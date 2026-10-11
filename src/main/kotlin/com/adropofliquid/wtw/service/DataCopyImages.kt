package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.WtwProperties
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Outcome of copying one item's cut-out (brief 7.3). */
enum class ImageOutcome { COPIED, WOULD_COPY, ALREADY_PRESENT, MISSING, UNREADABLE, ERROR }

data class ImageResult(val itemId: String, val outcome: ImageOutcome, val key: String?, val bucketTried: String?, val oldKey: String?)

/**
 * Old S3 → new user bucket, for own-upload items. Reads PNG bytes whatever the key's extension says, converts
 * with ImageProcessor (≤600, WebP q80) and writes `users/{uid}/items/{id}/cutout.webp`. Skips a key that exists.
 * Dry-run: HEAD only, writes nothing. Built only when OLD_S3 credentials are set and `--skip-images` is absent.
 */
class DataCopyImages(
    props: WtwProperties,
    private val storage: ObjectStorage,
    private val imageProcessor: ImageProcessor,
    concurrency: Int,
) : AutoCloseable {
    private val s3: S3Client = S3Client.builder()
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.old.s3AccessKey, props.old.s3Secret)))
        .region(Region.of(props.old.s3Region.ifBlank { "eu-west-2" }))
        .build()
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private val gate = Semaphore(concurrency)

    /** Copies (or, in dry-run, checks) every item in [work] in parallel. [work] = item id → its old image row. */
    fun run(uid: String, work: Map<String, OldImage?>, apply: Boolean): List<ImageResult> =
        work.map { (itemId, img) ->
            pool.submit(Callable {
                gate.acquire()
                try { one(uid, itemId, img, apply) } finally { gate.release() }
            })
        }.map { it.get() }

    private fun one(uid: String, itemId: String, img: OldImage?, apply: Boolean): ImageResult {
        val key = Keys.itemImage(uid, itemId, "cutout")
        val oldKey = img?.fileName
        val first = DataCopyTransforms.bucketFromUrl(img?.url) ?: BUCKETS[0]
        val buckets = listOf(first) + BUCKETS.filter { it != first }
        if (oldKey.isNullOrBlank()) return ImageResult(itemId, ImageOutcome.MISSING, null, null, oldKey)
        return try {
            if (storage.exists(Bucket.USER, key)) return ImageResult(itemId, ImageOutcome.ALREADY_PRESENT, key, null, oldKey)
            if (!apply) {
                val found = buckets.firstOrNull { head(it, oldKey) }
                return if (found != null) ImageResult(itemId, ImageOutcome.WOULD_COPY, key, found, oldKey)
                else ImageResult(itemId, ImageOutcome.MISSING, null, buckets.joinToString("|"), oldKey)
            }
            var bytes: ByteArray? = null
            var from: String? = null
            for (b in buckets) { bytes = get(b, oldKey); if (bytes != null) { from = b; break } }
            if (bytes == null) return ImageResult(itemId, ImageOutcome.MISSING, null, buckets.joinToString("|"), oldKey)
            val webp = try { imageProcessor.toWebp(bytes) } catch (_: Exception) {
                return ImageResult(itemId, ImageOutcome.UNREADABLE, null, from, oldKey)
            }
            storage.put(Bucket.USER, key, webp, "image/webp")
            ImageResult(itemId, ImageOutcome.COPIED, key, from, oldKey)
        } catch (_: SdkException) {
            ImageResult(itemId, ImageOutcome.ERROR, null, buckets.joinToString("|"), oldKey)
        }
    }

    private fun head(bucket: String, key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()); true
    } catch (_: NoSuchKeyException) { false } catch (e: S3Exception) { if (e.statusCode() == 404 || e.statusCode() == 403) false else throw e }

    private fun get(bucket: String, key: String): ByteArray? = try {
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
    } catch (_: NoSuchKeyException) { null } catch (e: S3Exception) { if (e.statusCode() == 404 || e.statusCode() == 403) null else throw e }

    override fun close() { pool.close(); s3.close() }

    companion object { val BUCKETS = listOf("whatshouldiwearr", "whatshouldiwear") }
}
