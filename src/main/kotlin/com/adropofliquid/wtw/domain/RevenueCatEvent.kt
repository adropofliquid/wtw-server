package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Raw RevenueCat webhook log; the PK (event id) makes the webhook idempotent. */
@Entity
@Table(name = "revenuecat_event")
class RevenueCatEvent(
    @Id var id: String = "",
    var type: String = "",
    var appUserId: String = "",
    var productId: String? = null,
    var transactionId: String? = null,
    var environment: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var payload: Map<String, Any?> = emptyMap(),
    var outcome: String? = null,
    var receivedAt: Instant = Instant.now(),
    var processedAt: Instant? = null,
    var updatedAt: Instant = Instant.now(),
)
