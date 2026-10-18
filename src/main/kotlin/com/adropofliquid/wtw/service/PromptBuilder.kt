package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.gemini.StubGeminiClient
import com.adropofliquid.wtw.dto.WardrobeEntryDto
import com.adropofliquid.wtw.dto.WeatherDto
import com.adropofliquid.wtw.dto.SuggestionModifications
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every prompt the AI gateway sends (brief 4.4). Prompt files live in `resources/prompts/`.
 * Messages are ported from the old SuggestionSessionService / SessionGeminiService, adapted to the KMP request.
 */
@Component
class PromptBuilder(private val json: JsonMapper) {
    val sessionSystem: String = load("prompts/session-system-v2.st")
    val attributes: String = load("prompts/attributes.txt")
    val generateImage: String = load("prompts/generate-image.txt")
    val describeOutfit: String = load("prompts/describe-outfit.txt")

    /** First user message of a session. The wardrobe JSON sits on ONE line after "My Wardrobe:" (the dev stub reads it). */
    fun initialUserMessage(
        wardrobe: List<WardrobeEntryDto>,
        aliasById: Map<String, String>,
        targetDate: LocalDate?,
        today: LocalDate,
        weather: WeatherDto?,
        dayType: String?,
        comfort: String?,
        style: String?,
        note: String?,
        recentlyWorn: Map<String, String>,
        stylistNotes: String,
    ): String = buildString {
        appendLine("Please suggest an outfit for me based on:")
        appendLine()
        appendLine(StubGeminiClient.WARDROBE_MARK)
        appendLine(json.writeValueAsString(wardrobe.map { itemForPrompt(aliasById[it.id] ?: it.id!!, it) }))
        appendLine()
        val dateText = targetDate?.let {
            val f = it.format(DATE_FORMAT)
            if (it == today) "Today ($f)" else f
        } ?: "Not specified (general outfit)"
        appendLine("Date: $dateText")
        appendLine()
        appendLine("Weather:")
        if (weather?.maxC != null && weather.minC != null) {
            appendLine("- High: ${weather.maxC.roundToInt()}°C, Low: ${weather.minC.roundToInt()}°C")
            weather.code?.let { appendLine("- Condition: ${wmoLabel(it)}") }
            weather.precipChance?.let { appendLine("- Chance of rain: $it%") }
            weather.code?.let { appendLine("- Snow expected: ${if (it in 71..77 || it == 85 || it == 86) "Yes" else "No"}") }
        } else {
            appendLine("Weather information not available")
        }
        appendLine()
        appendLine("Context:")
        appendLine("- Day type: ${dayType?.ifBlank { null } ?: "Not sure"}")
        val feel = comfort?.ifBlank { null } ?: "Relaxed"
        appendLine("- How they want to feel: $feel${FEEL_MEANING[feel]?.let { " ($it)" } ?: ""}")
        style?.ifBlank { null }?.let { appendLine("- Style: $it") }
        appendLine("- Additional Info: ${note?.trim()?.ifBlank { null } ?: "None"}")
        val worn = recentlyWorn.entries.mapNotNull { (id, date) -> aliasById[id]?.let { it to date } }.sortedBy { it.first }
        if (worn.isNotEmpty()) {
            appendLine()
            appendLine("Worn in the last few days:")
            worn.forEach { (alias, date) -> appendLine("- $alias (worn $date)") }
        }
        if (stylistNotes.isNotBlank()) {
            appendLine()
            appendLine(stylistNotes)
        }
        appendLine()
        append("Please recommend your best outfit for this occasion.")
    }

    /** Next: the user rejected the last outfit (old buildRejectionMessage). */
    fun rejectionMessage(rejection: String?, feedback: String?): String {
        val reasonHint = when (rejection) {
            "NOT_MY_STYLE" -> "it wasn't my style"
            "WRONG_VIBE" -> "it didn't match the vibe I wanted"
            "TOO_FORMAL" -> "it was too formal for what I need"
            "TOO_CASUAL" -> "it was too casual for what I need"
            "JUST_BROWSING", null -> "I just want to see other options"
            else -> "it wasn't right"
        }
        val core = if (!feedback.isNullOrBlank()) "I rejected the previous outfit — $reasonHint. In my own words: \"$feedback\". Show me another."
        else "I rejected the previous outfit — $reasonHint. Show me another."
        return "$core If you can't honour this without dropping a required category or breaking taste, push back instead of forcing it."
    }

