package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.AiSuggestion
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface AiSuggestionRepository : JpaRepository<AiSuggestion, String> {
    /** Daily generation cap: suggestions made since the start of the user's day. */
    fun countByUserIdAndCreatedAtAfter(userId: String, since: Instant): Long
    fun findByIdAndSessionId(id: String, sessionId: String): AiSuggestion?
    fun findBySessionIdOrderByAttemptNumberAsc(sessionId: String): List<AiSuggestion>
    /** Pull: suggestion history changed after `since`. */
    fun findByUserIdAndUpdatedAtAfter(userId: String, since: Instant): List<AiSuggestion>
    /** Stylist notes: recent history. */
    fun findTop100ByUserIdOrderByCreatedAtDesc(userId: String): List<AiSuggestion>
    fun deleteByUserId(userId: String): Long
}
