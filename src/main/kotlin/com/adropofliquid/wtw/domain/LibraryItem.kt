package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One Library entry: a starter (42, UUID ids) or a generated catalog item (540, slug ids). */
@Entity
@Table(name = "library_item")
class LibraryItem(
    @Id var id: String = "",
    var source: String = "generated",
    var name: String = "",
    var fileName: String? = null,
    var category: String = "",
    var description: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") var attributes: Map<String, Any?>? = null,
    var brand: String? = null,
    var model: String? = null,
    var gender: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var keywords: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var vibes: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") var styles: List<String> = emptyList(),
    var imageKey: String = "",
    var active: Boolean = true,
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
