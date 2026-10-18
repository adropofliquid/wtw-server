package com.adropofliquid.wtw.service

import org.slf4j.LoggerFactory
import com.adropofliquid.wtw.config.msSince
import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.dto.ItemImageResponse
import com.adropofliquid.wtw.repository.MirrorItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Route 10, PUT /mirror/items/{itemId}/images/{kind} (brief 4.2, keys 5.4).
 * Converts to WebP (≤ IMAGE_MAX_SIDE, IMAGE_WEBP_QUALITY), stores it at the item's key, sets the key,
 * bumps synced_at only (not updated_at). A new upload overwrites the same key.
 */
@Service
class MirrorImageService(
    private val items: MirrorItemRepository,
    private val images: ImageProcessor,
    private val storage: ObjectStorage,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)
    private val log = LoggerFactory.getLogger(javaClass)

    fun upload(uid: String, itemId: String, kind: String, bytes: ByteArray): ItemImageResponse {
        if (kind != MirrorPushService.CUTOUT && kind != MirrorPushService.GENERATED) {
            throw ApiException.validation("kind must be cutout or generated")
        }
        check(uid, itemId)
        val start = System.nanoTime()
        val webp = images.toWebp(bytes)
        log.info("mirror image convert kind={} inBytes={} outBytes={} ms={}", kind, bytes.size, webp.size, msSince(start))
        val key = Keys.itemImage(uid, itemId, kind)
        storage.put(Bucket.USER, key, webp, "image/webp")
        try {
            tx.executeWithoutResult {
                // Checked again: a tombstone may have landed while the image was converting.
                val item = check(uid, itemId)
                if (kind == MirrorPushService.CUTOUT) item.cutoutKey = key else item.generatedKey = key
                item.syncedAt = Instant.now()
            }
        } catch (e: ApiException) {
            runCatching { storage.delete(Bucket.USER, key) }
            throw e
        }
        return ItemImageResponse(key, storage.presignedUrl(Bucket.USER, key))
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
}
