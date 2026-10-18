package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.Feedback
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface FeedbackRepository : JpaRepository<Feedback, UUID> {
    /** Returns 1 when stored, 0 when a row with this id already exists (a resend). */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO feedback (id, user_id, body_uid, message, category, can_reply, email,
                              app_version, platform, os_version, created_at, received_at)
        VALUES (:id, :userId, :bodyUid, :message, :category, :canReply, :email,
                :appVersion, :platform, :osVersion, :createdAt, :receivedAt)
        ON CONFLICT (id) DO NOTHING""",
    )
    fun insertIfAbsent(
        id: UUID, userId: String, bodyUid: String, message: String, category: String?, canReply: Boolean,
        email: String?, appVersion: String, platform: String, osVersion: String, createdAt: Instant, receivedAt: Instant,
    ): Int

    fun deleteByUserId(userId: String): Long
}
