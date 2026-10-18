package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.dto.FeedbackRequest
import com.adropofliquid.wtw.repository.FeedbackRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * POST /feedback. Stores one submission, idempotent on the body's id (a resend stores nothing).
 * Only id and message can fail validation: the app keeps a rejected submission queued, so the
 * other fields are tidied rather than refused.
 */
@Service
class FeedbackService(private val feedback: FeedbackRepository, txManager: PlatformTransactionManager) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    fun submit(uid: String, req: FeedbackRequest) {
        val id = req.id?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ApiException.validation("id must be a UUID")
        val message = req.message?.trim().orEmpty()
        if (message.isEmpty() || message.length > MAX_MESSAGE) throw ApiException.validation("message must be 1 to $MAX_MESSAGE characters")
        val bodyUid = req.uid?.trim().orEmpty()
        if (bodyUid != uid) log.warn("feedback uid mismatch uid={} bodyUid={}", uidHash(uid), uidHash(bodyUid.ifEmpty { null }))
        val canReply = req.canReply == true
        val now = Instant.now()
        val stored = tx.execute {
            feedback.insertIfAbsent(
                id = id, userId = uid, bodyUid = bodyUid.take(128), message = message,
                category = req.category?.trim()?.lowercase()?.let { if (it in CATEGORIES) it else "other" },
                canReply = canReply,
                email = if (canReply) req.email?.trim()?.takeIf { it.isNotEmpty() }?.take(320) else null,
                appVersion = req.appVersion.tidy(64), platform = req.platform?.lowercase().tidy(16),
                osVersion = req.osVersion.tidy(64),
                createdAt = req.createdAt?.let(Instant::ofEpochMilli) ?: now, receivedAt = now,
            )
        }!!
        log.info("feedback {} uid={}", if (stored == 1) "stored" else "repeat", uidHash(uid))
    }

    private fun String?.tidy(max: Int) = this?.trim()?.takeIf { it.isNotEmpty() }?.take(max) ?: "unknown"

    companion object {
        const val MAX_MESSAGE = 4000
        val CATEGORIES = setOf("idea", "problem", "other")
    }
}
