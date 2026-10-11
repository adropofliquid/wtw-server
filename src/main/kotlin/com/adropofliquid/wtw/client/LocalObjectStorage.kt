package com.adropofliquid.wtw.client

import com.adropofliquid.wtw.config.WtwProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/** Not prod: stores objects as files under `STORAGE_LOCAL_DIR/{bucket}/{key}`. URLs point at DevFilesController. */
@Component
@Profile("!prod")
class LocalObjectStorage(private val props: WtwProperties) : ObjectStorage {
    private val root: Path = Paths.get(props.storage.localDir).toAbsolutePath().normalize()

    private fun dir(bucket: Bucket) = root.resolve(bucket.name.lowercase())

    fun path(bucket: Bucket, key: String): Path {
        val p = dir(bucket).resolve(key).normalize()
        require(p.startsWith(dir(bucket))) { "bad key" }
        return p
    }

    override fun put(bucket: Bucket, key: String, bytes: ByteArray, contentType: String, cacheControl: String?) {
        val p = path(bucket, key)
        p.parent.createDirectories()
        p.writeBytes(bytes)
    }

    override fun get(bucket: Bucket, key: String): StoredObject? {
        val p = path(bucket, key)
        if (!p.isRegularFile()) return null
        return StoredObject(p.readBytes(), contentTypeOf(key))
    }

    override fun exists(bucket: Bucket, key: String) = path(bucket, key).isRegularFile()

    override fun delete(bucket: Bucket, key: String) {
        path(bucket, key).deleteIfExists()
    }

    override fun deletePrefix(bucket: Bucket, prefix: String): Int {
        val base = dir(bucket)
        if (!base.exists()) return 0
        val files = Files.walk(base).use { s ->
            s.filter { it.isRegularFile() && base.relativize(it).toString().replace('\\', '/').startsWith(prefix) }.toList()
        }
        files.forEach { it.deleteIfExists() }
        return files.size
    }

    override fun presignedUrl(key: String) = "${props.storage.localBaseUrl.trimEnd('/')}/dev-files/user/$key"

    override fun publicUrl(key: String) = "${props.storage.localBaseUrl.trimEnd('/')}/dev-files/library/$key"

    companion object {
        fun contentTypeOf(key: String) = when (key.substringAfterLast('.').lowercase()) {
            "webp" -> "image/webp"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
