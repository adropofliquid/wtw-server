package com.adropofliquid.wtw.dto

/**
 * AI gateway shapes (brief 4.4). Field names are the KMP `@Serializable` names in `backend/AiGateway.kt`.
 * Enums arrive as strings and are checked in the services, so a bad value is a 400 VALIDATION with a clear message.
 */

/** One wardrobe item sent with a request. `attributes` is the superset object (unknown keys kept). */
data class WardrobeEntryDto(
    val id: String? = null,
    val category: String? = null,
    val attributes: Map<String, Any?>? = null,
)

/** KMP `DayWeather`: date ISO, code = WMO weather code. */
data class WeatherDto(
    val date: String? = null,
    val code: Int? = null,
    val maxC: Double? = null,
    val minC: Double? = null,
    val precipChance: Int? = null,
)

data class SuggestionRequest(
    val context: String? = null,
    val dayType: String? = null,
    val comfort: String? = null,
    val date: String? = null,
    val weather: WeatherDto? = null,
    val wardrobe: List<WardrobeEntryDto>? = null,
    val style: String? = null,
    val note: String? = null,
    val recentlyWorn: Map<String, String>? = null,
)

data class NextRequest(
    val sessionId: String? = null,
    /** NOT_MY_STYLE | WRONG_VIBE | TOO_FORMAL | TOO_CASUAL | JUST_BROWSING | null */
    val rejection: String? = null,
    val feedback: String? = null,
)

data class SuggestionModifications(
    val tooWarm: Boolean = false,
    val tooCold: Boolean = false,
    val tooCasual: Boolean = false,
    val tooFormal: Boolean = false,
    val differentTop: Boolean = false,
    val differentBottom: Boolean = false,
)

data class RefineRequest(
    val sessionId: String? = null,
    val modifications: SuggestionModifications? = null,
    val feedback: String? = null,
)

data class AcceptRequest(val sessionId: String? = null, val suggestionId: String? = null)

data class AcceptResponse(val charged: Boolean, val balance: Int)

data class SuggestedOutfitDto(
    val id: String,
    val name: String,
    val itemIds: List<String>,
    val reasoning: String,
    val occasion: String?,
)

/** missingCategories holds only the five category names, so the app's Category enum always decodes. */
data class FailureDto(val reason: String?, val missingCategories: List<String> = emptyList())

data class SuggestionResponse(
    /** SUCCESS | INSUFFICIENT_WARDROBE | LOW_CONFIDENCE | EXHAUSTED */
    val status: String,
    val sessionId: String,
    val attempt: Int,
    val suggestion: SuggestedOutfitDto? = null,
    val failure: FailureDto? = null,
)

data class DescribeRequest(
    val outfitId: String? = null,
    val occasion: String? = null,
    val items: List<WardrobeEntryDto>? = null,
)

data class DescribeResponse(val review: String, val occasion: String)
