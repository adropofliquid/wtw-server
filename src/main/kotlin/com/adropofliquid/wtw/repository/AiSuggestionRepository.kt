package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.AiSuggestion
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface AiSuggestionRepository : JpaRepository<AiSuggestion, String> {
    /** Daily generation cap: suggestions made since the start of the user's day. */
    fun countByUserIdAndCreatedAtAfter(userId: String, since: Instant): Long
}
