package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.config.msSince
import com.adropofliquid.wtw.domain.*
import com.adropofliquid.wtw.dto.*
import com.adropofliquid.wtw.repository.*
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Route 8, POST /mirror/push (brief 4.2). Batch upserts and tombstones, last-writer-wins on `updatedAt`.
 * One transaction for the whole request, rows in the order items → outfits → planned → wear → packingLists.
 * There are no foreign keys (ruling 9.1), so ownership and "planned outfit exists" are checked here.
 * Images of tombstoned items are deleted from storage after the transaction commits.
 */
@Service
class MirrorPushService(
    private val items: MirrorItemRepository,
    private val outfits: MirrorOutfitRepository,
    private val pieces: MirrorOutfitPieceRepository,
    private val planned: MirrorPlannedRepository,
    private val wear: MirrorWearRepository,
    private val lists: MirrorPackingListRepository,
    private val packItems: MirrorPackingItemRepository,
    private val storage: ObjectStorage,
    private val props: WtwProperties,
    private val em: EntityManager,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    fun push(uid: String, req: MirrorPushRequest): MirrorPushResponse {
        val max = props.mirror.maxRowsPerPush
        if (req.rowCount() > max) throw ApiException.tooLarge("More than $max rows in one push")
        val now = Instant.now()
        val run = Run(uid, now)
        val start = System.nanoTime()
        val results = tx.execute { run.all(req) }!!
        val txMs = msSince(start)
        // Storage is not transactional: delete only once the tombstones are committed.
        run.keysToDelete.forEach { key ->
            runCatching { storage.delete(Bucket.USER, key) }
                .onFailure { log.warn("upstream=storage delete failed uid={} err={}", uidHash(uid), it.javaClass.simpleName) }
        }
        fun RowResults.s() = "$upserted/$stale/${rejected.size}"
        log.info("mirror push rows={} items={} outfits={} planned={} wear={} lists={} (upserted/stale/rejected) imageDeletes={} txMs={} deleteMs={}",
            req.rowCount(), results.items.s(), results.outfits.s(), results.planned.s(), results.wear.s(), results.packingLists.s(),
            run.keysToDelete.size, txMs, msSince(start) - txMs)
        return MirrorPushResponse(now.toEpochMilli(), results)
    }

    private class Tally {
        var upserted = 0
        var stale = 0
        val rejected = mutableListOf<RejectedRow>()
        fun reject(id: String?, reason: String) { rejected += RejectedRow(id ?: "", reason) }
        fun result() = RowResults(upserted, stale, rejected)
    }

    /** One push request. Holds the rows already seen so later stages (and repeats of one id) see them. */
    private inner class Run(val uid: String, val now: Instant) {
        val keysToDelete = linkedSetOf<String>()
        /** Outfits loaded or written in this request, by id (used by the planned check). */
        val outfitById = HashMap<String, MirrorOutfit>()

        fun all(req: MirrorPushRequest) = PushResults(
            items = pushItems(req.items.orEmpty()),
            outfits = pushOutfits(req.outfits.orEmpty()),
            planned = pushPlanned(req.planned.orEmpty()),
            wear = pushWear(req.wear.orEmpty()),
            packingLists = pushLists(req.packingLists.orEmpty()),
        )

        // ---- items ---------------------------------------------------------------------------

        fun pushItems(rows: List<ItemRow?>): RowResults {
            val t = Tally()
            val existing = HashMap(items.findAllById(rows.mapNotNull { it?.id }.filter(::validId).distinct()).associateBy { it.id })
            for (row in rows) {
                val id = row?.id
                if (row == null || !validId(id)) { t.reject(id, INVALID); continue }
                id!!
                val cur = existing[id]
                if (cur != null && cur.userId != uid) { t.reject(id, NOT_OWNER); continue }
                if (!validItem(row)) { t.reject(id, INVALID); continue }
                val updatedAt = row.updatedAt!!
                if (cur != null && cur.updatedAt.toEpochMilli() > updatedAt) { t.stale++; continue }
                val e = cur ?: MirrorItem(id = id, userId = uid)
                if (cur == null) e.createdAt = ms(row.createdAt ?: updatedAt)
                if (row.deletedAt != null) {
                    e.deletedAt = ms(row.deletedAt)
                    listOfNotNull(e.cutoutKey, e.generatedKey, e.originalKey, Keys.itemImage(uid, id, CUTOUT), Keys.itemImage(uid, id, GENERATED))
                        .forEach { keysToDelete += it }
                    e.cutoutKey = null
                    e.generatedKey = null
                    e.cutoutVersion = null
                    e.generatedVersion = null
                    e.originalKey = null
                    e.originalVersion = null
                } else {
                    e.deletedAt = null
                    row.createdAt?.let { e.createdAt = ms(it) }
                    e.chosen = row.chosen ?: "CUTOUT"
                    e.reviewed = row.reviewed ?: false
                    e.category = row.category
                    e.attributes = row.attributes
                    e.price = row.price
                    e.currency = row.currency?.uppercase()
                    e.purchaseDate = row.purchaseDate?.let(LocalDate::parse)
                    e.brand = row.brand
                    e.archived = row.archived ?: false
                    e.catalogId = row.catalogId
                    e.source = row.source
                    e.poolConsent = row.poolConsent ?: false
                    e.poolFilter = row.poolFilter
                    // A live row written after a tombstone of the same id: its object keys were cleared; the app re-uploads.
                }
                e.updatedAt = ms(updatedAt)
                e.syncedAt = now
                if (cur == null) { em.persist(e); existing[id] = e }
                t.upserted++
            }
            return t.result()
        }

        fun validItem(r: ItemRow): Boolean {
            if (r.updatedAt == null || !times(r.createdAt, r.updatedAt, r.deletedAt)) return false
            if (r.deletedAt != null) return true // other fields may be left out
            return (r.chosen == null || r.chosen in CHOSEN) &&
                (r.category == null || r.category in CATEGORIES) &&
                (r.purchaseDate == null || isoDate(r.purchaseDate) != null) &&
                (r.currency == null || r.currency.length == 3) &&
                (r.price == null || r.price.abs() < MAX_PRICE) &&
                (r.catalogId == null || r.catalogId.length <= 128) &&
                (r.source == null || r.source.length <= 16) &&
                (r.poolFilter == null || r.poolFilter.length <= 16)
        }

        // ---- outfits (pieces travel inside) ----------------------------------------------------

        fun pushOutfits(rows: List<OutfitRow?>): RowResults {
            val t = Tally()
            val ids = rows.mapNotNull { it?.id }.filter(::validId).distinct()
            outfits.findAllById(ids).forEach { outfitById[it.id] = it }
            val piecesByOutfit = HashMap<String, MutableList<MirrorOutfitPiece>>()
            if (ids.isNotEmpty()) pieces.findByOutfitIdIn(ids).forEach { piecesByOutfit.getOrPut(it.outfitId) { mutableListOf() } += it }
            for (row in rows) {
                val id = row?.id
                if (row == null || !validId(id)) { t.reject(id, INVALID); continue }
                id!!
                val cur = outfitById[id]
                if (cur != null && cur.userId != uid) { t.reject(id, NOT_OWNER); continue }
                if (!validOutfit(row)) { t.reject(id, INVALID); continue }
                val updatedAt = row.updatedAt!!
                if (cur != null && cur.updatedAt.toEpochMilli() > updatedAt) { t.stale++; continue }
                val e = cur ?: MirrorOutfit(id = id, userId = uid)
                if (cur == null) e.createdAt = ms(row.createdAt ?: updatedAt)
                val wanted: List<PieceRow>
                if (row.deletedAt != null) {
                    e.deletedAt = ms(row.deletedAt)
                    wanted = emptyList() // an outfit tombstone deletes its pieces
                } else {
                    e.deletedAt = null
                    row.createdAt?.let { e.createdAt = ms(it) }
                    e.name = row.name ?: ""
                    e.createdByAi = row.createdByAi ?: false
                    e.occasion = row.occasion
                    e.note = row.note
                    e.layout = row.layout
                    e.archived = row.archived ?: false
                    wanted = row.pieces.orEmpty().filterNotNull()
                }
                e.updatedAt = ms(updatedAt)
                e.syncedAt = now
                if (cur == null) { em.persist(e); outfitById[id] = e }
                piecesByOutfit[id] = replacePieces(id, piecesByOutfit[id].orEmpty(), wanted, e.updatedAt)
                t.upserted++
            }
            return t.result()
        }

        fun validOutfit(r: OutfitRow): Boolean {
            if (r.updatedAt == null || !times(r.createdAt, r.updatedAt, r.deletedAt)) return false
            if (r.deletedAt != null) return true
            if (r.layout != null && r.layout !in LAYOUTS) return false
            val ps = r.pieces.orEmpty()
            if (ps.any { it == null || !validId(it.itemId) }) return false
            return ps.map { it!!.itemId }.toSet().size == ps.size // one item once per outfit (the KMP key)
        }

        /** Makes the outfit's pieces equal [wanted]: update in place, remove the rest, add the new ones. */
        fun replacePieces(outfitId: String, old: List<MirrorOutfitPiece>, wanted: List<PieceRow>, at: Instant): MutableList<MirrorOutfitPiece> {
            val oldByItem = old.associateBy { it.itemId }
            val keep = wanted.map { it.itemId!! }.toSet()
            val gone = old.filter { it.itemId !in keep }
            gone.forEach { em.remove(it) }
            // Hibernate flushes inserts before deletes; flush now so a re-added (outfit, item) key cannot clash.
            if (gone.isNotEmpty()) em.flush()
            return wanted.map { p ->
                val e = oldByItem[p.itemId] ?: MirrorOutfitPiece(outfitId = outfitId, itemId = p.itemId!!, userId = uid).also { em.persist(it) }
                e.x = p.x ?: 0.5f
                e.y = p.y ?: 0.5f
                e.scale = p.scale ?: 1f
                e.rotation = p.rotation ?: 0f
                e.z = p.z ?: 0
                e.updatedAt = at
                e
            }.toMutableList()
        }

        // ---- planned (key = user + date) -------------------------------------------------------

        fun pushPlanned(rows: List<PlannedRow?>): RowResults {
            val t = Tally()
            val dates = rows.mapNotNull { isoDate(it?.date) }.distinct()
            val existing = HashMap<LocalDate, MirrorPlanned>()
            if (dates.isNotEmpty()) planned.findByUserIdAndDateIn(uid, dates).forEach { existing[it.date] = it }
            // Outfits named by live rows that this request did not touch: load them in one query.
            val missing = rows.filter { it?.deletedAt == null }.mapNotNull { it?.outfitId }.filter { validId(it) && it !in outfitById }.distinct()
            if (missing.isNotEmpty()) outfits.findAllById(missing).forEach { outfitById[it.id] = it }
            for (row in rows) {
                val key = row?.date
                val date = isoDate(key)
                if (row == null || date == null || row.updatedAt == null || !times(row.createdAt, row.updatedAt, row.deletedAt)) {
                    t.reject(key, INVALID); continue
                }
                if (row.deletedAt == null) {
                    if (!validId(row.outfitId)) { t.reject(key, INVALID); continue }
                    val o = outfitById[row.outfitId]
                    if (o == null || o.userId != uid || o.deletedAt != null) { t.reject(key, MISSING_OUTFIT); continue }
                } else if (row.outfitId != null && row.outfitId.length > 64) { t.reject(key, INVALID); continue }
                val cur = existing[date]
                if (cur != null && cur.updatedAt.toEpochMilli() > row.updatedAt) { t.stale++; continue }
                val e = cur ?: MirrorPlanned(userId = uid, date = date)
                if (cur == null) e.createdAt = ms(row.createdAt ?: row.updatedAt)
                row.createdAt?.let { e.createdAt = ms(it) }
                if (row.deletedAt != null) {
                    e.deletedAt = ms(row.deletedAt)
                    row.outfitId?.let { e.outfitId = it }
                } else {
                    e.deletedAt = null
                    e.outfitId = row.outfitId
                }
                e.updatedAt = ms(row.updatedAt)
                e.syncedAt = now
                if (cur == null) { em.persist(e); existing[date] = e }
                t.upserted++
            }
            return t.result()
        }

        // ---- wear (key = user + date; no outfit check on purpose) ------------------------------

        fun pushWear(rows: List<WearRow?>): RowResults {
            val t = Tally()
            val dates = rows.mapNotNull { isoDate(it?.date) }.distinct()
            val existing = HashMap<LocalDate, MirrorWear>()
            if (dates.isNotEmpty()) wear.findByUserIdAndDateIn(uid, dates).forEach { existing[it.date] = it }
            for (row in rows) {
                val key = row?.date
                val date = isoDate(key)
                if (row == null || date == null || row.updatedAt == null || !times(row.createdAt, row.updatedAt, row.deletedAt)) {
                    t.reject(key, INVALID); continue
                }
                if (row.deletedAt == null && row.status !in WEAR_STATUS) { t.reject(key, INVALID); continue }
                if (row.outfitId != null && row.outfitId.length > 64) { t.reject(key, INVALID); continue }
                val cur = existing[date]
                if (cur != null && cur.updatedAt.toEpochMilli() > row.updatedAt) { t.stale++; continue }
                val e = cur ?: MirrorWear(userId = uid, date = date)
                if (cur == null) e.createdAt = ms(row.createdAt ?: row.updatedAt)
                row.createdAt?.let { e.createdAt = ms(it) }
                if (row.deletedAt != null) {
                    e.deletedAt = ms(row.deletedAt)
                } else {
                    e.deletedAt = null
                    e.status = row.status
                    e.outfitId = row.outfitId
                    e.itemIds = row.itemIds.orEmpty().filterNotNull()
                }
                e.updatedAt = ms(row.updatedAt)
                e.syncedAt = now
                if (cur == null) { em.persist(e); existing[date] = e }
                t.upserted++
            }
            return t.result()
        }

        // ---- packing lists (items travel inside) -----------------------------------------------

        fun pushLists(rows: List<PackingListRow?>): RowResults {
            val t = Tally()
            val ids = rows.mapNotNull { it?.id }.filter(::validId).distinct()
            val existing = HashMap(lists.findAllById(ids).associateBy { it.id })
            val itemsByList = HashMap<String, MutableList<MirrorPackingItem>>()
            if (ids.isNotEmpty()) packItems.findByListIdIn(ids).forEach { itemsByList.getOrPut(it.listId) { mutableListOf() } += it }
            for (row in rows) {
                val id = row?.id
                if (row == null || !validId(id)) { t.reject(id, INVALID); continue }
                id!!
                val cur = existing[id]
                if (cur != null && cur.userId != uid) { t.reject(id, NOT_OWNER); continue }
                if (!validList(row)) { t.reject(id, INVALID); continue }
                val updatedAt = row.updatedAt!!
                if (cur != null && cur.updatedAt.toEpochMilli() > updatedAt) { t.stale++; continue }
                val e = cur ?: MirrorPackingList(id = id, userId = uid)
                if (cur == null) e.createdAt = ms(row.createdAt ?: updatedAt)
                val wanted: List<PackingItemRow>
                if (row.deletedAt != null) {
                    e.deletedAt = ms(row.deletedAt)
                    wanted = emptyList() // the list's items die with it
                } else {
                    e.deletedAt = null
                    row.createdAt?.let { e.createdAt = ms(it) }
                    e.name = row.name!!
                    e.startDate = row.startDate?.let(LocalDate::parse)
                    e.endDate = row.endDate?.let(LocalDate::parse)
                    wanted = row.items.orEmpty().filterNotNull()
                }
                e.updatedAt = ms(updatedAt)
                e.syncedAt = now
                if (cur == null) { em.persist(e); existing[id] = e }
                itemsByList[id] = replacePackItems(id, itemsByList[id].orEmpty(), wanted, e.updatedAt)
                t.upserted++
            }
            return t.result()
        }

        fun validList(r: PackingListRow): Boolean {
            if (r.updatedAt == null || !times(r.createdAt, r.updatedAt, r.deletedAt)) return false
            if (r.deletedAt != null) return true
            if (r.name == null) return false
            if (r.startDate != null && isoDate(r.startDate) == null) return false
            if (r.endDate != null && isoDate(r.endDate) == null) return false
            val its = r.items.orEmpty()
            if (its.any { it == null || !validId(it.itemId) }) return false
            return its.map { it!!.itemId }.toSet().size == its.size
        }

        fun replacePackItems(listId: String, old: List<MirrorPackingItem>, wanted: List<PackingItemRow>, at: Instant): MutableList<MirrorPackingItem> {
            val oldByItem = old.associateBy { it.itemId }
            val keep = wanted.map { it.itemId!! }.toSet()
            val gone = old.filter { it.itemId !in keep }
            gone.forEach { em.remove(it) }
            if (gone.isNotEmpty()) em.flush()
            return wanted.map { p ->
                val e = oldByItem[p.itemId] ?: MirrorPackingItem(listId = listId, itemId = p.itemId!!, userId = uid).also { em.persist(it) }
                e.packed = p.packed ?: false
                e.updatedAt = at
                e
            }.toMutableList()
        }
    }

    companion object {
        const val NOT_OWNER = "NOT_OWNER"
        const val INVALID = "INVALID"
        const val MISSING_OUTFIT = "MISSING_OUTFIT"
        const val CUTOUT = "cutout"
        const val GENERATED = "generated"
        const val ORIGINAL = "original"
        val CHOSEN = setOf("ORIGINAL", "CUTOUT", "GENERATED")
        val CATEGORIES = setOf("tops", "bottoms", "outerwear", "shoes", "accessories")
        val WEAR_STATUS = setOf("WORN", "NOT_WORN")
        val LAYOUTS = setOf("slots", "canvas")
        /** numeric(12,2) holds up to 10 digits before the point. */
        val MAX_PRICE = BigDecimal("10000000000")

        fun validId(id: String?) = !id.isNullOrBlank() && id.length <= 64
        /** Epoch ms that Postgres can store (year 1970 to 9999). */
        fun times(vararg t: Long?) = t.all { it == null || it in 0..253_402_300_799_999 }
        fun ms(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)
        fun isoDate(s: String?): LocalDate? = s?.let { try { LocalDate.parse(it) } catch (_: DateTimeParseException) { null } }
    }
}
