package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.service.AiItemService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/** Route 21 (brief 4.5): background removal, proxied to rembg. Free, capped (remove_bg_daily_cap). Stores nothing. */
@RestController
class BackgroundController(private val items: AiItemService) {

    @PostMapping("/images/remove-background", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun remove(@AuthenticationPrincipal uid: String, @RequestPart("photo", required = false) photo: MultipartFile?): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(items.removeBackground(uid, photo))
}
