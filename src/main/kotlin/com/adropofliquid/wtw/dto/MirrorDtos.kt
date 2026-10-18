package com.adropofliquid.wtw.dto

import java.math.BigDecimal

// ---- Route 8: POST /mirror/push (brief 4.2) ---------------------------------------------------
// Every field is nullable so one bad row is rejected on its own (INVALID) instead of failing the whole body.
// Times are epoch milliseconds; dates are ISO yyyy-MM-dd strings.

data class MirrorPushRequest(
    val items: List<ItemRow?>? = null,
    val outfits: List<OutfitRow?>? = null,
    val planned: List<PlannedRow?>? = null,
    val wear: List<WearRow?>? = null,
    val packingLists: List<PackingListRow?>? = null,
) {
    fun rowCount() = (items?.size ?: 0) + (outfits?.size ?: 0) + (planned?.size ?: 0) + (wear?.size ?: 0) + (packingLists?.size ?: 0)
}

data class ItemRow(
    val id: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
    val chosen: String? = null,
    val reviewed: Boolean? = null,
    val category: String? = null,
    /** The superset attributes object, stored whole (unknown keys kept; ruling 9.2). */
    val attributes: Map<String, Any?>? = null,
    val price: BigDecimal? = null,
    val currency: String? = null,
    val purchaseDate: String? = null,
    val brand: String? = null,
    val archived: Boolean? = null,
    val catalogId: String? = null,
    val source: String? = null,
    val poolConsent: Boolean? = null,
    val poolFilter: String? = null,
)

data class PieceRow(
    val itemId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val scale: Float? = null,
    val rotation: Float? = null,
    val z: Int? = null,
)

data class OutfitRow(
    val id: String? = null,
    val name: String? = null,
    val createdByAi: Boolean? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
    val occasion: String? = null,
    val note: String? = null,
    val layout: String? = null,
    val pieces: List<PieceRow?>? = null,
)

data class PlannedRow(
    val date: String? = null,
    val outfitId: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
)

data class WearRow(
    val date: String? = null,
    val status: String? = null,
    val outfitId: String? = null,
    val itemIds: List<String?>? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
)

data class PackingItemRow(val itemId: String? = null, val packed: Boolean? = null)

data class PackingListRow(
    val id: String? = null,
    val name: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
    val items: List<PackingItemRow?>? = null,
)

data class RejectedRow(val id: String, val reason: String)

data class RowResults(val upserted: Int, val stale: Int, val rejected: List<RejectedRow>)

data class PushResults(
    val items: RowResults,
    val outfits: RowResults,
    val planned: RowResults,
    val wear: RowResults,
    val packingLists: RowResults,
)

data class MirrorPushResponse(val serverTime: Long, val results: PushResults)

// ---- Route 9: GET /mirror/pull?since={ms} (brief 4.2) -----------------------------------------

data class MirrorPullResponse(
    val serverTime: Long,
    val items: List<ItemDto>,
    val outfits: List<OutfitDto>,
    val planned: List<PlannedDto>,
    val wear: List<WearDto>,
    val packingLists: List<PackingListDto>,
    val suggestions: List<SuggestionDto>,
)

data class ItemDto(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val chosen: String,
    val reviewed: Boolean,
    val category: String?,
    val attributes: Map<String, Any?>?,
    val price: BigDecimal?,
    val currency: String?,
    val purchaseDate: String?,
    val brand: String?,
    val archived: Boolean,
    val catalogId: String?,
    val source: String?,
    val poolConsent: Boolean,
    val poolFilter: String?,
    /** Stable API link to the cut-out, or the Library image link for a catalog item with no own cut-out. */
    val cutoutUrl: String?,
    val generatedUrl: String?,
)

data class PieceDto(val itemId: String, val x: Float, val y: Float, val scale: Float, val rotation: Float, val z: Int)

data class OutfitDto(
    val id: String,
    val name: String,
    val createdByAi: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val occasion: String?,
    val note: String?,
    val layout: String?,
    val pieces: List<PieceDto>,
)

data class PlannedDto(val date: String, val outfitId: String?, val createdAt: Long, val updatedAt: Long, val deletedAt: Long?)

data class WearDto(
    val date: String,
    val status: String?,
    val outfitId: String?,
    val itemIds: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class PackingItemDto(val itemId: String, val packed: Boolean)

data class PackingListDto(
    val id: String,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val items: List<PackingItemDto>,
)

/** Read-only suggestion history (ai_suggestion joined to its ai_session for context and date). */
data class SuggestionDto(
    val id: String,
    val sessionId: String?,
    val kind: String,
    val createdAt: Long,
    val updatedAt: Long,
    val context: String?,
    val date: String?,
    val name: String?,
    val itemIds: List<String>,
    val reasoning: String?,
    val occasion: String?,
    val accepted: Boolean,
    val rejected: Boolean,
    val rejectionReason: String?,
    val feedback: String?,
)

// ---- Route 10: PUT /mirror/items/{itemId}/images/{kind} ---------------------------------------

data class ItemImageResponse(val key: String, val url: String)
