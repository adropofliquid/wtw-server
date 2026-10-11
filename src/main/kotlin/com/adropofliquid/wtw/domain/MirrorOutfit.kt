package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "mirror_outfit")
class MirrorOutfit(
    @Id var id: String = "",
    var userId: String = "",
    var name: String = "",
    var createdByAi: Boolean = false,
    var occasion: String? = null,
    var note: String? = null,
    var layout: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var deletedAt: Instant? = null,
    var syncedAt: Instant = Instant.now(),
)
