package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.AiSession
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface AiSessionRepository : JpaRepository<AiSession, String> {
    fun findByIdAndUserId(id: String, userId: String): AiSession?
    /** Session sweep: open (ACTIVE or LOW_CONFIDENCE) sessions idle for 24 h. */
    fun findByStatusInAndLastActivityAtBefore(statuses: Collection<String>, before: Instant): List<AiSession>
    /** Habit builder: did the user accept a suggestion in [from, to)? */
    fun existsByUserIdAndAcceptedSuggestionIdIsNotNullAndLastActivityAtGreaterThanEqualAndLastActivityAtLessThan(
        userId: String, from: Instant, to: Instant,
    ): Boolean
    fun deleteByUserId(userId: String): Long
    /** Data copy (7.9): per-user count. */
    fun countByUserId(userId: String): Long
}
