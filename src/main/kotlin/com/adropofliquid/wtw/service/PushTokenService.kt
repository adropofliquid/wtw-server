package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.domain.PushToken
import com.adropofliquid.wtw.dto.PushTokenRequest
import com.adropofliquid.wtw.dto.PushTokenResponse
import com.adropofliquid.wtw.repository.PushTokenRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Routes 24–26 (brief 4.7). POST and PUT both upsert on fcm_token; a token held by another user
 * (a phone that changed account) moves to the caller. DELETE removes the token only if it is the caller's.
 */
@Service
class PushTokenService(private val tokens: PushTokenRepository, txManager: PlatformTransactionManager) {
    private val tx = TransactionTemplate(txManager)

    fun upsert(uid: String, req: PushTokenRequest): PushTokenResponse {
        val token = req.fcmToken?.trim()
        if (token.isNullOrEmpty() || token.length > 4096) throw ApiException.validation("fcmToken is required")
        val platform = req.platform?.trim()?.lowercase()
        if (platform !in PLATFORMS) throw ApiException.validation("platform must be ios or android")
        // Two first registrations of one token at once: the loser hits the unique key; one retry finds the row.
        val saved = try {
            write(uid, token, platform!!)
        } catch (_: DataIntegrityViolationException) {
            write(uid, token, platform!!)
        }
        return PushTokenResponse(saved.id.toString(), saved.fcmToken, saved.platform)
    }

    private fun write(uid: String, token: String, platform: String): PushToken = tx.execute {
        val now = Instant.now()
        val row = tokens.findByFcmToken(token) ?: PushToken(fcmToken = token, createdAt = now)
        row.userId = uid
        row.platform = platform
        row.updatedAt = now
        tokens.saveAndFlush(row)
    }!!

    /** 204 either way; deletes only the caller's own row. */
    fun delete(uid: String, fcmToken: String?) {
        val token = fcmToken?.trim()?.takeIf { it.isNotEmpty() } ?: return
        tx.executeWithoutResult {
            tokens.findByFcmToken(token)?.takeIf { it.userId == uid }?.let(tokens::delete)
        }
    }

    companion object { val PLATFORMS = setOf("ios", "android") }
}
