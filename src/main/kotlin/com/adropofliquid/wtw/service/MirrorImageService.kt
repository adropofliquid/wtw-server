package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.msSince
import com.adropofliquid.wtw.dto.ItemImageResponse
import com.adropofliquid.wtw.repository.MirrorItemRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Route 10, PUT /mirror/items/{itemId}/images/{kind} (brief 4.2, keys 5.4).
 * - cutout, generated: converted to WebP (≤ IMAGE_MAX_SIDE, IMAGE_WEBP_QUALITY) at `users/{uid}/...`.
 * - original: the photo before background removal, at `user-raw/{uid}/...`. Kept as sent when it is a JPEG, PNG
 *   or WebP no larger than IMAGE_ORIGINAL_MAX_SIDE; otherwise shrunk to that and stored as WebP.
 * Sets the item's key and version, bumps synced_at only (not updated_at). A new upload replaces the image and gets
 * a new version (its link changes).
 */
@Service
class MirrorImageService(
    private val items: MirrorItemRepository,
    private val images: ImageProcessor,
    private val storage: ObjectStorage,
    private val urls: ImageUrls,
    private val props: WtwProperties,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)
    private val log = LoggerFactory.getLogger(javaClass)

    private class Stored(val bytes: ByteArray, val key: String, val contentType: String)

    fun upload(uid: String, itemId: String, kind: String, bytes: ByteArray): ItemImageResponse {
        if (kind !in KINDS) throw ApiException.validation("kind must be cutout, generated or original")
        check(uid, itemId)
        val start = System.nanoTime()
        val stored = if (kind == MirrorPushService.ORIGINAL) original(uid, itemId, bytes)
            else Stored(images.toWebp(bytes), Keys.itemImage(uid, itemId, kind), "image/webp")
        log.info("mirror image convert kind={} inBytes={} outBytes={} ms={}", kind, bytes.size, stored.bytes.size, msSince(start))
        val version = ImageUrls.version(stored.bytes)
        storage.put(Bucket.USER, stored.key, stored.bytes, stored.contentType)
        var replacedKey: String? = null
        try {
            tx.executeWithoutResult {
                // Checked again: a tombstone may have landed while the image was converting.
                val item = check(uid, itemId)
                when (kind) {
                    MirrorPushService.CUTOUT -> { item.cutoutKey = stored.key; item.cutoutVersion = version }
                    MirrorPushService.GENERATED -> { item.generatedKey = stored.key; item.generatedVersion = version }
                    else -> {
                        // The original's extension can change between uploads (jpg → webp): drop the old object after commit.
                        replacedKey = item.originalKey?.takeIf { it != stored.key }
                        item.originalKey = stored.key; item.originalVersion = version
                    }
                }
                item.syncedAt = Instant.now()
            }
        } catch (e: ApiException) {
            runCatching { storage.delete(Bucket.USER, stored.key) }
            throw e
        }
        replacedKey?.let { k -> runCatching { storage.delete(Bucket.USER, k) } }
        return ItemImageResponse(stored.key, urls.item(itemId, kind, version))
    }

    /** As sent when it is already small enough (no second compression); otherwise shrunk to WebP. */
    private fun original(uid: String, itemId: String, bytes: ByteArray): Stored {
        val max = props.image.originalMaxSide
        val img = images.decode(bytes)
        val format = images.format(bytes)
        if (format != null && maxOf(img.width, img.height) <= max) {
            return Stored(bytes, Keys.itemOriginal(uid, itemId, format.first), format.second)
        }
        val webp = images.encodeWebp(images.shrink(img, max), props.image.originalWebpQuality)
        return Stored(webp, Keys.itemOriginal(uid, itemId, "webp"), "image/webp")
    }

    class ItemImage(val bytes: ByteArray, val version: String, val contentType: String?)

    /** GET of the same route: the caller's own image only. Anyone else's item, a tombstone, or no image → 404. */
    fun read(uid: String, itemId: String, kind: String): ItemImage {
        val item = items.findById(itemId).orElse(null)
            ?.takeIf { it.userId == uid && it.deletedAt == null } ?: throw ApiException.notFound("No such image")
        val (key, version) = when (kind) {
            MirrorPushService.CUTOUT -> item.cutoutKey to item.cutoutVersion
            MirrorPushService.GENERATED -> item.generatedKey to item.generatedVersion
            MirrorPushService.ORIGINAL -> item.originalKey to item.originalVersion
            else -> throw ApiException.validation("kind must be cutout, generated or original")
        }
        val obj = key?.let { storage.get(Bucket.USER, it) } ?: throw ApiException.notFound("No such image")
        return ItemImage(obj.bytes, version ?: "0", obj.contentType)
    }

    /**
     * Id unknown → 409 ITEM_NOT_PUSHED (push the row first). Id owned by someone else, or tombstoned → 404.
     * Ids are device UUIDs, unique across users, so "unknown id" can only mean "not pushed yet".
     */
    private fun check(uid: String, itemId: String) = items.findById(itemId).orElse(null).let { item ->
        when {
            item == null -> throw ApiException.conflict("ITEM_NOT_PUSHED", "Push the item row before its image")
            item.userId != uid || item.deletedAt != null -> throw ApiException.notFound("No such item")
            else -> item
        }
    }

    private companion object {
        val KINDS = setOf(MirrorPushService.CUTOUT, MirrorPushService.GENERATED, MirrorPushService.ORIGINAL)
    }
}
