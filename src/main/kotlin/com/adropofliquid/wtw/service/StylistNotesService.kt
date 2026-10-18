package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.domain.AiSuggestion
import com.adropofliquid.wtw.dto.WardrobeEntryDto
import com.adropofliquid.wtw.repository.AiSuggestionRepository
import com.adropofliquid.wtw.repository.UserProfileRepository
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * The "STYLIST'S NOTES ON THIS CLIENT" block of the first session message (brief 4.4, ruling 9.2).
 * Port of the old PersonalizationContextService + StylistSignalService:
 * - what they told us at signup (user_profile style, vibes, events);
 * - pickiness: accepts / (accepts + rejects) over the recent ai_suggestion history;
 * - taste leanings: accept/reject rates per superset attribute value of the items in reacted outfits.
 *   Attributes come from the wardrobe sent with the request (the server holds no closet for this), so an
 *   item no longer in the wardrobe adds nothing;
 * - recently accepted outfits ("do NOT repeat"), as session aliases.
 * Never throws: on any error the block is empty.
 */
@Service
class StylistNotesService(
    private val suggestions: AiSuggestionRepository,
    private val profiles: UserProfileRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Recent history per user, cached 15 minutes (the old signal cache TTL) to keep it off every turn. */
    private val history = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(20_000)
        .build<String, List<AiSuggestion>>()

    /** Called after an accept, so the next session's "do not repeat" list includes it. */
    fun evict(uid: String) = history.invalidate(uid)

    fun block(uid: String, wardrobe: List<WardrobeEntryDto>, aliasById: Map<String, String>, requestStyle: String?): String = try {
        val profile = profiles.findById(uid).orElse(null)
        val style = requestStyle?.ifBlank { null } ?: profile?.style
        val vibes = Vocab.vibes(profile?.vibes.orEmpty())
        val events = Vocab.occasions(profile?.events.orEmpty())
        val rows = history.get(uid) { suggestions.findTop100ByUserIdOrderByCreatedAtDesc(it) }
        val attrsById = wardrobe.associate { it.id!! to it.attributes.orEmpty() }
        render(style, vibes, events, rows, attrsById, aliasById)
    } catch (e: Exception) {
        log.warn("stylist notes skipped uid={} err={}", uidHash(uid), e.javaClass.simpleName)
        ""
    }

    private fun render(
        style: String?, vibes: List<String>, events: List<String>, rows: List<AiSuggestion>,
        attrsById: Map<String, Map<String, Any?>>, aliasById: Map<String, String>,
    ): String {
        val accepted = rows.filter { it.accepted && it.sessionId != null }
        val rejected = rows.filter { it.rejected && it.sessionId != null && !it.accepted }
        val signal = signal(accepted, rejected, attrsById)
        val worn = accepted.take(10).mapNotNull { s ->
            val refs = s.itemIds.mapNotNull { aliasById[it] }
            if (refs.size < 2) null else Triple(s, refs, s.acceptedAt ?: s.createdAt)
        }
        val hasSignup = style != null || vibes.isNotEmpty() || events.isNotEmpty()
        if (!hasSignup && signal.first == null && signal.second.isEmpty() && worn.isEmpty()) return ""

        return buildString {
            appendLine("STYLIST'S NOTES ON THIS CLIENT")
            if (hasSignup) {
                appendLine()
                appendLine("What they told us at signup:")
                style?.let { appendLine("- Style: $it") }
                if (vibes.isNotEmpty()) appendLine("- Vibes they like: ${vibes.joinToString(", ")}")
                if (events.isNotEmpty()) appendLine("- Typical occasions: ${events.joinToString(", ")}")
            }
            signal.first?.let {
                appendLine()
                appendLine("How hard they are to please:")
                appendLine("- $it")
            }
            if (signal.second.isNotEmpty()) {
                appendLine()
                appendLine("What you've learned about their taste (from what they accept and reject):")
                signal.second.forEach { appendLine("- $it") }
            }
            if (worn.isNotEmpty()) {
                appendLine()
                appendLine("Recently worn — do NOT repeat these exact combinations:")
                worn.forEachIndexed { i, (s, refs, at) ->
                    val label = listOfNotNull(s.name, s.occasion).joinToString(" - ").ifEmpty { "Outfit" }
                    appendLine("  ${i + 1}. $label (worn ${at.atZone(ZoneOffset.UTC).toLocalDate()}): [${refs.joinToString(", ")}]")
                }
            }
        }.trimEnd()
    }

    /** (pickiness line, leaning lines). Same thresholds and wording as the old StylistSignalService. */
    private fun signal(
        accepted: List<AiSuggestion>, rejected: List<AiSuggestion>, attrsById: Map<String, Map<String, Any?>>,
    ): Pair<String?, List<String>> {
        val decisions = accepted.size + rejected.size
        if (decisions == 0) return null to emptyList()
        val pickiness = if (decisions >= MIN_DECISIONS) {
            val pct = (accepted.size.toDouble() / decisions * 100).roundToInt()
            if (accepted.size.toDouble() / decisions < PICKY_ACCEPT_RATE) {
                "Hard to please — hold a high bar and prefer an honest refusal over a weak or borderline suggestion, " +
                    "because a mediocre outfit will be rejected. Accepts only ~$pct% of suggestions (${accepted.size}/$decisions decisions)."
            } else {
                "Receptive — you can be a touch bolder rather than playing it ultra-safe. Accepts ~$pct% of suggestions (${accepted.size}/$decisions decisions)."
            }
        } else null

        val acc = mutableMapOf<Pair<String, String>, Int>()
        val rej = mutableMapOf<Pair<String, String>, Int>()
        accepted.forEach { s -> buckets(s.itemIds, attrsById).forEach { acc.merge(it, 1, Int::plus) } }
        rejected.forEach { s -> buckets(s.itemIds, attrsById).forEach { rej.merge(it, 1, Int::plus) } }

        data class Lean(val line: String, val support: Int)
        val prefers = mutableMapOf<String, Lean>()
        val avoids = mutableMapOf<String, Lean>()
        for (key in acc.keys + rej.keys) {
            val (axis, value) = key
            val nA = acc[key] ?: 0
            val nR = rej[key] ?: 0
            val support = nA + nR
            if (support < MIN_SUPPORT) continue
            if (nA >= MIN_PREFER_ACCEPT && nA.toDouble() / support >= PREFER_SHARE) {
                if ((prefers[axis]?.support ?: -1) < support) prefers[axis] = Lean("Leans toward $value ${label(axis)} — accepted in $nA of $support decisive reactions.", support)
            } else if (nR >= MIN_SUPPORT && nA == 0) {
                if ((avoids[axis]?.support ?: -1) < support) avoids[axis] = Lean("Tends to reject $value ${label(axis)} — rejected in $nR of $support reactions, never accepted.", support)
            }
        }
        val lines = (prefers.values + avoids.values).sortedByDescending { it.support }.take(MAX_LINES).map { it.line }
        return pickiness to lines
    }

    /** Distinct (axis, value) buckets across an outfit's items: an outfit counts once per bucket. */
    private fun buckets(itemIds: List<String>, attrsById: Map<String, Map<String, Any?>>): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        for (id in itemIds) {
            val a = attrsById[id] ?: continue
            for (axis in SCALAR_AXES) (a[axis] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += axis to it }
            for (axis in ARRAY_AXES) (a[axis] as? List<*>)?.forEach { v -> (v as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += axis to it } }
        }
        return out
    }

    /** "fitShape" → "fit shape", "skinCoverageOverall" → "skin coverage". */
    private fun label(axis: String) = when (axis) {
        "skinCoverageOverall" -> "skin coverage"
        else -> axis.replace(Regex("([A-Z])")) { " " + it.value.lowercase() }
    }

    private companion object {
        const val MIN_DECISIONS = 5
        const val PICKY_ACCEPT_RATE = 0.33
        const val MIN_SUPPORT = 3
        const val MIN_PREFER_ACCEPT = 2
        const val PREFER_SHARE = 0.70
        const val MAX_LINES = 4
        val SCALAR_AXES = listOf("formality", "polish", "fitShape", "silhouetteRole", "statementLevel", "layeringRole", "comfortCost", "skinCoverageOverall")
        val ARRAY_AXES = listOf("colorStory", "fabricHand")
    }
}
