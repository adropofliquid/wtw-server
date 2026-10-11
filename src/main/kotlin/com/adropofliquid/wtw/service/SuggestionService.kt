package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.gemini.ChatMessage
import com.adropofliquid.wtw.client.gemini.GeminiCallLog
import com.adropofliquid.wtw.client.gemini.GeminiClient
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.domain.AiSession
import com.adropofliquid.wtw.domain.AiSuggestion
import com.adropofliquid.wtw.dto.*
import com.adropofliquid.wtw.repository.AiSessionRepository
import com.adropofliquid.wtw.repository.AiSuggestionRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A response body plus the credit balance for the `X-Credits-Balance` header. */
data class Metered<T>(val balance: Int, val body: T)

/**
 * Suggestion sessions (brief 4.4, routes 17–20). Port of the old SuggestionSessionService + SessionGeminiService:
 * the wardrobe comes from the request (not the mirror) and goes to the model under I1..In aliases; 4 tries on a
 * blank reply; one corrective re-ask when the reply names ids outside the wardrobe, then those ids are dropped.
 *
 * Money: one flat fee per session. start charges cost_outfit_suggestion once (ref = `idem:{Idempotency-Key}`, else the session id) just before
 * the first model turn, and refunds it (own transaction) if that turn fails. An INSUFFICIENT_WARDROBE start makes
 * no model call and charges nothing. next, refine and accept charge nothing; accept's `charged` is always false. Daily generation cap = ai_suggestion rows since the user's local midnight (429 "generations").
 * Gemini is always called outside a DB transaction; rows are written after the reply, all or nothing.
 */
