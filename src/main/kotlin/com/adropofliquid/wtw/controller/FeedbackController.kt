package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.FeedbackRequest
import com.adropofliquid.wtw.service.FeedbackService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** POST /feedback: in-app feedback. Not metered. 204 whether stored now or already stored (resend). */
@RestController
@RequestMapping("/feedback")
class FeedbackController(private val service: FeedbackService) {

    @PostMapping
    fun submit(@AuthenticationPrincipal uid: String, @RequestBody req: FeedbackRequest): ResponseEntity<Void> {
        service.submit(uid, req)
        return ResponseEntity.noContent().build()
    }
}
