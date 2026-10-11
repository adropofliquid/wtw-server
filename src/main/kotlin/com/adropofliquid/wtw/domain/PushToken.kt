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
@Table(name = "push_token")
class PushToken(
    @Id var id: UUID = UUID.randomUUID(),
    var userId: String = "",
    var fcmToken: String = "",
    /** ios or android. */
    var platform: String = "",
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