@Service
class SuggestionService(
    private val sessions: AiSessionRepository,
    private val suggestions: AiSuggestionRepository,
    private val gemini: GeminiClient,
    private val prompts: PromptBuilder,
    private val notes: StylistNotesService,
    private val ledger: LedgerService,
    private val entitlements: EntitlementService,
    private val config: AppConfigService,
    private val profiles: UserProfileService,
    private val props: WtwProperties,
    private val json: JsonMapper,
    private val idempotency: IdempotencyService,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    // ---------------------------------------------------------------- routes

    /**
     * [idempotencyKey] (optional header): with a key, the fee's ledger ref is `idem:{key}` instead of the session id,
     * so an app retry after a timeout is never charged twice. A repeat gets the first session back (same rules as
     * routes 15 and 16, see IdempotencyService). No key → a fresh session and a fee every call (as before).
     */
    fun start(uid: String, req: SuggestionRequest, idempotencyKey: String? = null): Metered<SuggestionResponse> {
        val key = idempotency.clean(idempotencyKey)
        val context = req.context?.uppercase()?.takeIf { it in CONTEXTS }
            ?: throw ApiException.validation("context must be one of ${CONTEXTS.joinToString()}")
        val wardrobe = cleanWardrobe(req.wardrobe ?: throw ApiException.validation("wardrobe is required"), "wardrobe")
        val zone = profiles.zoneOf(uid)
        val today = LocalDate.now(zone)
        val requested = req.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiException.validation("date must be yyyy-MM-dd") }
        val targetDate = when (context) {
            "DAILY" -> requested ?: today
            "PLANNER" -> requested ?: today
            else -> requested
        }
        val recentlyWorn = req.recentlyWorn.orEmpty()

        return idempotency.once(uid, "outfit/suggest/start", key) { ref ->
            startCharged(uid, req, context, wardrobe, today, targetDate, recentlyWorn, ref)
        }
    }

    private fun startCharged(
        uid: String, req: SuggestionRequest, context: String, wardrobe: List<WardrobeEntryDto>,
        today: LocalDate, targetDate: LocalDate?, recentlyWorn: Map<String, String>, ref: String?,
    ): Metered<SuggestionResponse> {
        checkGenerationCap(uid)
        val state = ledger.requireBalance(uid, config.ledger().costOutfitSuggestion)

        val now = Instant.now()
        val session = AiSession(
            id = "sess_${UUID.randomUUID()}", userId = uid, context = context, targetDate = targetDate,
            dayType = req.dayType?.trim()?.ifBlank { null }, comfort = req.comfort?.trim()?.ifBlank { null },
            note = req.note?.trim()?.ifBlank { null }?.take(MAX_TEXT), style = req.style?.trim()?.ifBlank { null },
            weather = req.weather?.let { w -> linkedMapOf("date" to w.date, "code" to w.code, "maxC" to w.maxC, "minC" to w.minC, "precipChance" to w.precipChance) },
            wardrobeSnapshot = wardrobe.map { linkedMapOf("id" to it.id, "category" to it.category, "attributes" to it.attributes) },
            recentlyWorn = recentlyWorn,
            status = ACTIVE, attemptNumber = 0, startedAt = now, lastActivityAt = now, createdAt = now, updatedAt = now,
        )

        val missing = missingCategories(wardrobe)
        if (missing.isNotEmpty()) {
            sessions.save(session)
            return Metered(state.balance, SuggestionResponse(
                INSUFFICIENT_WARDROBE, session.id, 0,
                failure = FailureDto("Not enough items in wardrobe to create an outfit", missing),
            ))
        }

        val aliasById = aliases(wardrobe.map { it.id!! })
        val stylistNotes = notes.block(uid, wardrobe, aliasById, session.style)
        val history = listOf(
            ChatMessage("system", prompts.sessionSystem),
            ChatMessage("user", prompts.initialUserMessage(
                wardrobe, aliasById, targetDate, today, req.weather, session.dayType, session.comfort,
                session.style, session.note, recentlyWorn, stylistNotes,
            )),
        )
        // The session fee: its own transaction, committed before the model call (CONTRIBUTING "metered action").
        val charge = ledger.spend(uid, Reason.OUTFIT_SUGGESTION, config.ledger().costOutfitSuggestion, ref = ref ?: session.id)
        if (!charge.charged) throw IdempotencyService.keyUsed()
        val response = try {
            val turn = converse("suggest/start", history, aliasById, wardrobe.map { it.id!! }.toSet())
            session.attemptNumber = 1
            session.conversationHistory = turn.history.map { mapOf("role" to it.role, "content" to it.content) }
            tx.execute { persist(session, turn) }!!
        } catch (e: Exception) {
            ledger.refund(uid, charge)
            throw e
        }
        return Metered(charge.state.balance, response)
    }

    fun next(uid: String, req: NextRequest): Metered<SuggestionResponse> {
        val session = openSession(uid, req.sessionId)
        val rejection = req.rejection?.uppercase()?.also {
            if (it !in REJECTIONS) throw ApiException.validation("rejection must be one of ${REJECTIONS.joinToString()} or null")
        }
        val feedback = req.feedback?.trim()?.ifBlank { null }?.take(MAX_TEXT)
        checkGenerationCap(uid)

        val now = Instant.now()
        val rejected = suggestions.findFirstBySessionIdOrderByCreatedAtDesc(session.id)?.takeIf { !it.rejected && !it.accepted }
        rejected?.apply {
            this.rejected = true
            rejectionReason = rejection
            feedbackText = feedback
            updatedAt = now
        }
        if (rejected != null && rejected.id !in session.rejectedSuggestionIds) session.rejectedSuggestionIds += rejected.id
        session.lastActivityAt = now
        session.updatedAt = now
        session.status = ACTIVE

        val max = config.ledger().maxAttemptsPerSession
        if (session.attemptNumber + 1 > max) {
            tx.execute {
                rejected?.let { suggestions.save(it) }
                sessions.save(session)
            }
            return Metered(ledger.read(uid).balance, SuggestionResponse(
                EXHAUSTED, session.id, session.attemptNumber,
                failure = FailureDto("You've seen many suggestions in this session"),
            ))
        }

        val (aliasById, wardrobeIds) = sessionAliases(session)
        val history = storedHistory(session) + ChatMessage("user", prompts.rejectionMessage(rejection, feedback))
        val turn = converse("suggest/next", history, aliasById, wardrobeIds)

        session.attemptNumber += 1
        session.conversationHistory = turn.history.map { mapOf("role" to it.role, "content" to it.content) }
        val response = tx.execute {
            rejected?.let { suggestions.save(it) }
            persist(session, turn)
        }!!
        return Metered(ledger.read(uid).balance, response)
    }

    fun refine(uid: String, req: RefineRequest): Metered<SuggestionResponse> {
        val session = openSession(uid, req.sessionId)
        val m = req.modifications ?: SuggestionModifications()
        val feedback = req.feedback?.trim()?.ifBlank { null }?.take(MAX_TEXT)
        checkGenerationCap(uid)

        session.tooWarm = m.tooWarm
        session.tooCold = m.tooCold
        session.tooCasual = m.tooCasual
        session.tooFormal = m.tooFormal
        session.differentTop = m.differentTop
        session.differentBottom = m.differentBottom

        val stored = storedHistory(session)
        // The app's "Show me anyway" (after LOW_CONFIDENCE) calls refine: then the model must return an outfit.
        val forceShow = stored.lastOrNull { it.role == "assistant" }?.let { parseJson(it.content)?.get("lowConfidence") == true } ?: false
        val (aliasById, wardrobeIds) = sessionAliases(session)
        val history = stored + ChatMessage("user", prompts.refinementMessage(m, forceShow, feedback))
        val turn = converse("suggest/refine", history, aliasById, wardrobeIds)

        val now = Instant.now()
        session.attemptNumber = 1 // a refinement starts a fresh count (old server)
        session.status = ACTIVE
        session.lastActivityAt = now
        session.updatedAt = now
        session.conversationHistory = turn.history.map { mapOf("role" to it.role, "content" to it.content) }
        val response = tx.execute { persist(session, turn) }!!
        return Metered(ledger.read(uid).balance, response)
    }

    /** Records the accept (idempotent). Charges nothing: the fee was taken at start. `charged` is always false. */
    fun accept(uid: String, req: AcceptRequest): AcceptResponse {
        val sessionId = req.sessionId?.ifBlank { null } ?: throw ApiException.validation("sessionId is required")
        val suggestionId = req.suggestionId?.ifBlank { null } ?: throw ApiException.validation("suggestionId is required")
        tx.execute {
            val session = sessions.findByIdAndUserId(sessionId, uid) ?: throw ApiException.notFound("Session not found")
            val suggestion = suggestions.findByIdAndSessionIdAndUserId(suggestionId, sessionId, uid)
                ?: throw ApiException.notFound("Suggestion not found in this session")
            if (session.status == COMPLETED && session.acceptedSuggestionId != suggestionId) throw sessionClosed()
            val now = Instant.now()
            if (session.status != COMPLETED) {
                session.status = COMPLETED
                session.acceptedSuggestionId = suggestionId
                session.lastActivityAt = now
                session.updatedAt = now
                sessions.save(session)
            }
            if (!suggestion.accepted) {
                suggestion.accepted = true
                suggestion.acceptedAt = now
                suggestion.updatedAt = now
                suggestions.save(suggestion)
            }
        }
        notes.evict(uid)
        return AcceptResponse(charged = false, balance = ledger.read(uid).balance)
    }

    // ---------------------------------------------------------------- model turn

    private sealed interface Parsed
    private data class OutfitReply(val ids: List<String>, val review: String, val occasion: String) : Parsed
    private data class Refusal(val reason: String, val missing: List<String>) : Parsed
    private data class Turn(val parsed: Parsed, val history: List<ChatMessage>)

    /**
     * One model turn: call, parse, translate aliases, validate ids (one corrective re-ask, then strip).
     * Unparseable reply or an outfit with no valid item → 502 UPSTREAM_FAILED (nothing stored; start refunds its fee).
     */
    private fun converse(route: String, history: List<ChatMessage>, aliasById: Map<String, String>, wardrobeIds: Set<String>): Turn {
        val aliasToId = aliasById.entries.associate { (id, alias) -> alias to id }
        var current = history + ChatMessage("assistant", chat(route, history))
        var parsed = parse(current.last().content, aliasToId)
        if (parsed is OutfitReply) {
            val invalid = parsed.ids.filter { it !in wardrobeIds }
            if (invalid.isNotEmpty()) {
                log.warn("suggest route={} invalid item ids in reply count={}; one corrective re-ask", route, invalid.size)
                val ask = current + ChatMessage("user", prompts.idCorrectionMessage(invalid))
                val retried = runCatching { ask + ChatMessage("assistant", chat(route, ask)) }.getOrNull()
                val reparsed = retried?.let { runCatching { parse(it.last().content, aliasToId) }.getOrNull() }
                if (retried != null && reparsed != null) {
                    current = retried
                    parsed = reparsed
                }
                if (parsed is OutfitReply) parsed = parsed.copy(ids = parsed.ids.filter { it in wardrobeIds })
            }
            if (parsed is OutfitReply && parsed.ids.isEmpty()) throw ApiException.upstreamFailed("Gemini", "outfit with no valid items")
        }
        return Turn(parsed, current)
    }

    /** Old oneShotCall: up to 4 tries with backoff (0.5 s, 1 s, 2 s) on a blank reply or a non-timeout failure. */
    private fun chat(route: String, history: List<ChatMessage>): String {
        val system = history.first { it.role == "system" }.content
        val messages = history.filter { it.role != "system" }
        var delay = 500L
        repeat(4) { attempt ->
            try {
                val r = gemini.chat(props.ai.modelSuggest, system, messages)
                GeminiCallLog.log(route, r.usage, 0)
                if (r.text.isNotBlank()) return r.text
                log.warn("gemini route={} blank reply attempt={}", route, attempt + 1)
            } catch (e: ApiException) {
                if (e.status == HttpStatus.GATEWAY_TIMEOUT || attempt == 3) throw e
            }
            if (attempt < 3) Thread.sleep(delay)
            delay *= 2
        }
        throw ApiException.upstreamFailed("Gemini", "blank reply")
    }

    /** Shape 1 `{"outfit": {...}}` or Shape 2 `{"lowConfidence": true, ...}` (old SessionJsonUtils). */
    private fun parse(text: String, aliasToId: Map<String, String>): Parsed {
        val map = parseJson(text) ?: throw ApiException.upstreamFailed("Gemini", "reply is not JSON")
        if (map["lowConfidence"] == true) {
            val reason = (map["reason"] as? String)?.trim()?.ifBlank { null }
                ?: "I'm not confident I can put something together that honours what you asked for."
            val missing = (map["missingCategories"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(::toCategory) }.distinct()
            return Refusal(reason, missing)
        }
        @Suppress("UNCHECKED_CAST")
        val outfit = map["outfit"] as? Map<String, Any?> ?: throw ApiException.upstreamFailed("Gemini", "no outfit in reply")
        val bySlot = outfit.filterKeys { it != "review" }.mapNotNull { (k, v) ->
            (v as? String)?.trim()?.ifBlank { null }?.let { k.lowercase() to (aliasToId[it] ?: it) }
        }
        val ordered = bySlot.sortedBy { (k, _) -> SLOT_ORDER.indexOf(k).let { if (it < 0) SLOT_ORDER.size else it } }
        val review = when (val r = outfit["review"]) {
            is List<*> -> r
            is String -> listOf(r)
            else -> emptyList()
        }
        return OutfitReply(
            ids = ordered.map { it.second }.distinct(),
            review = review.getOrNull(0)?.toString()?.trim().orEmpty(),
            occasion = review.getOrNull(1)?.toString()?.trim().orEmpty(),
        )
    }

    /** Saves the session and, for an outfit, a new ai_suggestion row. Caller holds a transaction. */
    private fun persist(session: AiSession, turn: Turn): SuggestionResponse {
        val now = Instant.now()
        session.lastActivityAt = now
        session.updatedAt = now
        return when (val p = turn.parsed) {
            is Refusal -> {
                sessions.save(session)
                SuggestionResponse(LOW_CONFIDENCE, session.id, session.attemptNumber, failure = FailureDto(p.reason, p.missing))
            }
            is OutfitReply -> {
                val name = p.occasion.substringBefore(',').trim().ifBlank { "Outfit" }
                val s = suggestions.save(AiSuggestion(
                    id = UUID.randomUUID().toString(), userId = session.userId, sessionId = session.id, kind = "session",
                    attemptNumber = session.attemptNumber, name = name, itemIds = p.ids, reasoning = p.review,
                    occasion = p.occasion.ifBlank { null }, dayType = session.dayType, note = session.note,
                    createdAt = now, updatedAt = now,
                ))
                sessions.save(session)
                SuggestionResponse(SUCCESS, session.id, session.attemptNumber,
                    suggestion = SuggestedOutfitDto(s.id, name, s.itemIds, p.review, s.occasion))
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** next / refine: 404 if missing or not the caller's; 409 SESSION_CLOSED once accepted. */
    private fun openSession(uid: String, sessionId: String?): AiSession {
        val id = sessionId?.ifBlank { null } ?: throw ApiException.validation("sessionId is required")
        val session = sessions.findByIdAndUserId(id, uid) ?: throw ApiException.notFound("Session not found")
        if (session.status == COMPLETED) throw sessionClosed()
        return session
    }

    private fun sessionClosed() = ApiException.conflict("SESSION_CLOSED", "This session is already completed")

    private fun checkGenerationCap(uid: String) {
        val cfg = config.ledger()
        val limit = if (entitlements.isPremium(uid)) cfg.premiumDailyGenerations else cfg.freeDailyGenerations
        val zone = profiles.zoneOf(uid)
        val since = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        if (suggestions.countByUserIdAndCreatedAtAfter(uid, since) >= limit) throw ApiException.limitReached("generations")
    }

    /** Valid entries only, one per id, sorted by id (alias order = snapshot order). */
    private fun cleanWardrobe(list: List<WardrobeEntryDto>, field: String): List<WardrobeEntryDto> {
        if (list.size > MAX_WARDROBE) throw ApiException.validation("$field has more than $MAX_WARDROBE items")
        return list.map { e ->
            val id = e.id?.trim()?.ifBlank { null } ?: throw ApiException.validation("$field[].id is required")
            if (id.length > 64) throw ApiException.validation("$field[].id is too long")
            val category = e.category?.lowercase()?.takeIf { it in CATEGORIES }
                ?: throw ApiException.validation("$field[].category must be one of ${CATEGORIES.joinToString()}")
            e.copy(id = id, category = category)
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    /**
     * Old rule: an outfit needs a top and a bottom (shoes are optional; the prompt handles their absence).
     * A dress, gown or jumpsuit (category "tops") stands in for both, so bottoms are not required then.
     */
    private fun missingCategories(wardrobe: List<WardrobeEntryDto>): List<String> {
        val tops = wardrobe.filter { it.category == "tops" }
        val onePiece = tops.any { e ->
            val a = e.attributes.orEmpty()
            val text = listOf(a["name"], a["description"], a["tags"]).joinToString(" ").lowercase()
            ONE_PIECE.any { it in text }
        }
        return listOfNotNull(
            "tops".takeIf { tops.isEmpty() },
            "bottoms".takeIf { wardrobe.none { it.category == "bottoms" } && !onePiece },
        )
    }

    /** id → I1..In by the sorted id order (old buildAliasByItemId). */
    private fun aliases(sortedIds: List<String>): Map<String, String> =
        sortedIds.withIndex().associateTo(LinkedHashMap()) { (i, id) -> id to "I${i + 1}" }

    private fun sessionAliases(session: AiSession): Pair<Map<String, String>, Set<String>> {
        val ids = session.wardrobeSnapshot.orEmpty().mapNotNull { it["id"] as? String }
        return aliases(ids) to ids.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun storedHistory(session: AiSession): List<ChatMessage> =
        (session.conversationHistory as? List<Map<String, Any?>>).orEmpty().mapNotNull { m ->
            val role = m["role"] as? String
            val content = m["content"] as? String
            if (role != null && content != null) ChatMessage(role, content) else null
        }.ifEmpty { throw ApiException.conflict("SESSION_CLOSED", "This session has no history to continue; start a new one") }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String): Map<String, Any?>? {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.readValue(clean, Map::class.java) as Map<String, Any?> }.getOrNull()
    }

    /** Free-form model words ("dark_tops", "structured blazers") → one of the five categories, or null. */
    private fun toCategory(word: String): String? {
        val w = word.lowercase()
        return CATEGORY_WORDS.firstOrNull { (_, keys) -> keys.any { it in w } }?.first
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val COMPLETED = "COMPLETED"
        const val SUCCESS = "SUCCESS"
        const val INSUFFICIENT_WARDROBE = "INSUFFICIENT_WARDROBE"
        const val LOW_CONFIDENCE = "LOW_CONFIDENCE"
        const val EXHAUSTED = "EXHAUSTED"
        const val MAX_TEXT = 1000
        const val MAX_WARDROBE = 2000
        val CONTEXTS = listOf("DAILY", "PLANNER", "WARDROBE")
        val CATEGORIES = listOf("tops", "bottoms", "outerwear", "shoes", "accessories")
        val REJECTIONS = listOf("NOT_MY_STYLE", "WRONG_VIBE", "TOO_FORMAL", "TOO_CASUAL", "JUST_BROWSING")
        val SLOT_ORDER = listOf("top", "tops", "dress", "bottom", "bottoms", "outerwear", "shoe", "shoes", "accessory", "accessories")
        val ONE_PIECE = listOf("dress", "gown", "jumpsuit", "playsuit", "romper", "boilersuit", "overalls")
        val CATEGORY_WORDS = listOf(
            "outerwear" to listOf("outer", "jacket", "coat", "blazer", "parka", "layer"),
            "shoes" to listOf("shoe", "sneaker", "trainer", "boot", "footwear", "loafer", "heel", "sandal"),
            "bottoms" to listOf("bottom", "trouser", "pant", "jean", "skirt", "short", "chino"),
            "accessories" to listOf("accessor", "bag", "belt", "hat", "cap", "scarf", "jewel", "watch"),
            "tops" to listOf("top", "shirt", "tee", "blouse", "sweater", "knit", "jumper", "hoodie", "dress"),
        )
    }
}
