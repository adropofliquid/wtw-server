package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One suggestion session (start → next/refine → accept). The request and the model transcript are kept. */
@Entity
@Table(name = "ai_session")
class AiSession(
    @Id var id: String = "",
    var userId: String = "",
    var context: String = "DAILY",
    var targetDate: LocalDate? = null,
    var dayType: String? = null,
    var comfort: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var vibes: List<String> = emptyList(),
    var note: String? = null,
    var style: String? = null,
    /** KMP DayWeather as sent. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var weather: Map<String, Any?>? = null,
    /** The wardrobe list as sent: [{id, category, attributes}]. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var wardrobeSnapshot: List<Map<String, Any?>>? = null,
    /** itemId -> ISO date. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var recentlyWorn: Map<String, Any?>? = null,
    var status: String = "ACTIVE",
    var initiator: String = "USER_INITIATED",
    var attemptNumber: Int = 0,
    var tooWarm: Boolean = false,
    var tooCold: Boolean = false,
    var tooCasual: Boolean = false,
    var tooFormal: Boolean = false,
    var differentTop: Boolean = false,
    var differentBottom: Boolean = false,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var rejectedSuggestionIds: List<String> = emptyList(),
    var acceptedSuggestionId: String? = null,
    /** Model transcript: a list of {role, content}. Copied rows that did not parse are {"raw": "..."}. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var conversationHistory: Any? = null,
    var startedAt: Instant = Instant.now(),
    var lastActivityAt: Instant = Instant.now(),
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
