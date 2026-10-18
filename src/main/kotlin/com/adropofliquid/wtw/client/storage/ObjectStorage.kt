package com.adropofliquid.wtw.client.storage

import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash

/** The two buckets (brief 5.4). Both private; clients read images through the API (ImageUrls), never from storage directly. */
enum class Bucket { USER, LIBRARY }

class StoredObject(val bytes: ByteArray, val contentType: String?)

/**
 * Object storage behind one interface.
 * - STORAGE_MODE=s3 (prod default): S3ObjectStorage (S3, Cloudflare R2 or MinIO; S3-compatible API).
 * - STORAGE_MODE=local (dev default): LocalObjectStorage (files under STORAGE_LOCAL_DIR).
 *
 * Keys (brief 5.4): `users/{uid}/items/{itemId}/cutout.webp`, `.../generated.webp`, `library/{libraryId}.webp`,
 * and the original photo at `user-raw/{uid}/items/{itemId}/original.{jpg|png|webp}`.
 * Use [Keys] to build them.
 */
interface ObjectStorage {
    fun put(bucket: Bucket, key: String, bytes: ByteArray, contentType: String, cacheControl: String? = null)
    fun get(bucket: Bucket, key: String): StoredObject?
    fun exists(bucket: Bucket, key: String): Boolean
    fun delete(bucket: Bucket, key: String)
    /** Deletes every object whose key starts with [prefix]. Returns how many were deleted. */
    fun deletePrefix(bucket: Bucket, prefix: String): Int
}

object Keys {
    fun userPrefix(uid: String) = "users/$uid/"
    fun itemImage(uid: String, itemId: String, kind: String) = "users/$uid/items/$itemId/$kind.webp"
    /** The original photo: its own prefix, extension as stored (jpg, png or webp). */
    fun itemOriginal(uid: String, itemId: String, ext: String) = "user-raw/$uid/items/$itemId/original.$ext"
    fun rawPrefix(uid: String) = "user-raw/$uid/"
    fun library(libraryId: String) = "library/$libraryId.webp"
    /** A key safe to log: the uid in `users/{uid}/` or `user-raw/{uid}/` is replaced by its hash. */
    fun forLog(key: String) = key.replace(Regex("^(users|user-raw)/([^/]+)/")) { "${it.groupValues[1]}/${uidHash(it.groupValues[2])}/" }
}
