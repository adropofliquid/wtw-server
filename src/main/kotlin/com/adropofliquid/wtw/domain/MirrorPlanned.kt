package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UserDateId(var userId: String = "", var date: LocalDate = LocalDate.EPOCH) : Serializable

/** One plan per user per date. */
@Entity
@Table(name = "mirror_planned")
@IdClass(UserDateId::class)
class MirrorPlanned(
    @Id var userId: String = "",
    @Id var date: LocalDate = LocalDate.EPOCH,
    var outfitId: String? = null,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var deletedAt: Instant? = null,
    var syncedAt: Instant = Instant.now(),
)
