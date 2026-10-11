package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Live config row (notification copy, ledger numbers). Read via AppConfigService (5-minute cache). */
@Entity
@Table(name = "app_config")
class AppConfigEntry(
    @Id var id: String = "",
    var name: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var configMap: Map<String, Any?> = emptyMap(),
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
