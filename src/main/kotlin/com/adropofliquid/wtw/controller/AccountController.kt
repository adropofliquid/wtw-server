package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.service.AccountService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RestController

/** Route 28 (brief 4.9): DELETE /account → 204, idempotent. The app calls it before deleting the Firebase user. */
@RestController
class AccountController(private val accounts: AccountService) {
    @DeleteMapping("/account")
    fun delete(@AuthenticationPrincipal uid: String): ResponseEntity<Void> {
        accounts.delete(uid)
        return ResponseEntity.noContent().build()
    }
}
