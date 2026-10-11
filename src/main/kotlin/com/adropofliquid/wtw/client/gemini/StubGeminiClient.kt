package com.adropofliquid.wtw.client.gemini

import com.adropofliquid.wtw.service.ImageProcessor
import com.google.genai.types.Schema
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Not prod, GEMINI_MODE=stub (the default): no network, canned but VALID results, so the whole pipeline (aliases, parsing, ledger) runs.
 * - chat: a session conversation gets a Shape-1 outfit picked from the wardrobe JSON in the first user message
 *   (one top, one bottom, shoes if any; rotates each turn); anything else gets a {review, occasion} reply.
 * - readImage: a fixed superset object; name/category depend on the image size, so it is deterministic.
 * - editImage: the input photo as a PNG (longest side IMAGE_MAX_SIDE).
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = ["wtw.ai.mode"], havingValue = "stub", matchIfMissing = true)
class StubGeminiClient(private val json: JsonMapper, private val images: ImageProcessor) : GeminiClient {
    private fun usage(model: String) = GeminiUsage(model, 0, 0, 0)

    override fun chat(model: String, system: String, messages: List<ChatMessage>, json: Boolean): GeminiText {
        val first = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
        val text = if (WARDROBE_MARK in first) outfit(first, messages.count { it.role == "assistant" })
        else this.json.writeValueAsString(mapOf(
            "review" to "The pieces share a calm colour story and a similar level of polish, so the look holds together without trying too hard. Easy to wear and easy to repeat.",
            "occasion" to "Casual day out, Coffee with friends, Weekend errands",
        ))
        return GeminiText(text, usage(model))
    }

    @Suppress("UNCHECKED_CAST")
    private fun outfit(firstUserMessage: String, turn: Int): String {
        val line = firstUserMessage.lines().dropWhile { !it.startsWith(WARDROBE_MARK) }.drop(1).firstOrNull().orEmpty()
        val items = runCatching { json.readValue(line, List::class.java) as List<Map<String, Any?>> }.getOrDefault(emptyList())
        fun pick(cat: String) = items.filter { it["category"] == cat }.let { if (it.isEmpty()) null else it[turn % it.size]["id"] as String }
        val top = pick("tops")
        val bottom = pick("bottoms")
        if (top == null || bottom == null) {
            return json.writeValueAsString(mapOf(
                "lowConfidence" to true,
                "reason" to "I need at least a top and a bottom to build an honest outfit from this wardrobe.",
                "missingCategories" to listOfNotNull("tops".takeIf { top == null }, "bottoms".takeIf { bottom == null }),
                "suggestedActions" to listOf("loosen_rules", "show_anyway", "skip"),
            ))
        }
        val outfit = linkedMapOf<String, Any?>("top" to top, "bottom" to bottom)
        pick("shoes")?.let { outfit["shoe"] = it }
        outfit["review"] = listOf(
            "A clean, easy pairing: the top and bottom sit at the same level of polish, and the shoes keep it grounded. Nothing fights for attention, so it works from morning to evening.",
            "Everyday errands, Casual lunch, Relaxed office",
        )
        return json.writeValueAsString(mapOf("outfit" to outfit))
    }

    override fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String, schema: Schema?): GeminiText {
        val img = images.decode(image)
        val i = (img.width + img.height) % CATEGORIES.size
        val (category, name, layering) = CATEGORIES[i]
        val attrs = linkedMapOf<String, Any?>(
            "name" to name, "category" to category,
            "description" to "$name: a simple piece with a relaxed fit. Check the name and category, and change anything that looks wrong.",
            "formality" to "casual", "polish" to "considered", "fitShape" to "relaxed", "layeringRole" to layering,
            "colors" to listOf("black"), "season" to "all", "tags" to listOf("garment_type:${name.substringAfterLast(' ')}"),
            "silhouetteRole" to "draped", "statementLevel" to "quiet", "comfortCost" to "low", "skinCoverageOverall" to "high",
            "colorStory" to listOf("neutral", "dark"), "fabricHand" to listOf("soft"), "skinCoverageZones" to emptyList<String>(),
            "filter" to mapOf("singleGarment" to true, "personPresent" to false),
        )
        return GeminiText(json.writeValueAsString(attrs), usage(model))
    }

    override fun editImage(model: String, prompt: String, image: ByteArray, mimeType: String) =
        GeminiImage(images.toPng(image), "image/png", usage(model))

    companion object {
        const val WARDROBE_MARK = "My Wardrobe:"
        private val CATEGORIES = listOf(
            Triple("tops", "Everyday top", "base"), Triple("bottoms", "Everyday trousers", "base"),
            Triple("outerwear", "Everyday jacket", "outerwear"), Triple("shoes", "Everyday sneakers", "standalone"),
            Triple("accessories", "Everyday bag", "standalone"),
        )
    }
}
