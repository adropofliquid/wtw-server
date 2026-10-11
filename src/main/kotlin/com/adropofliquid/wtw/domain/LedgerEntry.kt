package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Append-only credit ledger row. Balance = sum of delta (current week's WEEKLY rows + all PURCHASED rows). */
@Entity
@Table(name = "ledger_entry")
class LedgerEntry(
    @Id var id: UUID = UUID.randomUUID(),
    var userId: String = "",
    /** WEEKLY or PURCHASED. */
    var bucket: String = "WEEKLY",
    var delta: Int = 0,
    var reason: String = "",
    var weekStart: LocalDate? = null,
    /** Idempotency key, unique per (user, reason). */
    var ref: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
