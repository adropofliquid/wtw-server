package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.PushTokenDeleteRequest
import com.adropofliquid.wtw.dto.PushTokenRequest
import com.adropofliquid.wtw.dto.PushTokenResponse
import com.adropofliquid.wtw.service.PushTokenService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** Routes 24–26 (brief 4.7). Shapes match the app's HttpPush exactly. POST and PUT do the same upsert. */
@RestController
@RequestMapping("/user/notification")
class PushTokenController(private val service: PushTokenService) {

    @PostMapping
    fun create(@AuthenticationPrincipal uid: String, @RequestBody req: PushTokenRequest): PushTokenResponse = service.upsert(uid, req)

    @PutMapping
    fun update(@AuthenticationPrincipal uid: String, @RequestBody req: PushTokenRequest): PushTokenResponse = service.upsert(uid, req)

    /** 204 either way (call on sign-out). */
    @DeleteMapping
    fun delete(@AuthenticationPrincipal uid: String, @RequestBody(required = false) req: PushTokenDeleteRequest?): ResponseEntity<Void> {
        service.delete(uid, req?.fcmToken)
        return ResponseEntity.noContent().build()
    }
}
