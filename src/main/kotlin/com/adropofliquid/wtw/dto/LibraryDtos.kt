package com.adropofliquid.wtw.dto

/** GET /library (brief 4.3, route 11). The KMP `CatalogItem` plus source, brand, keywords and imageUrl. */
data class LibraryItemResponse(
    val id: String,
    /** "starter" or "generated". */
    val source: String,
    val name: String,
    val category: String,
    val description: String,
    val vibes: List<String>,
    val styles: List<String>,
    val brand: String?,
    val keywords: List<String>,
    val imageUrl: String,
    /** Superset attributes object; null for starters. */
    val attributes: Map<String, Any?>?,
)

/** Compat GET /user/onboarding/starter-items (route 12). Same shape as the old server's StarterItem. */
data class StarterItemResponse(
    val id: String,
    /** The old PNG file name, e.g. "white_crewneck_tshirt.png". */
    val name: String,
    val description: String,
    val type: String,
    val vibe: List<String>,
    val style: List<String>,
)
