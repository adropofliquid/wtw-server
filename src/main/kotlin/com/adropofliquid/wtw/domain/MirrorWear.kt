package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One wear-log row per user per date. item_ids is a snapshot. */
@Entity
@Table(name = "mirror_wear")
@IdClass(UserDateId::class)
class MirrorWear(
    @Id var userId: String = "",
    @Id var date: LocalDate = LocalDate.EPOCH,
    var status: String? = null,
    var outfitId: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var itemIds: List<String> = emptyList(),
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var deletedAt: Instant? = null,
    var syncedAt: Instant = Instant.now(),
)
