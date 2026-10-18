package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.WtwProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant

/**
 * Stable image links, served by this API (no signed storage links). `?v=` changes when the image does, so a
 * phone can cache each link for good. Library: `/image/{id}`. A user's own image: the item's image route.
 */
@Component
class ImageUrls(props: WtwProperties) {
    private val base = props.publicBaseUrl.trimEnd('/')

    fun library(id: String, updatedAt: Instant) = "$base/image/$id?v=${updatedAt.toEpochMilli().toString(36)}"

    fun item(itemId: String, kind: String, version: String?) = "$base/mirror/items/$itemId/images/$kind?v=${version ?: "0"}"

    companion object {
        /** First 8 hex chars of SHA-256: the version of an uploaded image. */
        fun version(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }
    }
}