    /** Refine (old buildRefinementMessage). [forceShow] = the user said "show me anyway" after a refusal. */
    fun refinementMessage(m: SuggestionModifications, forceShow: Boolean, feedback: String?): String {
        val parts = buildList {
            if (m.tooWarm) add("- Suggest something cooler/lighter.")
            if (m.tooCold) add("- Suggest something warmer with more layers.")
            if (m.tooCasual) add("- Aim more dressy/polished.")
            if (m.tooFormal) add("- Aim more relaxed.")
            if (m.differentTop) add("- I want a different top.")
            if (m.differentBottom) add("- I want different bottoms.")
            if (!feedback.isNullOrBlank()) add("- In my own words: \"$feedback\".")
        }
        if (forceShow) {
            val intro = if (parts.isNotEmpty()) "Refining my preferences:\n${parts.joinToString("\n")}\n\n" else ""
            return "${intro}Just show me your best attempt with these adjustments, even if you're not fully confident. Return an outfit, not a refusal."
        }
        return if (parts.isNotEmpty()) {
            "Refining my preferences:\n${parts.joinToString("\n")}\n\nPlease suggest a new outfit. If these adjustments can't be honoured without dropping a required category or breaking taste, push back instead."
        } else {
            "Show me a different outfit with my original preferences. Push back if you can't do that honestly."
        }
    }

    fun idCorrectionMessage(invalid: List<String>): String =
        "The outfit referenced item IDs that aren't in the wardrobe I gave you: ${invalid.joinToString(", ")}. " +
            "Use ONLY the item IDs from the wardrobe list I provided (they look like I1, I2, ...). Return a corrected outfit."

    /** describe-outfit user message: the pieces (no ids) and the planned occasion, if any. */
    fun describeUserMessage(items: List<WardrobeEntryDto>, occasion: String?): String = buildString {
        appendLine("The outfit:")
        appendLine(json.writeValueAsString(items.map { itemForPrompt(null, it) }))
        occasion?.trim()?.ifBlank { null }?.let {
            appendLine()
            appendLine("I plan to wear it for: $it")
        }
        appendLine()
        append("Please review this outfit.")
    }

    /** One item as the model sees it: alias, category, then the non-empty superset attributes. */
    private fun itemForPrompt(alias: String?, e: WardrobeEntryDto): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        if (alias != null) out["id"] = alias
        out["category"] = e.category
        val a = e.attributes.orEmpty()
        for (key in PROMPT_KEYS) {
            val v = a[key] ?: continue
            if (v is String && v.isBlank()) continue
            if (v is Collection<*> && v.isEmpty()) continue
            out[key] = v
        }
        return out
    }

    private fun load(path: String) = ClassPathResource(path).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

        /** What each "how you want to feel" choice means, so the model reads the app's short words the same way. */
        private val FEEL_MEANING = mapOf(
            "Cosy" to "warm, soft, snug layers",
            "Relaxed" to "easy and comfortable, nothing fussy",
            "Smart" to "polished and put-together",
            "Bold" to "stand out: a statement piece, colour or contrast",
        )

        /** Superset vocabulary (ruling 9.2), in the order the model reads it. */
        val PROMPT_KEYS = listOf(
            "name", "description", "formality", "polish", "fitShape", "layeringRole", "colors", "season", "tags",
            "silhouetteRole", "statementLevel", "comfortCost", "skinCoverageOverall", "colorStory", "fabricHand", "skinCoverageZones",
        )

        /** WMO code → words (same groups as the app's WeatherKind, with a little more detail). */
        fun wmoLabel(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66, 80, 81 -> "Rain"
            65, 67, 82 -> "Heavy rain"
            71, 73, 77, 85 -> "Snow"
            75, 86 -> "Heavy snow"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Cloudy"
        }
    }
}
