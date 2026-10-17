package com.adropofliquid.wtw.client.storage

/** The two buckets (brief 5.4). Both private; clients read through presigned GETs. */
enum class Bucket { USER, LIBRARY }

class StoredObject(val bytes: ByteArray, val contentType: String?)

/**
 * Object storage behind one interface.
 * - STORAGE_MODE=s3 (prod default): S3ObjectStorage (S3, Cloudflare R2 or MinIO; S3-compatible API).
 * - STORAGE_MODE=local (dev default): LocalObjectStorage (files under STORAGE_LOCAL_DIR, served at /dev-files/...).
 *
 * Keys (brief 5.4): `users/{uid}/items/{itemId}/cutout.webp`, `.../generated.webp`, `library/{libraryId}.webp`.
 * Use [Keys] to build them.
 */
interface ObjectStorage {
    fun put(bucket: Bucket, key: String, bytes: ByteArray, contentType: String, cacheControl: String? = null)
    fun get(bucket: Bucket, key: String): StoredObject?
    fun exists(bucket: Bucket, key: String): Boolean
    fun delete(bucket: Bucket, key: String)
    /** Deletes every object whose key starts with [prefix]. Returns how many were deleted. */
    fun deletePrefix(bucket: Bucket, prefix: String): Int
    /** Time-limited GET URL for a private object (STORAGE_PRESIGN_TTL). */
    fun presignedUrl(bucket: Bucket, key: String): String
}

object Keys {
    fun userPrefix(uid: String) = "users/$uid/"
    fun itemImage(uid: String, itemId: String, kind: String) = "users/$uid/items/$itemId/$kind.webp"
    fun library(libraryId: String) = "library/$libraryId.webp"
}
