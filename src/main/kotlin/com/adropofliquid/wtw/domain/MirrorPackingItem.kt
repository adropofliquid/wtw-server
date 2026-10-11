package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class PackingItemId(var listId: String = "", var itemId: String = "") : Serializable

/** One item in a packing list. Travels inside its list; replaced on each list push. */
@Entity
@Table(name = "mirror_packing_item")
@IdClass(PackingItemId::class)
class MirrorPackingItem(
    @Id var listId: String = "",
    @Id var itemId: String = "",
    var userId: String = "",
    var packed: Boolean = false,
    var updatedAt: Instant = Instant.now(),
)
