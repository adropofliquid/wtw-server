package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.dto.ItemImageResponse
import com.adropofliquid.wtw.dto.MirrorPullResponse
import com.adropofliquid.wtw.dto.MirrorPushRequest
import com.adropofliquid.wtw.dto.MirrorPushResponse
import com.adropofliquid.wtw.service.ApiException
import com.adropofliquid.wtw.service.MirrorImageService
import com.adropofliquid.wtw.service.MirrorPullService
import com.adropofliquid.wtw.service.MirrorPushService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** Routes 8–10 (brief 4.2): mirror push, pull and item images. Not metered. */
@RestController
@RequestMapping("/mirror")
class MirrorController(
    private val pushService: MirrorPushService,
    private val pullService: MirrorPullService,
    private val imageService: MirrorImageService,
    private val props: WtwProperties,
) {
    @PostMapping("/push")
    fun push(@AuthenticationPrincipal uid: String, @RequestBody req: MirrorPushRequest): MirrorPushResponse =
        pushService.push(uid, req)

    @GetMapping("/pull")
    fun pull(@AuthenticationPrincipal uid: String, @RequestParam(defaultValue = "0") since: Long): MirrorPullResponse =
        pullService.pull(uid, since)

    /**
     * Raw image bytes (image/png, image/webp or image/jpeg; anything else → 400 VALIDATION).
     * The body is read with a cap of UPLOAD_MAX_BYTES (413 above it) instead of `@RequestBody ByteArray`,
     * so an oversized body is never read whole into memory.
     */
    @PutMapping("/items/{itemId}/images/{kind}", consumes = ["image/png", "image/webp", "image/jpeg"])
    fun image(
        @AuthenticationPrincipal uid: String,
        @PathVariable itemId: String,
        @PathVariable kind: String,
        request: HttpServletRequest,
    ): ItemImageResponse {
        val max = props.image.uploadMaxBytes
        if (request.contentLengthLong > max) throw ApiException.tooLarge("Image over $max bytes")
        val bytes = request.inputStream.readNBytes((max + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (bytes.size > max) throw ApiException.tooLarge("Image over $max bytes")
        if (bytes.isEmpty()) throw ApiException.validation("Empty body")
        return imageService.upload(uid, itemId, kind, bytes)
    }
}
