package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The user root (Firebase uid). A stub row (id only) is made by the auth filter on first sight. */
@Entity
@Table(name = "user_profile")
class UserProfile(
    @Id var id: String = "",
    var displayName: String? = null,
    var style: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var vibes: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var events: List<String> = emptyList(),
    var timezone: String? = null,
    var lastNotifiedAt: Instant? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    /** True when no profile field was ever set (only the stub exists). */
    val isStub: Boolean get() = displayName == null && style == null && vibes.isEmpty() && events.isEmpty() && timezone == null
}
