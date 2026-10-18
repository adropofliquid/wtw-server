package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Latest pushed state of one device item. Attributes are the superset object as sent (ruling 9.2). */
@Entity
@Table(name = "mirror_item")
class MirrorItem(
    @Id var id: String = "",
    var userId: String = "",
    var createdAt: Instant = Instant.now(),
    var chosen: String = "CUTOUT",
    var reviewed: Boolean = false,
    var category: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var attributes: Map<String, Any?>? = null,
    @Column(precision = 12, scale = 2) var price: BigDecimal? = null,
    var currency: String? = null,
    var purchaseDate: LocalDate? = null,
    var brand: String? = null,
    var archived: Boolean = false,
    var catalogId: String? = null,
    var cutoutKey: String? = null,
    var generatedKey: String? = null,
    /** Changes when the image does; part of its stable URL (ImageUrls). Null = "0". */
    var cutoutVersion: String? = null,
    var generatedVersion: String? = null,
    /** The original photo (user-raw/ prefix), and its version. */
    var originalKey: String? = null,
    var originalVersion: String? = null,
    var source: String? = null,
    var poolConsent: Boolean = false,
    var poolFilter: String? = null,
    var updatedAt: Instant = Instant.now(),
    var deletedAt: Instant? = null,
    var syncedAt: Instant = Instant.now(),
)
