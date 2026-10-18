package com.adropofliquid.wtw.service

/**
 * The app's style words (2026-10-18). Old words from earlier app builds and the old database are mapped on the
 * way in, so an older build cannot store them again. Matching ignores case; unknown words are kept as sent.
 */
object Vocab {
    val VIBES = listOf("Classic", "Minimal", "Smart casual", "Streetwear", "Sporty", "Colourful")
    /** Profile events and suggestion day types. The suggestion flow also sends "Not sure". */
    val OCCASIONS = listOf("Work", "Casual", "Going out", "Date night", "Active", "Travel")
    val COMFORTS = listOf("Cosy", "Relaxed", "Smart", "Bold")

    private val OLD_VIBES = mapOf(
        "clean cut" to "Classic", "sporty" to "Sporty", "chill mode" to "Minimal",
        "fresh fit" to "Smart casual", "glow up" to "Colourful",
    )
    private val OLD_OCCASIONS = mapOf("social" to "Going out")
    private val OLD_COMFORTS = mapOf(
        "cozy" to "Cosy", "comfortable" to "Relaxed", "sharp" to "Smart", "effortless" to "Relaxed",
    )

    fun vibe(word: String): String = map(word, VIBES, OLD_VIBES)
    fun occasion(word: String): String = map(word, OCCASIONS + "Not sure", OLD_OCCASIONS)
    fun comfort(word: String): String = map(word, COMFORTS, OLD_COMFORTS)

    fun vibes(words: List<String>): List<String> = words.mapNotNull { it.clean()?.let(::vibe) }.distinct()
    fun occasions(words: List<String>): List<String> = words.mapNotNull { it.clean()?.let(::occasion) }.distinct()

    private fun map(word: String, current: List<String>, old: Map<String, String>): String {
        val w = word.trim()
        val key = w.lowercase()
        return current.firstOrNull { it.lowercase() == key } ?: old[key] ?: w
    }

    private fun String.clean() = trim().ifEmpty { null }
}
