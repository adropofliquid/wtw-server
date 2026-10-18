package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.OldDatabase
import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.domain.*
import com.adropofliquid.wtw.dto.TableStats
import com.adropofliquid.wtw.dto.UserCounts
import com.adropofliquid.wtw.repository.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.exitProcess

/** Options of one `--data-copy` run (brief 7.1). */
data class DataCopyOptions(
    val apply: Boolean,
    val only: Set<String>,
    val users: Set<String>?,
    val skipImages: Boolean,
    val imageConcurrency: Int,
) {
    fun has(step: String) = step in only

    companion object {
        val STEPS = listOf("profiles", "items", "outfits", "pieces", "planned", "tokens", "sessions", "suggestions")

        fun parse(args: ApplicationArguments): DataCopyOptions {
            val mode = args.getOptionValues("data-copy")?.firstOrNull()
            require(mode == "dry-run" || mode == "apply") { "--data-copy must be dry-run or apply" }
            val only = args.getOptionValues("only")?.flatMap { it.split(",") }?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: STEPS.toSet()
            require(only.all { it in STEPS }) { "--only takes: ${STEPS.joinToString(",")}" }
            val users = args.getOptionValues("users")?.flatMap { it.split(",") }?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            val conc = args.getOptionValues("image-concurrency")?.firstOrNull()?.toIntOrNull() ?: 16
            return DataCopyOptions(mode == "apply", only, users, args.containsOption("skip-images"), conc.coerceIn(1, 64))
        }
    }
}

/** Acts only when `--data-copy=…` is on the command line; runs the copy, then exits 0 (ok) or 1 (errors or failed checks). */
@Component
class DataCopyRunner(private val ctx: ApplicationContext, private val service: DataCopyService) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("data-copy")) return
        val code = try {
            service.run(DataCopyOptions.parse(args))
        } catch (e: Exception) {
            log.error("data copy failed: {}", e.message, e)
            1
        }
        exitProcess(SpringApplication.exit(ctx, ExitCodeGenerator { code }))
    }
}

/**
 * One-off old → new data copy (brief 7). Reads the old DB (read-only), writes the new DB through JPA, one
 * transaction per user in uid order, last-writer-wins on `updated_at` (a newer row from a phone is never
 * overwritten). Dry-run does every read, transform and check and writes nothing. Re-runs write 0 rows.
 */
