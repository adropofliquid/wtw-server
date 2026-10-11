package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One generated suggestion (the user's suggestion history; restorable through the pull). */
@Entity
@Table(name = "ai_suggestion")
class AiSuggestion(
    @Id var id: String = "",
    var userId: String = "",
    var sessionId: String? = null,
    var kind: String = "session",
    var attemptNumber: Int = 1,
    var name: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var itemIds: List<String> = emptyList(),
    var reasoning: String? = null,
    var occasion: String? = null,
    var dayType: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var vibes: List<String> = emptyList(),
    var note: String? = null,
    var accepted: Boolean = false,
    var acceptedAt: Instant? = null,
    var rejected: Boolean = false,
    var rejectionReason: String? = null,
    var feedbackText: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
