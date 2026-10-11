package com.adropofliquid.wtw.service

import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.atan2
import kotlin.math.sqrt

/** Pure old → new value transforms for the data copy (brief 7.3–7.7). No I/O. */
object DataCopyTransforms {
    private val json: JsonMapper = JsonMapper.builder().build()

    val CATEGORIES = setOf("tops", "bottoms", "outerwear", "shoes", "accessories")

    /** The KMP colour words (FakeStylist.COLOURS). `colors` takes only these from `color_story` (7.3 "closest match"). */
    private val COLOUR_WORDS = setOf("black", "white", "grey", "navy", "blue", "green", "olive", "red", "burgundy",
        "pink", "cream", "ivory", "beige", "tan", "khaki", "brown", "yellow", "orange", "purple", "gold", "charcoal")

    /** Comma-joined list → trimmed, non-empty parts (profile vibes/event, suggestion vibes). */
    fun splitList(s: String?): List<String> = s?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Old `type` → category: lower-case; empty, NULL and the literal 'null' → null. Unknown values → null too. */
    fun category(type: String?): String? {
        val t = type?.trim()?.lowercase()
        if (t.isNullOrEmpty() || t == "null") return null
        return t.takeIf { it in CATEGORIES }
    }

    /** A JSON array (as text) → list of strings. Bad or non-array JSON → null. */
    fun jsonStringList(text: String?): List<String>? {
        if (text.isNullOrBlank()) return null
        val v = runCatching { json.readValue(text, Any::class.java) }.getOrNull() as? List<*> ?: return null
        return v.mapNotNull { it?.toString() }
    }

    /** Any JSON text → its value (List/Map/…), or null when it does not parse. */
    fun parseJson(text: String?): Any? = if (text.isNullOrBlank()) null else runCatching { json.readValue(text, Any::class.java) }.getOrNull()

    /**
     * The one `attributes` object (ruling 9.2, brief 7.3): camelCase keys, null values left out,
     * `name` and `season` absent. Always an object, even when only category/description are known.
     */
    fun attributes(i: OldItem): Map<String, Any?> {
        val colorStory = jsonStringList(i.colorStory)
        val out = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?) { if (v != null) out[k] = v }
        put("category", category(i.type))
        put("description", i.description)
        put("formality", i.formality)
        put("polish", i.polish)
        put("fitShape", i.fitShape)
        put("layeringRole", i.layeringRole)
        put("colors", colorStory?.filter { it.lowercase() in COLOUR_WORDS }?.map { it.lowercase() } ?: emptyList<String>())
        put("tags", jsonStringList(i.tags))
        put("silhouetteRole", i.silhouetteRole)
        put("statementLevel", i.statementLevel)
        put("comfortCost", i.comfortCost)
        put("skinCoverageOverall", i.skinCoverageOverall)
        put("colorStory", colorStory)
        put("fabricHand", jsonStringList(i.fabricHand))
        put("skinCoverageZones", jsonStringList(i.skinCoverageZones))
        return out
    }

    data class Placement(val x: Float, val y: Float, val scale: Float, val rotation: Float)

    /**
     * Flutter Matrix4 (16 floats, column-major) → KMP canvas fractions (brief 7.4).
     * Old canvas 500×700, item box 150×150 centred, transform origin top-left; KMP base side = 210 on a 500 canvas.
     * NULL, empty, wrong-length or NaN → the identity matrix (x 0.5, y 0.5, scale 0.714, rotation 0).
     */
    fun placement(position: DoubleArray?): Placement {
        val m = position?.takeIf { p -> p.size == 16 && p.none { it.isNaN() || it.isInfinite() } } ?: IDENTITY
        val x = ((m[0] * 250 + m[4] * 350 + m[12]) / 500).coerceIn(0.0, 1.0)
        val y = ((m[1] * 250 + m[5] * 350 + m[13]) / 700).coerceIn(0.0, 1.0)
        val scale = (sqrt(m[0] * m[0] + m[1] * m[1]) * 150 / 210).coerceIn(0.3, 3.0)
        val rotation = Math.toDegrees(atan2(m[1], m[0]))
        return Placement(x.toFloat(), y.toFloat(), scale.toFloat(), rotation.toFloat())
    }

    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    /**
     * Planned date (7.5). A row at exactly midnight is already a plain date (what Flutter sent): keep it, because
     * moving midnight UTC into a zone west of UTC would shift it to the day before (122 rows in the local copy).
     * A row with a time part (126 rows) is read as UTC, moved to the user's zone, then the date part. Bad or no zone → UTC.
     */
    fun localDate(utc: LocalDateTime, timezone: String?): LocalDate {
        if (utc.toLocalTime() == java.time.LocalTime.MIDNIGHT) return utc.toLocalDate()
        val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
    }

    /** S3 bucket named by the host of an old public URL, e.g. `https://whatshouldiwearr.s3.eu-west-2.amazonaws.com/…`. */
    fun bucketFromUrl(url: String?): String? {
        val host = url?.substringAfter("://", "")?.substringBefore('/')?.lowercase() ?: return null
        return when {
            host.startsWith("whatshouldiwearr.") -> "whatshouldiwearr"
            host.startsWith("whatshouldiwear.") -> "whatshouldiwear"
            else -> null
        }
    }
}