@Service
class DataCopyService(
    private val props: WtwProperties,
    private val oldDb: ObjectProvider<OldDatabase>,
    private val storage: ObjectStorage,
    private val imageProcessor: ImageProcessor,
    txManager: PlatformTransactionManager,
    private val profiles: UserProfileRepository,
    private val items: MirrorItemRepository,
    private val outfits: MirrorOutfitRepository,
    private val pieces: MirrorOutfitPieceRepository,
    private val planned: MirrorPlannedRepository,
    private val tokens: PushTokenRepository,
    private val sessions: AiSessionRepository,
    private val suggestions: AiSuggestionRepository,
    private val ledger: LedgerEntryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val writeTx = TransactionTemplate(txManager)
    private val readTx = TransactionTemplate(txManager).apply { isReadOnly = true }
    private val json: JsonMapper = JsonMapper.builder().build()

    @PersistenceContext
    private lateinit var em: EntityManager

    private enum class Dec { INSERT, UPDATE, UNCHANGED, NEWER }

    private fun lww(existing: Instant?, src: Instant) = when {
        existing == null -> Dec.INSERT
        existing < src -> Dec.UPDATE
        existing == src -> Dec.UNCHANGED
        else -> Dec.NEWER
    }

    fun run(opts: DataCopyOptions): Int {
        val started = Instant.now()
        val old = oldDb.getIfAvailable() ?: error("OLD_DB_URL is required for --data-copy")
        if (opts.apply) {
            val same = JdbcUrlsMatch(props.old.dbUrl, props.databaseUrl)
            check(!same) { "OLD_DB_URL and DATABASE_URL point at the same database; refusing to apply" }
        }
        val reader = DataCopyOldReader(old)
        val tLoad = System.currentTimeMillis()
        val prep = Prep(reader.loadAll(), opts.users)
        val loadMs = System.currentTimeMillis() - tLoad
        log.info("data copy: mode={} only={} users={} loaded old data in {} ms",
            if (opts.apply) "apply" else "dry-run", opts.only.joinToString(","), prep.users.size, loadMs)

        val imagesOn = !opts.skipImages && props.old.s3AccessKey.isNotBlank() && props.old.s3Secret.isNotBlank() && opts.has("items")
        val images = if (imagesOn) DataCopyImages(props, storage, imageProcessor, opts.imageConcurrency) else null
        val report = Report(opts, imagesOn)
        prep.countGlobal(report)

        val tUsers = System.currentTimeMillis()
        prep.users.forEachIndexed { n, uid ->
            val uc = UserCounts(uid)
            try {
                copyUser(uid, prep, reader, images, opts, report, uc)
            } catch (e: Exception) {
                uc.error = e.javaClass.simpleName
                report.errors += mapOf("user" to uidHash(uid), "error" to e.javaClass.name, "cause" to rootCause(e).javaClass.name)
                log.warn("data copy: user {} failed: {} (cause {})", uidHash(uid), e.javaClass.simpleName, rootCause(e).javaClass.simpleName)
            }
            report.users += uc
            if ((n + 1) % 250 == 0) log.info("data copy: {}/{} users", n + 1, prep.users.size)
        }
        images?.close()
        val usersMs = System.currentTimeMillis() - tUsers

        report.ledgerEntries = ledger.count()
        if (opts.apply) report.newTableCounts = linkedMapOf(
            "user_profile" to profiles.count(), "mirror_item" to items.count(), "mirror_outfit" to outfits.count(),
            "mirror_outfit_piece" to pieces.count(), "mirror_planned" to planned.count(), "push_token" to tokens.count(),
            "ai_session" to sessions.count(), "ai_suggestion" to suggestions.count(),
        )
        report.timings = linkedMapOf("loadOldMs" to loadMs, "usersMs" to usersMs, "totalMs" to (Instant.now().toEpochMilli() - started.toEpochMilli()))
        return report.write(started)
    }

    private fun rootCause(e: Throwable): Throwable = generateSequence(e) { it.cause }.last()

    // ---------------------------------------------------------------------------------------------------------
    // One user. Images first (outside the DB transaction), then every table in one transaction.

    private fun copyUser(
        uid: String, prep: Prep, reader: DataCopyOldReader, images: DataCopyImages?, opts: DataCopyOptions,
        report: Report, uc: UserCounts,
    ) {
        val imageResults = if (images != null) copyImages(uid, prep, images, opts) else emptyMap()
        val tx = if (opts.apply) writeTx else readTx
        val delta = HashMap<String, Long>() // per table: inserted − deleted for this user (dry-run projection)
        tx.executeWithoutResult {
            val now = Instant.now()
            if (opts.has("profiles")) copyProfile(uid, prep, opts, report)
            if (opts.has("items")) copyItems(uid, prep, imageResults, opts, report, uc, delta, now)
            val blocked = blockedOutfits(uid, prep)
            if (opts.has("outfits")) copyOutfits(uid, prep, opts, report, uc, delta, now)
            if (opts.has("pieces")) copyPieces(uid, prep, blocked, opts, report, uc, delta)
            if (opts.has("planned")) copyPlanned(uid, prep, opts, report, uc, delta, now)
            if (opts.has("tokens")) copyTokens(uid, prep, opts, report)
            if (opts.has("sessions")) copySessions(uid, prep, reader, opts, report, uc, delta, now)
            if (opts.has("suggestions")) copySuggestions(uid, prep, opts, report, uc, delta)
        }
        em.clear()
        // New counts: real counts after apply; in dry-run, today's count + what would be inserted − deleted.
        fun count(t: String, c: () -> Long) { if (t in uc.old) uc.actual[t] = c() + if (opts.apply) 0 else (delta[t] ?: 0) }
        count("items") { items.countByUserId(uid) }
        count("outfits") { outfits.countByUserId(uid) }
        count("pieces") { pieces.countByUserId(uid) }
        count("planned") { planned.countByUserId(uid) }
        count("sessions") { sessions.countByUserId(uid) }
        count("suggestions") { suggestions.countByUserId(uid) }
        uc.pullRows = listOf("items", "outfits", "pieces", "planned", "suggestions").sumOf { uc.actual[it] ?: 0 }
    }

    private fun copyImages(uid: String, prep: Prep, images: DataCopyImages, opts: DataCopyOptions): Map<String, ImageResult> {
        val own = prep.itemsByUser[uid].orEmpty().filter { it.ownUpload }
        if (own.isEmpty()) return emptyMap()
        val existing = readTx.execute { items.findAllById(own.map { it.id }).associateBy { it.id } }!!
        em.clear()
        val work = LinkedHashMap<String, OldImage?>()
        for (i in own) {
            val ex = existing[i.id]
            val upd = i.updatedAt ?: i.createdAt ?: Instant.EPOCH
            val needs = ex == null || (ex.userId == uid && ex.cutoutKey == null && ex.updatedAt <= upd)
            if (needs) work[i.id] = prep.imagesById[i.id]
        }
        return images.run(uid, work, opts.apply).associateBy { it.itemId }
    }

    private fun copyProfile(uid: String, prep: Prep, opts: DataCopyOptions, report: Report) {
        val src = prep.profiles[uid]
        val existing = profiles.findById(uid).orElse(null)
        val lastNotified = prep.lastNotified[uid]
        if (src == null) {
            val st = report.table("profile_stubs")
            st.read++; st.oldCount++
            if (existing != null) { st.unchanged++; return }
            st.inserted++
            val t = prep.firstSeen[uid] ?: Instant.now()
            if (opts.apply) em.persist(UserProfile(id = uid, lastNotifiedAt = lastNotified, createdAt = t, updatedAt = t))
            return
        }
        val st = report.table("profiles")
        st.read++
        val updated = src.updatedAt ?: src.createdAt ?: Instant.EPOCH
        val desired = UserProfile(
            id = uid, displayName = src.displayName, style = src.style,
            vibes = Vocab.vibes(DataCopyTransforms.splitList(src.vibes)), events = Vocab.occasions(DataCopyTransforms.splitList(src.event)),
            timezone = src.timezone, lastNotifiedAt = lastNotified,
            createdAt = src.createdAt ?: updated, updatedAt = updated,
        )
        // A stub the auth filter made (no field set) is filled even if its time is newer: it holds no user data.
        val dec = if (existing != null && existing.isStub && !desired.isStub) Dec.UPDATE else lww(existing?.updatedAt, updated)
        when (dec) {
            Dec.INSERT -> { st.inserted++; if (opts.apply) em.persist(desired) }
            Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
            Dec.UNCHANGED -> st.unchanged++
            Dec.NEWER -> st.skip("newer_on_server")
        }
    }

    private fun copyItems(
        uid: String, prep: Prep, imageResults: Map<String, ImageResult>, opts: DataCopyOptions, report: Report,
        uc: UserCounts, delta: MutableMap<String, Long>, now: Instant,
    ) {
        val src = prep.itemsByUser[uid].orEmpty()
        uc.old["items"] = src.size.toLong()
        if (src.isEmpty()) return
        val st = report.table("items")
        val existing = items.findAllById(src.map { it.id }).associateBy { it.id }
        for (i in src) {
            st.read++
            val ex = existing[i.id]
            val res = imageResults[i.id]
            if (i.ownUpload) report.recordImage(uid, i, res, ex)
            if (ex != null && ex.userId != uid) { st.skip("owned_by_other_user"); uc.skip("items"); continue }
            val updated = i.updatedAt ?: i.createdAt ?: Instant.EPOCH
            val newKey = res?.takeIf { it.outcome == ImageOutcome.COPIED || it.outcome == ImageOutcome.ALREADY_PRESENT || it.outcome == ImageOutcome.WOULD_COPY }?.key
            val category = DataCopyTransforms.category(i.type)
            val desired = MirrorItem(
                id = i.id, userId = uid, createdAt = i.createdAt ?: updated, chosen = "CUTOUT", reviewed = category != null,
                category = category, attributes = DataCopyTransforms.attributes(i), archived = false,
                catalogId = if (i.ownUpload) null else i.imageMetadataId,
                cutoutKey = ex?.cutoutKey ?: newKey, generatedKey = ex?.generatedKey,
                cutoutVersion = ex?.cutoutVersion, generatedVersion = ex?.generatedVersion,
                updatedAt = updated, deletedAt = null, syncedAt = now,
            )
            report.pullBytes(uc, desired, extra = if (desired.cutoutKey != null || desired.catalogId != null) IMAGE_URL_BYTES else 0)
            when (lww(ex?.updatedAt, updated)) {
                Dec.INSERT -> { st.inserted++; delta.inc("items"); if (opts.apply) em.persist(desired) }
                Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
                Dec.UNCHANGED -> if (ex!!.cutoutKey == null && newKey != null) {
                    // Only the image is new (e.g. an earlier run had --skip-images): set the key, bump synced_at.
                    st.updated++; report.cutoutKeySetOnExisting++
                    if (opts.apply) { ex.cutoutKey = newKey; ex.syncedAt = now }
                } else st.unchanged++
                Dec.NEWER -> { st.skip("newer_on_server"); uc.phoneNewer += "items" }
            }
        }
    }

    /** Outfits whose new row is newer (a phone pushed it) or owned by someone else: their pieces are left alone. */
    private fun blockedOutfits(uid: String, prep: Prep): Set<String> {
        val src = prep.outfitsByUser[uid].orEmpty()
        if (src.isEmpty()) return emptySet()
        return outfits.findAllById(src.map { it.id }).filter { ex ->
            val o = prep.outfitsById[ex.id]!!
            ex.userId != uid || ex.updatedAt > (o.updatedAt ?: o.createdAt ?: Instant.EPOCH)
        }.map { it.id }.toSet()
    }

    private fun copyOutfits(
        uid: String, prep: Prep, opts: DataCopyOptions, report: Report, uc: UserCounts, delta: MutableMap<String, Long>, now: Instant,
    ) {
        val src = prep.outfitsByUser[uid].orEmpty()
        uc.old["outfits"] = src.size.toLong()
        if (src.isEmpty()) return
        val st = report.table("outfits")
        val existing = outfits.findAllById(src.map { it.id }).associateBy { it.id }
        for (o in src) {
            st.read++
            val ex = existing[o.id]
            if (ex != null && ex.userId != uid) { st.skip("owned_by_other_user"); uc.skip("outfits"); continue }
            val updated = o.updatedAt ?: o.createdAt ?: Instant.EPOCH
            val desired = MirrorOutfit(
                id = o.id, userId = uid, name = o.name ?: "", createdByAi = o.createdByAi ?: false, occasion = o.occasion,
                note = o.outfitReview, layout = "canvas", createdAt = o.createdAt ?: updated, updatedAt = updated, syncedAt = now,
            )
            report.pullBytes(uc, desired)
            when (lww(ex?.updatedAt, updated)) {
                Dec.INSERT -> { st.inserted++; delta.inc("outfits"); if (opts.apply) em.persist(desired) }
                Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
                Dec.UNCHANGED -> st.unchanged++
                Dec.NEWER -> { st.skip("newer_on_server"); uc.phoneNewer += "outfits" }
            }
        }
    }

    private fun copyPieces(
        uid: String, prep: Prep, blocked: Set<String>, opts: DataCopyOptions, report: Report, uc: UserCounts,
        delta: MutableMap<String, Long>,
    ) {
        val userOutfits = prep.outfitsByUser[uid].orEmpty()
        val st = report.table("pieces")
        var old = 0L
        val existingByOutfit = if (userOutfits.isEmpty()) emptyMap()
        else pieces.findByOutfitIdIn(userOutfits.map { it.id }).groupBy { it.outfitId }
        for (o in userOutfits) {
            val rows = prep.piecesByOutfit[o.id].orEmpty()
            old += rows.size
            st.read += rows.size
            // Drop pieces whose item was not copied, then keep the last row per item (physical order).
            val live = rows.filter { it.itemId != null && it.itemId in prep.copiedItemIds }
            st.skip("missing_item", (rows.size - live.size).toLong())
            val kept = live.associateBy { it.itemId!! }.values.sortedBy { it.seq }
            st.skip("duplicate_pair", (live.size - kept.size).toLong())
            uc.skip("pieces", (rows.size - kept.size).toLong())
            val updated = o.updatedAt ?: o.createdAt ?: Instant.EPOCH
            if (o.id in blocked) { st.skip("newer_on_server", kept.size.toLong()); uc.phoneNewer += "pieces"; continue }
            val exMap = existingByOutfit[o.id].orEmpty().associateBy { it.itemId }
            kept.forEachIndexed { z, p ->
                val pl = DataCopyTransforms.placement(p.position)
                val desired = MirrorOutfitPiece(o.id, p.itemId!!, uid, pl.x, pl.y, pl.scale, pl.rotation, z, updated)
                report.pullBytes(uc, desired)
                when (lww(exMap[p.itemId]?.updatedAt, updated)) {
                    Dec.INSERT -> { st.inserted++; delta.inc("pieces"); if (opts.apply) em.persist(desired) }
                    Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
                    Dec.UNCHANGED -> st.unchanged++
                    Dec.NEWER -> { st.skip("newer_on_server"); uc.phoneNewer += "pieces" }
                }
            }
            // Pieces in the new DB that the old outfit no longer has (only possible after an earlier copy) go.
            val keptIds = kept.map { it.itemId }.toSet()
            exMap.values.filter { it.itemId !in keptIds && it.updatedAt <= updated }.forEach {
                st.deleted++; delta.inc("pieces", -1); if (opts.apply) em.remove(it)
            }
        }
        uc.old["pieces"] = old
    }

    private fun copyPlanned(
        uid: String, prep: Prep, opts: DataCopyOptions, report: Report, uc: UserCounts, delta: MutableMap<String, Long>, now: Instant,
    ) {
        val src = prep.plannedByUser[uid].orEmpty()
        uc.old["planned"] = src.size.toLong()
        if (src.isEmpty()) return
        val st = report.table("planned")
        val tz = prep.profiles[uid]?.timezone
        val valid = ArrayList<Pair<LocalDate, OldPlanned>>()
        for (p in src) {
            st.read++
            when {
                p.outfitId == null || p.outfitId !in prep.outfitIds -> { st.skip("missing_outfit"); uc.skip("planned") }
                p.plannedDate == null -> { st.skip("no_date"); uc.skip("planned") }
                else -> valid += DataCopyTransforms.localDate(p.plannedDate, tz) to p
            }
        }
        val existing = planned.findByUserId(uid).associateBy { it.date }
        for ((date, group) in valid.groupBy({ it.first }, { it.second })) {
            // Same (user, date) more than once: keep the latest created_at (then the later row).
            val win = group.maxWith(compareBy<OldPlanned>({ it.createdAt ?: Instant.EPOCH }, { it.seq }))
            st.skip("duplicate_date", (group.size - 1).toLong()); uc.skip("planned", (group.size - 1).toLong())
            val created = win.createdAt ?: win.plannedDate!!.toInstant(ZoneOffset.UTC)
            val desired = MirrorPlanned(uid, date, win.outfitId, created, created, null, now)
            report.pullBytes(uc, desired)
            when (lww(existing[date]?.updatedAt, created)) {
                Dec.INSERT -> { st.inserted++; delta.inc("planned"); if (opts.apply) em.persist(desired) }
                Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
                Dec.UNCHANGED -> st.unchanged++
                Dec.NEWER -> { st.skip("newer_on_server"); uc.phoneNewer += "planned" }
            }
        }
    }

    private fun copyTokens(uid: String, prep: Prep, opts: DataCopyOptions, report: Report) {
        val src = prep.tokensByUser[uid].orEmpty()
        if (src.isEmpty()) return
        val st = report.table("tokens")
        val existing = tokens.findByFcmTokenIn(src.map { it.fcmToken!! }).associateBy { it.fcmToken }
        for (t in src) {
            st.read++
            val updated = t.updatedAt ?: t.createdAt ?: Instant.EPOCH
            val ex = existing[t.fcmToken]
            val dec = lww(ex?.updatedAt, updated)
            var id = ex?.id ?: runCatching { UUID.fromString(t.id) }.getOrNull() ?: UUID.nameUUIDFromBytes(t.id.toByteArray())
            if (dec == Dec.INSERT && tokens.existsById(id)) id = UUID.nameUUIDFromBytes(t.fcmToken!!.toByteArray())
            val desired = PushToken(id, uid, t.fcmToken!!, (t.platform ?: "").trim().lowercase(), t.createdAt ?: updated, updated)
            when (dec) {
                Dec.INSERT -> { st.inserted++; if (opts.apply) em.persist(desired) }
                Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
                Dec.UNCHANGED -> st.unchanged++
                Dec.NEWER -> st.skip("newer_on_server")
            }
        }
    }

    private fun copySessions(
        uid: String, prep: Prep, reader: DataCopyOldReader, opts: DataCopyOptions, report: Report, uc: UserCounts,
        delta: MutableMap<String, Long>, now: Instant,
    ) {
        val src = prep.sessionsByUser[uid].orEmpty()
        uc.old["sessions"] = src.size.toLong()
        if (src.isEmpty()) return
        val st = report.table("sessions")
        val existing = sessions.findAllById(src.map { it.id }).associateBy { it.id }
        val decided = src.map { s ->
            val ex = existing[s.id]
            val updated = s.updatedAt ?: s.lastActivityAt ?: s.createdAt ?: Instant.EPOCH
            Triple(s, updated, if (ex != null && ex.userId != uid) null else lww(ex?.updatedAt, updated))
        }
        // The transcripts are big: read them only when a row is going to be written.
        val conv = if (decided.any { it.third == Dec.INSERT || it.third == Dec.UPDATE }) reader.conversations(uid) else emptyMap()
        for ((s, updated, dec) in decided) {
            st.read++
            if (dec == null) { st.skip("owned_by_other_user"); uc.skip("sessions"); continue }
            when (dec) {
                Dec.UNCHANGED -> { st.unchanged++; continue }
                Dec.NEWER -> { st.skip("newer_on_server"); uc.phoneNewer += "sessions"; continue }
                else -> {}
            }
            val started = s.startedAt ?: s.createdAt ?: updated
            val lastActivity = s.lastActivityAt ?: updated
            var status = s.status?.uppercase()?.takeIf { it in setOf("ACTIVE", "COMPLETED", "ABANDONED") } ?: "ACTIVE"
            if (status == "ACTIVE" && lastActivity.isBefore(now.minusSeconds(24 * 3600))) { status = "ABANDONED"; report.sessionsAbandoned++ }
            val text = conv[s.id]
            val history: Any? = when {
                text.isNullOrBlank() -> null
                else -> DataCopyTransforms.parseJson(text) ?: mapOf("raw" to text).also { report.conversationsWrappedRaw++ }
            }
            val desired = AiSession(
                id = s.id, userId = uid,
                context = s.context?.uppercase()?.takeIf { it in setOf("DAILY", "PLANNER", "WARDROBE") } ?: "DAILY",
                targetDate = s.targetDate, dayType = s.originalEvent?.trim()?.ifBlank { null }?.let(Vocab::occasion),
                vibes = Vocab.vibes(DataCopyTransforms.jsonStringList(s.originalVibes) ?: DataCopyTransforms.splitList(s.originalVibes)),
                note = s.originalMoreInfo,
                wardrobeSnapshot = DataCopyTransforms.jsonStringList(s.wardrobeSnapshotIds)?.map { mapOf("id" to it) },
                status = status, initiator = s.initiator ?: "USER_INITIATED", attemptNumber = s.attemptNumber ?: 0,
                tooWarm = s.tooWarm ?: false, tooCold = s.tooCold ?: false, tooCasual = s.tooCasual ?: false,
                tooFormal = s.tooFormal ?: false, differentTop = s.differentTop ?: false, differentBottom = s.differentBottom ?: false,
                rejectedSuggestionIds = DataCopyTransforms.jsonStringList(s.rejectedSuggestionIds) ?: emptyList(),
                acceptedSuggestionId = s.acceptedSuggestionId, conversationHistory = history,
                startedAt = started, lastActivityAt = lastActivity, createdAt = s.createdAt ?: started, updatedAt = updated,
            )
            if (dec == Dec.INSERT) { st.inserted++; delta.inc("sessions"); if (opts.apply) em.persist(desired) }
            else { st.updated++; if (opts.apply) em.merge(desired) }
        }
    }

    private fun copySuggestions(
        uid: String, prep: Prep, opts: DataCopyOptions, report: Report, uc: UserCounts, delta: MutableMap<String, Long>,
    ) {
        val src = prep.suggestionsByUser[uid].orEmpty()
        uc.old["suggestions"] = src.size.toLong()
        if (src.isEmpty()) return
        val st = report.table("suggestions")
        val existing = suggestions.findAllById(src.map { it.id }).associateBy { it.id }
        for (s in src) {
            st.read++
            val ex = existing[s.id]
            if (ex != null && ex.userId != uid) { st.skip("owned_by_other_user"); uc.skip("suggestions"); continue }
            val updated = s.updatedAt ?: s.createdAt ?: Instant.EPOCH
            val desired = AiSuggestion(
                id = s.id, userId = uid, sessionId = s.sessionId?.takeIf { it in prep.sessionIds },
                kind = if (s.sessionId != null) "session" else "legacy_batch", attemptNumber = s.attemptNumber ?: 1,
                name = s.name, itemIds = prep.suggestionItems[s.id].orEmpty(), reasoning = s.outfitReview,
                occasion = s.occasion, dayType = s.event?.trim()?.ifBlank { null }?.let(Vocab::occasion), vibes = Vocab.vibes(DataCopyTransforms.splitList(s.vibes)), note = s.moreInfo,
                accepted = s.favorited ?: false, acceptedAt = null, rejected = s.wasRejected ?: false,
                rejectionReason = s.rejectionReason?.trim()?.uppercase()?.ifEmpty { null }, feedbackText = s.feedbackText,
                createdAt = s.createdAt ?: updated, updatedAt = updated,
            )
            report.pullBytes(uc, desired)
            when (lww(ex?.updatedAt, updated)) {
                Dec.INSERT -> { st.inserted++; delta.inc("suggestions"); if (opts.apply) em.persist(desired) }
                Dec.UPDATE -> { st.updated++; if (opts.apply) em.merge(desired) }
                Dec.UNCHANGED -> st.unchanged++
                Dec.NEWER -> { st.skip("newer_on_server"); uc.phoneNewer += "suggestions" }
            }
        }
    }

    private fun MutableMap<String, Long>.inc(t: String, n: Long = 1) { merge(t, n, Long::plus) }
    private fun UserCounts.skip(t: String, n: Long = 1) { if (n > 0) skipped.merge(t, n, Long::plus) }

    // ---------------------------------------------------------------------------------------------------------
    // Old data, indexed once.

    private class Prep(d: OldData, only: Set<String>?) {
        val profiles = d.profiles.associateBy { it.id }
        val imagesById = d.images.associateBy { it.id }
        val copiedItemIds = d.items.filter { it.userId != null }.map { it.id }.toHashSet()
        val outfitIds = d.outfits.map { it.id }.toHashSet()
        val outfitsById = d.outfits.associateBy { it.id }
        val sessionIds = d.sessions.map { it.id }.toHashSet()
        val suggestionIds = d.suggestions.map { it.id }.toHashSet()

        val itemsByUser = d.items.filter { it.userId != null }.groupBy { it.userId!! }
        val outfitsByUser = d.outfits.filter { it.userId != null }.groupBy { it.userId!! }
        val plannedByUser = d.planned.filter { it.userId != null }.groupBy { it.userId!! }
        val sessionsByUser = d.sessions.filter { it.userId != null }.groupBy { it.userId!! }
        val suggestionsByUser = d.suggestions.filter { it.userId != null }.groupBy { it.userId!! }

        /** outfit_item rows of real outfits, by outfit, in physical order. */
        val piecesByOutfit = d.outfitItems.filter { it.outfitId != null && it.outfitId in outfitIds }.groupBy { it.outfitId!! }
        /** Item ids of each suggestion (7.6): its outfit_item rows in physical order, de-duplicated, deleted items kept. */
        val suggestionItems: Map<String, List<String>> = d.outfitItems
            .filter { it.outfitId != null && it.outfitId in suggestionIds && it.itemId != null }
            .groupBy({ it.outfitId!! }, { it.itemId!! }).mapValues { (_, v) -> v.distinct() }

        /** Push tokens: drop empty ones, then one row per token (newest created_at wins), across all users. */
        val tokenEmpty = d.tokens.filter { it.userId != null && it.fcmToken.isNullOrBlank() }
        private val tokenGroups = d.tokens.filter { it.userId != null && !it.fcmToken.isNullOrBlank() }.groupBy { it.fcmToken!! }
        val tokenWinners = tokenGroups.values.map { g -> g.maxWith(compareBy<OldToken>({ it.createdAt ?: Instant.EPOCH }, { it.seq })) }
        private val winnerSet = tokenWinners.toSet()
        val tokenLosers = tokenGroups.values.flatMap { g -> g.filter { it !in winnerSet } }
        val tokensByUser = tokenWinners.groupBy { it.userId!! }
        val lastNotified: Map<String, Instant> = d.tokens.filter { it.userId != null && it.lastNotifiedAt != null }
            .groupBy { it.userId!! }.mapValues { (_, v) -> v.maxOf { it.lastNotifiedAt!! } }

        val users: List<String> = (profiles.keys + itemsByUser.keys + outfitsByUser.keys + plannedByUser.keys +
            d.tokens.mapNotNull { it.userId } + sessionsByUser.keys + suggestionsByUser.keys)
            .filter { it.isNotBlank() && it != "onboarding-user" }
            .filter { only == null || it in only }
            .toSortedSet().toList()

        /** Earliest time seen for a user: the stub profile's created_at. */
        val firstSeen: Map<String, Instant> = buildMap {
            fun see(u: String?, t: Instant?) { if (u != null && t != null) merge(u, t) { a, b -> minOf(a, b) } }
            d.items.forEach { see(it.userId, it.createdAt) }; d.outfits.forEach { see(it.userId, it.createdAt ?: it.updatedAt) }
            d.planned.forEach { see(it.userId, it.createdAt) }; d.tokens.forEach { see(it.userId, it.createdAt) }
            d.sessions.forEach { see(it.userId, it.createdAt) }; d.suggestions.forEach { see(it.userId, it.createdAt) }
        }

        private val selected = users.toHashSet()
        private val all = only == null
        private val data = d

        /** Old counts and the skips decided before any user is processed (null user, empty/duplicate token). */
        fun countGlobal(r: Report) {
            fun sel(u: String?) = u != null && u in selected
            r.table("profiles").oldCount = data.profiles.count { sel(it.id) }.toLong()
            r.table("items").apply {
                oldCount = data.items.count { sel(it.userId) || (all && it.userId == null) }.toLong()
                if (all) { val n = data.items.count { it.userId == null }.toLong(); read += n; skip("null_user", n) }
            }
            r.table("outfits").apply {
                oldCount = data.outfits.count { sel(it.userId) || (all && it.userId == null) }.toLong()
                if (all) { val n = data.outfits.count { it.userId == null }.toLong(); read += n; skip("null_user", n) }
            }
            r.table("pieces").oldCount = piecesByOutfit.entries.filter { sel(outfitsById[it.key]?.userId) }.sumOf { it.value.size }.toLong()
            r.table("planned").apply {
                oldCount = data.planned.count { sel(it.userId) || (all && it.userId == null) }.toLong()
                if (all) { val n = data.planned.count { it.userId == null }.toLong(); read += n; skip("null_user", n) }
            }
            r.table("tokens").apply {
                oldCount = data.tokens.count { sel(it.userId) || (all && it.userId == null) }.toLong()
                val nNull = if (all) data.tokens.count { it.userId == null }.toLong() else 0
                val nEmpty = tokenEmpty.count { sel(it.userId) }.toLong()
                val nDup = tokenLosers.count { sel(it.userId) }.toLong()
                read += nNull + nEmpty + nDup
                skip("null_user", nNull); skip("empty_token", nEmpty); skip("duplicate_token", nDup)
            }
            r.table("sessions").apply {
                oldCount = data.sessions.count { sel(it.userId) || (all && it.userId == null) }.toLong()
                if (all) { val n = data.sessions.count { it.userId == null }.toLong(); read += n; skip("null_user", n) }
            }
            r.table("suggestions").apply {
                oldCount = data.suggestions.count { sel(it.userId) || (all && it.userId == null) }.toLong()
                if (all) { val n = data.suggestions.count { it.userId == null }.toLong(); read += n; skip("null_user", n) }
            }
            // outfit_item feeds two tables (7.4 and 7.6): say where every row went.
            val oi = data.outfitItems
            r.outfitItemSplit = linkedMapOf(
                "total" to oi.size.toLong(),
                "outfitRows" to oi.count { it.outfitId in outfitIds }.toLong(),
                "suggestionOnlyRows" to oi.count { it.outfitId !in outfitIds && it.outfitId in suggestionIds }.toLong(),
                "orphanRows" to oi.count { it.outfitId !in outfitIds && it.outfitId !in suggestionIds }.toLong(),
                "rowsInSuggestionItemIds" to oi.count { it.outfitId in suggestionIds }.toLong(),
                "imageMetadataRowsNotLinkedToAnyItem" to data.images.count { it.id !in linkedImageIds }.toLong(),
            )
        }

        private val linkedImageIds = d.items.mapNotNull { it.imageMetadataId }.toHashSet()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Report (brief 7.1, 7.9).

    private inner class Report(val opts: DataCopyOptions, val imagesOn: Boolean) {
        val tables = linkedMapOf<String, TableStats>()
        val users = ArrayList<UserCounts>()
        val errors = ArrayList<Map<String, String>>()
        val missing = ArrayList<List<String>>()
        val imageOutcomes = sortedMapOf<String, Long>()
        var ownUploads = 0L
        var ownUploadsWithKey = 0L
        var ownUploadsPhoneNewer = 0L
        var cutoutKeySetOnExisting = 0L
        var sessionsAbandoned = 0L
        var conversationsWrappedRaw = 0L
        var ledgerEntries = 0L
        var newTableCounts: Map<String, Long>? = null
        var timings: Map<String, Long> = emptyMap()
        var outfitItemSplit: Map<String, Long> = emptyMap()

        init { DataCopyOptions.STEPS.forEach { tables[it] = TableStats() }; tables["profile_stubs"] = TableStats() }

        fun table(t: String) = tables.getValue(t)

        fun pullBytes(uc: UserCounts, row: Any, extra: Int = 0) {
            uc.pullBytes += runCatching { json.writeValueAsBytes(row).size }.getOrDefault(300) + extra
        }

        fun recordImage(uid: String, i: OldItem, res: ImageResult?, ex: MirrorItem?) {
            ownUploads++
            if (!imagesOn) return
            if (res == null) {
                // Not attempted: the new row already has a key, or a phone owns a newer row.
                if (ex?.cutoutKey != null) { ownUploadsWithKey++; imageOutcomes.merge("key_already_set", 1, Long::plus) }
                else { ownUploadsPhoneNewer++; imageOutcomes.merge("left_to_phone_newer_row", 1, Long::plus) }
                return
            }
            imageOutcomes.merge(res.outcome.name.lowercase(), 1, Long::plus)
            when (res.outcome) {
                ImageOutcome.COPIED, ImageOutcome.ALREADY_PRESENT, ImageOutcome.WOULD_COPY -> ownUploadsWithKey++
                else -> missing += listOf(uid, i.id, res.oldKey ?: "", res.bucketTried ?: "", res.outcome.name.lowercase())
            }
        }

        /** Writes the three files and the console summary. Returns the exit code. */
        fun write(started: Instant): Int {
            val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(started)
            val tablesOk = tables.values.all { it.read == it.inserted + it.updated + it.unchanged + it.skippedTotal && it.oldCount == it.read }
            val mismatched = users.filter { u -> u.old.keys.any { !u.matches(it) } }
            val largestRows = users.maxByOrNull { it.pullRows }
            val largestBytes = users.maxByOrNull { it.pullBytes }
            val imagesSection: Any = if (!imagesOn) "not checked" else linkedMapOf(
                "ownUploads" to ownUploads, "withCutoutKey" to ownUploadsWithKey, "inMissingCsv" to missing.size.toLong(),
                "leftToPhone" to ownUploadsPhoneNewer, "outcomes" to imageOutcomes,
                "everyOwnUploadAccountedFor" to (ownUploads == ownUploadsWithKey + missing.size + ownUploadsPhoneNewer),
            )
            val written = tables.values.sumOf { it.written }
            val report = linkedMapOf(
                "mode" to if (opts.apply) "apply" else "dry-run",
                "startedAt" to started.toString(), "finishedAt" to Instant.now().toString(), "timingsMs" to timings,
                "only" to opts.only.toList(), "usersFilter" to (opts.users?.size ?: "all"), "usersProcessed" to users.size,
                "tables" to tables.mapValues { it.value.toMap() },
                "outfitItemSplit" to outfitItemSplit,
                "images" to imagesSection,
                "cutoutKeySetOnExistingRows" to cutoutKeySetOnExisting,
                "sessionsMarkedAbandoned" to sessionsAbandoned,
                "conversationsWrappedRaw" to conversationsWrappedRaw,
                "checks" to linkedMapOf(
                    "globalCountsReconcile" to tablesOk,
                    "usersWithCountMismatch" to mismatched.size,
                    "usersWithErrors" to errors.size,
                    "ledgerEntries" to ledgerEntries, "ledgerEmpty" to (ledgerEntries == 0L),
                    "largestPullByRows" to largestRows?.let { mapOf("user" to uidHash(it.uid), "rows" to it.pullRows, "bytesEstimate" to it.pullBytes) },
                    "largestPullByBytes" to largestBytes?.let { mapOf("user" to uidHash(it.uid), "rows" to it.pullRows, "bytesEstimate" to it.pullBytes) },
                    "pullOver5MB" to users.count { it.pullBytes > 5L * 1024 * 1024 },
                    "rowsWritten" to written,
                    "rerun" to if (opts.apply) "Run --data-copy=apply (or dry-run) again: it must report rowsWritten 0 and no copied images."
                    else "Dry-run: rowsWritten is what apply would write. After an apply, a dry-run must show 0.",
                ),
                "manualChecks" to listOf(
                    "HEAD 200 random cutout keys in the new user bucket",
                    "Restore spot check: GET /mirror/pull?since=0 for 20 random users + the 5 largest closets vs old /wardrobe, /outfit, /planner",
                    "Canvas transform: compare 10 outfits, old Flutter canvas vs KMP canvas after restore",
                ),
                "errors" to errors,
            )
            reportFile("data-copy-report-$ts.json").writeBytes(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsBytes(report))
            writeUsersCsv(reportFile("data-copy-users-$ts.csv"))
            reportFile("data-copy-missing-images.csv").printWriter().use { w ->
                w.println("user_id,item_id,old_key,buckets_tried,reason")
                missing.forEach { r -> w.println(r.joinToString(",") { csv(it) }) }
            }
            console(ts, tablesOk, mismatched.size, largestRows, largestBytes, written)
            return if (errors.isEmpty() && tablesOk && (!opts.apply || mismatched.isEmpty())) 0 else 1
        }

        /** Report files go to DATA_COPY_REPORT_DIR (default: working dir). On Dokku point it at a mounted folder. */
        private fun reportFile(name: String): java.io.File {
            val dir = java.io.File(System.getenv("DATA_COPY_REPORT_DIR") ?: ".").apply { mkdirs() }
            return java.io.File(dir, name)
        }

        private fun writeUsersCsv(file: java.io.File) {
            val ts = listOf("items", "outfits", "pieces", "planned", "sessions", "suggestions")
            file.printWriter().use { w ->
                w.println((listOf("user_id") + ts.flatMap { listOf("${it}_old", "${it}_skipped", "${it}_expected", "${it}_new") } +
                    listOf("phone_newer", "pull_rows", "pull_bytes_est", "ok", "error")).joinToString(","))
                for (u in users) {
                    val cells = ts.flatMap { t ->
                        if (t !in u.old) listOf("", "", "", "")
                        else listOf(u.old[t], u.skipped[t] ?: 0, u.expected(t), u.actual[t] ?: "").map { it.toString() }
                    }
                    val ok = u.error == null && u.old.keys.all { u.matches(it) }
                    w.println((listOf(csv(u.uid)) + cells + listOf(u.phoneNewer.sorted().joinToString("|"), "${u.pullRows}",
                        "${u.pullBytes}", "$ok", u.error ?: "")).joinToString(","))
                }
            }
        }

        private fun console(ts: String, tablesOk: Boolean, mismatched: Int, rows: UserCounts?, bytes: UserCounts?, written: Long) {
            val verb = if (opts.apply) "written" else "would write"
            val sb = StringBuilder("\n=== data copy (${if (opts.apply) "apply" else "dry-run"}) ===\n")
            for ((t, s) in tables) if (s.read > 0 || s.oldCount > 0)
                sb.append(String.format("%-14s old %6d  read %6d  %s +%d ~%d -%d  unchanged %d  skipped %s%n",
                    t, s.oldCount, s.read, verb, s.inserted, s.updated, s.deleted, s.unchanged, s.skipped))
            sb.append("outfit_item split: $outfitItemSplit\n")
            sb.append("images: ${if (imagesOn) "$imageOutcomes (missing csv rows: ${missing.size})" else "not checked"}\n")
            sb.append("sessions ACTIVE>24h → ABANDONED: $sessionsAbandoned; transcripts kept as {raw}: $conversationsWrappedRaw\n")
            sb.append("global counts reconcile: $tablesOk; users with count mismatch: $mismatched; users with errors: ${errors.size}\n")
            sb.append("largest pull: rows ${rows?.pullRows} (user ${rows?.let { uidHash(it.uid) }}), bytes≈${bytes?.pullBytes} (user ${bytes?.let { uidHash(it.uid) }})\n")
            sb.append("ledger entries: $ledgerEntries; rows $verb: $written; timings ms: $timings\n")
            sb.append("files in ${reportFile("x").absoluteFile.parent ?: "."}: data-copy-report-$ts.json, data-copy-users-$ts.csv, data-copy-missing-images.csv\n")
            log.info(sb.toString())
        }

        private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private companion object {
        /** Rough size of an image link in the pull (cutoutUrl). */
        const val IMAGE_URL_BYTES = 130

        fun JdbcUrlsMatch(a: String, b: String): Boolean = runCatching {
            com.adropofliquid.wtw.config.JdbcUrl.fromDatabaseUrl(a).url.substringBefore('?') ==
                com.adropofliquid.wtw.config.JdbcUrl.fromDatabaseUrl(b).url.substringBefore('?')
        }.getOrDefault(false)
    }
}
