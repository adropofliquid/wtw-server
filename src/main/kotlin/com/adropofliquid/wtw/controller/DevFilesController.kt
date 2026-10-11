package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.client.Bucket
import com.adropofliquid.wtw.client.LocalObjectStorage
import com.adropofliquid.wtw.service.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Not prod: serves LocalObjectStorage files so dev URLs work like presigned/public URLs. Not in API.md. */
@RestController
@Profile("!prod")
class DevFilesController(private val storage: LocalObjectStorage) {
    @GetMapping("/dev-files/{bucket}/**")
    fun file(@PathVariable bucket: String, req: HttpServletRequest): ResponseEntity<ByteArray> {
        val b = Bucket.entries.firstOrNull { it.name.equals(bucket, true) } ?: throw ApiException.notFound()
        val key = req.requestURI.substringAfter("/dev-files/$bucket/")
        val obj = storage.get(b, key) ?: throw ApiException.notFound()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(obj.contentType ?: "application/octet-stream")).body(obj.bytes)
    }
}
