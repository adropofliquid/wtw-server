package com.adropofliquid.wtw.service

import org.slf4j.LoggerFactory
import com.adropofliquid.wtw.config.msSince
import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.domain.*
import com.adropofliquid.wtw.dto.*
import com.adropofliquid.wtw.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Route 9, GET /mirror/pull?since={ms} (brief 4.2).
 * - since=0 (restore): live rows only, no tombstones.
 * - since>0: every row with synced_at > since, tombstones included (suggestions: updated_at > since).
 * `serverTime` is read before the queries, so a write that lands during the pull is picked up next time.
 */
@Service
class MirrorPullService(
    private val items: MirrorItemRepository,
    private val outfits: MirrorOutfitRepository,
    private val pieces: MirrorOutfitPieceRepository,
    private val planned: MirrorPlannedRepository,
    private val wear: MirrorWearRepository,
    private val lists: MirrorPackingListRepository,
    private val packItems: MirrorPackingItemRepository,
    private val suggestions: AiSuggestionRepository,
    private val sessions: AiSessionRepository,
    private val library: LibraryItemRepository,
    private val storage: ObjectStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun pull(uid: String, since: Long): MirrorPullResponse {
        if (since < 0) throw ApiException.validation("since must be 0 or more")
        val start = System.nanoTime()
        val serverTime = Instant.now()
        val restore = since == 0L
        val after = Instant.ofEpochMilli(since)

        val itemRows = if (restore) items.findByUserIdAndDeletedAtIsNull(uid) else items.findByUserIdAndSyncedAtAfter(uid, after)
        val outfitRows = if (restore) outfits.findByUserIdAndDeletedAtIsNull(uid) else outfits.findByUserIdAndSyncedAtAfter(uid, after)
        val plannedRows = if (restore) planned.findByUserIdAndDeletedAtIsNull(uid) else planned.findByUserIdAndSyncedAtAfter(uid, after)
        val wearRows = if (restore) wear.findByUserIdAndDeletedAtIsNull(uid) else wear.findByUserIdAndSyncedAtAfter(uid, after)
        val listRows = if (restore) lists.findByUserIdAndDeletedAtIsNull(uid) else lists.findByUserIdAndSyncedAtAfter(uid, after)
        val suggestionRows = suggestions.findByUserIdAndUpdatedAtAfter(uid, after)

        val piecesByOutfit = chunked(outfitRows.map { it.id }) { pieces.findByOutfitIdIn(it) }.groupBy { it.outfitId }
        val itemsByList = chunked(listRows.map { it.id }) { packItems.findByListIdIn(it) }.groupBy { it.listId }
        val sessionById = chunked(suggestionRows.mapNotNull { it.sessionId }.distinct()) { sessions.findAllById(it) }.associateBy { it.id }
        // Catalog items with no own cut-out show the library image; only ids that exist in the library.
        val catalogIds = itemRows.filter { it.cutoutKey == null && it.deletedAt == null }.mapNotNull { it.catalogId }.distinct()
        val inLibrary = chunked(catalogIds) { library.findAllById(it) }.map { it.id }.toSet()
        val queryMs = msSince(start)

        return MirrorPullResponse(
            serverTime = serverTime.toEpochMilli(),
            items = itemRows.map { it.toDto(inLibrary) },
            outfits = outfitRows.map { it.toDto(piecesByOutfit[it.id].orEmpty()) },
            planned = plannedRows.map { PlannedDto(it.date.toString(), it.outfitId, it.createdAt.ms(), it.updatedAt.ms(), it.deletedAt?.ms()) },
            wear = wearRows.map { WearDto(it.date.toString(), it.status, it.outfitId, it.itemIds, it.createdAt.ms(), it.updatedAt.ms(), it.deletedAt?.ms()) },
            packingLists = listRows.map { it.toDto(itemsByList[it.id].orEmpty()) },
            suggestions = suggestionRows.map { it.toDto(it.sessionId?.let(sessionById::get)) },
        ).also {
            log.info("mirror pull restore={} items={} outfits={} planned={} wear={} lists={} suggestions={} queryMs={} buildMs={}",
                restore, it.items.size, it.outfits.size, it.planned.size, it.wear.size, it.packingLists.size, it.suggestions.size,
                queryMs, msSince(start) - queryMs)
        }
    }

    private fun MirrorItem.toDto(inLibrary: Set<String>): ItemDto {
        val live = deletedAt == null
        val cutoutUrl = when {
            !live -> null
            cutoutKey != null -> storage.presignedUrl(Bucket.USER, cutoutKey!!)
            catalogId != null && catalogId in inLibrary -> storage.presignedUrl(Bucket.LIBRARY, Keys.library(catalogId!!))
            else -> null
        }
        return ItemDto(
            id = id, createdAt = createdAt.ms(), updatedAt = updatedAt.ms(), deletedAt = deletedAt?.ms(),
            chosen = chosen, reviewed = reviewed, category = category, attributes = attributes,
            price = price, currency = currency, purchaseDate = purchaseDate?.toString(), brand = brand,
            archived = archived, catalogId = catalogId, source = source, poolConsent = poolConsent, poolFilter = poolFilter,
            cutoutUrl = cutoutUrl,
            generatedUrl = if (live) generatedKey?.let { storage.presignedUrl(Bucket.USER, it) } else null,
        )
    }

    private fun MirrorOutfit.toDto(ps: List<MirrorOutfitPiece>) = OutfitDto(
        id = id, name = name, createdByAi = createdByAi, createdAt = createdAt.ms(), updatedAt = updatedAt.ms(),
        deletedAt = deletedAt?.ms(), occasion = occasion, note = note, layout = layout,
        pieces = ps.sortedBy { it.z }.map { PieceDto(it.itemId, it.x, it.y, it.scale, it.rotation, it.z) },
    )

    private fun MirrorPackingList.toDto(its: List<MirrorPackingItem>) = PackingListDto(
        id = id, name = name, startDate = startDate?.toString(), endDate = endDate?.toString(),
        createdAt = createdAt.ms(), updatedAt = updatedAt.ms(), deletedAt = deletedAt?.ms(),
        items = its.map { PackingItemDto(it.itemId, it.packed) },
    )

    private fun AiSuggestion.toDto(s: AiSession?) = SuggestionDto(
        id = id, sessionId = sessionId, kind = kind, createdAt = createdAt.ms(), updatedAt = updatedAt.ms(),
        context = s?.context, date = s?.targetDate?.toString(), name = name, itemIds = itemIds,
        reasoning = reasoning, occasion = occasion, accepted = accepted, rejected = rejected,
        rejectionReason = rejectionReason, feedback = feedbackText,
    )

    private fun Instant.ms() = toEpochMilli()

    /** Runs an IN-list query in chunks so a very large user stays under the driver's parameter limit. */
    private fun <T> chunked(ids: List<String>, query: (List<String>) -> Iterable<T>): List<T> =
        ids.chunked(5000).flatMap { query(it) }
}
