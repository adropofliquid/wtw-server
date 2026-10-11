package com.adropofliquid.wtw.client.storage

import com.adropofliquid.wtw.config.WtwProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * STORAGE_MODE=s3 (prod default; docker compose uses it with MinIO): S3-compatible storage (AWS S3 or Cloudflare R2). Set STORAGE_ENDPOINT for R2
 * (`https://<account>.r2.cloudflarestorage.com`, region `auto`); leave it empty for AWS S3.
 */
@Component
@ConditionalOnProperty(name = ["wtw.storage.mode"], havingValue = "s3")
class S3ObjectStorage(private val props: WtwProperties) : ObjectStorage {
    private val s = props.storage
    private val creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKey, s.secretKey))
    private val region = Region.of(s.region.ifBlank { "auto" })
    private val pathStyle = S3Configuration.builder().pathStyleAccessEnabled(s.endpoint.isNotBlank()).build()

    private val client: S3Client = S3Client.builder().credentialsProvider(creds).region(region)
        .serviceConfiguration(pathStyle)
        .apply { if (s.endpoint.isNotBlank()) endpointOverride(URI(s.endpoint)) }
        .build()

    private val presigner: S3Presigner = S3Presigner.builder().credentialsProvider(creds).region(region)
        .serviceConfiguration(pathStyle)
        .apply { if (s.endpoint.isNotBlank()) endpointOverride(URI(s.endpoint)) }
        .build()

    private fun name(bucket: Bucket) = if (bucket == Bucket.USER) s.bucketUser else s.bucketLibrary

    override fun put(bucket: Bucket, key: String, bytes: ByteArray, contentType: String, cacheControl: String?) {
        val req = PutObjectRequest.builder().bucket(name(bucket)).key(key).contentType(contentType)
            .apply { if (cacheControl != null) cacheControl(cacheControl) }.build()
        client.putObject(req, RequestBody.fromBytes(bytes))
    }

    override fun get(bucket: Bucket, key: String): StoredObject? = try {
        val r = client.getObjectAsBytes(GetObjectRequest.builder().bucket(name(bucket)).key(key).build())
        StoredObject(r.asByteArray(), r.response().contentType())
    } catch (_: NoSuchKeyException) {
        null
    }

    override fun exists(bucket: Bucket, key: String): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(name(bucket)).key(key).build()); true
    } catch (_: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) false else throw e
    }

    override fun delete(bucket: Bucket, key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(name(bucket)).key(key).build())
    }

    override fun deletePrefix(bucket: Bucket, prefix: String): Int {
        var count = 0
        client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(name(bucket)).prefix(prefix).build())
            .contents().map { it.key() }.chunked(1000).forEach { keys ->
                // One-by-one delete: R2 and S3 both support it; avoids the Content-MD5 rules of batch delete.
                keys.forEach { delete(bucket, it); count++ }
            }
        return count
    }

    override fun presignedUrl(key: String): String = presigner.presignGetObject {
        it.signatureDuration(s.presignTtl).getObjectRequest(GetObjectRequest.builder().bucket(s.bucketUser).key(key).build())
    }.url().toString()

    override fun publicUrl(key: String) = "${s.libraryPublicUrl.trimEnd('/')}/$key"
}
