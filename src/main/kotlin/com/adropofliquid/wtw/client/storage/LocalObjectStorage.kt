package com.adropofliquid.wtw.client.storage

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.config.timed
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

/** STORAGE_MODE=local (dev default): stores objects as files under `STORAGE_LOCAL_DIR/{bucket}/{key}`. Read through the API like S3 objects. */
@Component
@ConditionalOnProperty(name = ["wtw.storage.mode"], havingValue = "local")
class LocalObjectStorage(private val props: WtwProperties) : ObjectStorage {
    private val root: Path = Paths.get(props.storage.localDir).toAbsolutePath().normalize()

    private val log = LoggerFactory.getLogger("storage")

    private fun dir(bucket: Bucket) = root.resolve(bucket.name.lowercase())

    fun path(bucket: Bucket, key: String): Path {
        val p = dir(bucket).resolve(key).normalize()
        require(p.startsWith(dir(bucket))) { "bad key" }
        return p
    }

    override fun put(bucket: Bucket, key: String, bytes: ByteArray, contentType: String, cacheControl: String?) {
        log.timed("storage put", { _: Any? -> "bucket=$bucket key=${Keys.forLog(key)} bytes=${bytes.size}" }) {
            val p = path(bucket, key)
            p.parent.createDirectories()
            p.writeBytes(bytes)
        }
    }

    override fun get(bucket: Bucket, key: String): StoredObject? =
        log.timed("storage get", { o: StoredObject? -> "bucket=$bucket key=${Keys.forLog(key)} bytes=${o?.bytes?.size ?: "missing"}" }) {
            val p = path(bucket, key)
            if (p.isRegularFile()) StoredObject(p.readBytes(), contentTypeOf(key)) else null
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

    companion object {
        fun contentTypeOf(key: String) = when (key.substringAfterLast('.').lowercase()) {
            "webp" -> "image/webp"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
