package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.timed
import com.adropofliquid.wtw.domain.LibraryItem
import com.adropofliquid.wtw.dto.LibraryItemResponse
import com.adropofliquid.wtw.dto.StarterItemResponse
import com.adropofliquid.wtw.repository.LibraryItemRepository
import com.adropofliquid.wtw.repository.UserProfileRepository
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * The Library (brief 3.3, 4.3): all active items, the compat starter list, and the compat image lookup.
 * The active rows are cached for 10 minutes; a re-seed or an `active` change shows up within that time.
 */
@Service
class LibraryService(
    private val repo: LibraryItemRepository,
    private val profiles: UserProfileRepository,
    private val storage: ObjectStorage,
    private val urls: ImageUrls,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    /** Image bytes by "id@version", capped by size. */
    private val images = Caffeine.newBuilder().maximumWeight(64L * 1024 * 1024)
        .weigher<String, ByteArray> { _, v -> v.size }.build<String, ByteArray>()
    private val cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).build<String, Map<String, LibraryItem>>()

    /** Active rows by id, starters first, then by name (stable order for the app). */
    private fun active(): Map<String, LibraryItem> = cache.get("all") {
        log.timed("library cache load", { m: Map<String, LibraryItem> -> "rows=${m.size}" }) { load() }
    }

    private fun load(): Map<String, LibraryItem> =
        repo.findByActiveTrue()
            .sortedWith(compareBy<LibraryItem>({ it.source != STARTER }, { it.name.lowercase() }, { it.id }))
            .associateBy { it.id }

    /** Route 11. */
    fun all(): List<LibraryItemResponse> = active().values.map { it.toResponse() }

    /**
     * Changes when any active row is added, removed or updated (a re-seed bumps `updated_at`). Weak (`W/`):
     * Tomcat does not gzip a response with a strong ETag, and the gzipped bytes differ from the plain ones anyway.
     */
    fun listEtag(): String {
        val rows = active().values
        val newest = rows.maxOfOrNull { it.updatedAt.toEpochMilli() } ?: 0
        return "W/\"lib-${rows.size}-${newest.toString(36)}\""
    }

    /**
     * Route 12, same filter as the old `UserOnboardingService.getStarterItems`: each filter applies only when
     * the profile field is set. No style and no vibes → every starter. Old vibe words are mapped first. When the
     * vibes match nothing, the vibe filter is dropped; when style matches nothing too, every starter.
     */
    fun starterItems(uid: String): List<StarterItemResponse> {
        val profile = profiles.findById(uid).orElse(null)
        val style = profile?.style?.takeIf { it.isNotBlank() }
        val vibes = Vocab.vibes(profile?.vibes.orEmpty()).toSet()
        val starters = active().values.filter { it.source == STARTER }
        val byStyle = starters.filter { style == null || style in it.styles }.ifEmpty { starters }
        return byStyle.filter { vibes.isEmpty() || it.vibes.any { v -> v in vibes } }.ifEmpty { byStyle }
            .map { StarterItemResponse(it.id, it.fileName ?: it.name, it.description, it.category, it.vibes, it.styles) }
    }

    class LibraryImage(val bytes: ByteArray, val version: String)

    /**
     * Route 13: a Library image by item id, from memory after the first read (the whole Library is ~13 MB).
     * 404 if the id is not an active Library item or its image is missing.
     */
    fun image(id: String): LibraryImage {
        val item = active()[id] ?: throw ApiException.notFound()
        val version = item.updatedAt.toEpochMilli().toString(36)
        val cacheKey = "$id@$version"
        val bytes = images.getIfPresent(cacheKey)
            ?: storage.get(Bucket.LIBRARY, item.imageKey)?.bytes?.also { images.put(cacheKey, it) }
            ?: throw ApiException.notFound()
        return LibraryImage(bytes, version)
    }

    fun evictCache() {
        cache.invalidateAll()
        images.invalidateAll()
    }

    private fun LibraryItem.toResponse() = LibraryItemResponse(
        id = id, source = source, name = name, category = category, description = description,
        vibes = vibes, styles = styles, brand = brand, keywords = keywords,
        imageUrl = urls.library(id, updatedAt), attributes = attributes,
    )

    companion object {
        const val STARTER = "starter"
        const val GENERATED = "generated"
    }
}
