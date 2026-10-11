package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.service.ApiException
import com.adropofliquid.wtw.service.RevenueCatWebhookService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

/**
 * Route 23 (brief 4.6, 5.7): RevenueCat webhook. Public in SecurityConfig; guarded here by a shared secret:
 * the `Authorization` header must equal RC_WEBHOOK_AUTH exactly (constant-time compare).
 * If RC_WEBHOOK_AUTH is empty every call is refused (401), so a missing setting never opens the route.
 */
@RestController
class RevenueCatWebhookController(private val webhook: RevenueCatWebhookService, private val props: WtwProperties) {

    @PostMapping("/webhooks/revenuecat")
    fun receive(
        @RequestHeader("Authorization", required = false) auth: String?,
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Any?> {
        if (!authorized(auth)) throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Bad webhook authorization")
        webhook.handle(body)
        return emptyMap()
    }

    private fun authorized(auth: String?): Boolean {
        val secret = props.revenuecat.webhookAuth
        if (secret.isEmpty() || auth == null) return false
        return MessageDigest.isEqual(auth.toByteArray(), secret.toByteArray())
    }
}
