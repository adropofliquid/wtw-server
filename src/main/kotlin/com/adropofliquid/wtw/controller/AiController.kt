package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.DescribeRequest
import com.adropofliquid.wtw.dto.DescribeResponse
import com.adropofliquid.wtw.service.AiItemService
import com.adropofliquid.wtw.service.OutfitDescriptionService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/** Optional header on the two charged AI routes (review S-3). */
const val IDEMPOTENCY_KEY = "Idempotency-Key"

/** Routes 14–16 (brief 4.4): read a photo, AI studio image, outfit review. */
@RestController
@RequestMapping("/ai")
class AiController(private val items: AiItemService, private val descriptions: OutfitDescriptionService) {

    /** Free, capped (attributes_daily_cap). Superset ItemAttributes + `filter`. */
    @PostMapping("/attributes", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun attributes(@AuthenticationPrincipal uid: String, @RequestPart("photo", required = false) photo: MultipartFile?): Map<String, Any?> =
        items.attributes(uid, photo)

    /** cost_ai_image. image/png bytes + X-Credits-Balance. */
    @PostMapping("/generate-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun generateImage(
        @AuthenticationPrincipal uid: String,
        @RequestPart("photo", required = false) photo: MultipartFile?,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<ByteArray> {
        val r = items.generateImage(uid, photo, idempotencyKey)
        return okWithCredits(r.balance, r.body, MediaType.IMAGE_PNG)
    }

    /** cost_outfit_description. {review, occasion} + X-Credits-Balance. */
    @PostMapping("/describe-outfit")
    fun describe(
        @AuthenticationPrincipal uid: String,
        @RequestBody req: DescribeRequest,
        @RequestHeader(IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<DescribeResponse> {
        val r = descriptions.describe(uid, req, idempotencyKey)
        return okWithCredits(r.balance, r.body)
    }
}
