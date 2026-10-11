package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OutfitPieceId(var outfitId: String = "", var itemId: String = "") : Serializable

/** One item on an outfit. Travels inside its outfit; all pieces are replaced on each outfit push. */
@Entity
@Table(name = "mirror_outfit_piece")
@IdClass(OutfitPieceId::class)
class MirrorOutfitPiece(
    @Id var outfitId: String = "",
    @Id var itemId: String = "",
    var userId: String = "",
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var z: Int = 0,
    var updatedAt: Instant = Instant.now(),
)
