package com.adropofliquid.wtw.client

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.service.ApiException
import com.adropofliquid.wtw.service.ImageProcessor
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.HttpOptions
import com.google.genai.types.Part
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

/** One turn of a model conversation. role = "system" | "user" | "assistant". */
data class ChatMessage(val role: String, val content: String)

/** What one Gemini call cost, for the call log (brief 5.6). */
data class GeminiUsage(val model: String, val inputTokens: Int, val outputTokens: Int, val ms: Long)

data class GeminiText(val text: String, val usage: GeminiUsage)
data class GeminiImage(val bytes: ByteArray, val mimeType: String, val usage: GeminiUsage)

/**
 * The Gemini calls the AI gateway makes (brief 4.4). Every method throws ApiException
 * UPSTREAM_TIMEOUT (504) or UPSTREAM_FAILED (502) on failure. It never logs prompts or replies.
 */
interface GeminiClient {
    /** Text from a system prompt and a message history ("user"/"assistant"). [json] asks for a JSON reply. */
    fun chat(model: String, system: String, messages: List<ChatMessage>, json: Boolean = true): GeminiText

    /** Image + prompt → JSON text. */
    fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiText

    /** Image + prompt → image. */
    fun editImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiImage
}

/** One INFO line per Gemini call (brief 5.6): route, model, tokens, ms, credits. Never prompts or replies. */
object GeminiCallLog {
    private val log = LoggerFactory.getLogger("gemini")
    fun log(route: String, usage: GeminiUsage, credits: Int) =
        log.info("gemini route={} model={} inTokens={} outTokens={} ms={} credits={}",
            route, usage.model, usage.inputTokens, usage.outputTokens, usage.ms, credits)
}

/**
 * prod: Vertex AI through the google-genai SDK. Credentials from ADC (`GOOGLE_APPLICATION_CREDENTIALS`),
 * project/location from VERTEX_PROJECT_ID / VERTEX_LOCATION, timeout AI_TIMEOUT_MS. The SDK client is made
 * on first use, so a Vertex misconfiguration fails AI calls (502) but not the whole app.
 */
@Component
@Profile("prod")
class VertexGeminiClient(private val props: WtwProperties) : GeminiClient {
    private val client: Client by lazy {
        Client.builder()
            .vertexAI(true)
            .project(props.ai.vertexProjectId)
            .location(props.ai.vertexLocation)
            .httpOptions(HttpOptions.builder().timeout(props.ai.timeoutMs.toInt()).build())
            .build()
    }

    override fun chat(model: String, system: String, messages: List<ChatMessage>, json: Boolean): GeminiText {
        val contents = messages.filter { it.role != "system" }.map {
            Content.builder().role(if (it.role == "assistant") "model" else "user").parts(Part.fromText(it.content)).build()
        }
        val config = GenerateContentConfig.builder().systemInstruction(Content.fromParts(Part.fromText(system)))
            .apply { if (json) responseMimeType("application/json") }.build()
        return call(model) { client.models.generateContent(model, contents, config) }.let { (r, u) -> GeminiText(r.text() ?: "", u) }
    }

    override fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiText {
        val content = Content.builder().role("user").parts(Part.fromBytes(image, mimeType), Part.fromText(prompt)).build()
        val config = GenerateContentConfig.builder().responseMimeType("application/json").build()
        return call(model) { client.models.generateContent(model, content, config) }.let { (r, u) -> GeminiText(r.text() ?: "", u) }
    }

    override fun editImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiImage {
        val content = Content.builder().role("user").parts(Part.fromBytes(image, mimeType), Part.fromText(prompt)).build()
        val config = GenerateContentConfig.builder().responseModalities("TEXT", "IMAGE").build()
        val (r, u) = call(model) { client.models.generateContent(model, content, config) }
        val blob = r.parts()?.firstNotNullOfOrNull { p -> p.inlineData().orElse(null)?.takeIf { it.data().isPresent } }
            ?: throw ApiException.upstreamFailed("Gemini (no image in reply)")
        return GeminiImage(blob.data().get(), blob.mimeType().orElse("image/png"), u)
    }

    private fun call(model: String, block: () -> GenerateContentResponse): Pair<GenerateContentResponse, GeminiUsage> {
        val start = System.nanoTime()
        val r = try {
            block()
        } catch (e: Exception) {
            throw if (isTimeout(e)) ApiException.upstreamTimeout("Gemini", e) else ApiException.upstreamFailed("Gemini", e)
        }
        val meta = r.usageMetadata().orElse(null)
        val usage = GeminiUsage(
            model = model,
            inputTokens = meta?.promptTokenCount()?.orElse(0) ?: 0,
            outputTokens = (meta?.candidatesTokenCount()?.orElse(0) ?: 0) + (meta?.thoughtsTokenCount()?.orElse(0) ?: 0),
            ms = (System.nanoTime() - start) / 1_000_000,
        )
        return r to usage
    }

    private fun isTimeout(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(10).any {
        it is InterruptedIOException || it is HttpTimeoutException || it is TimeoutException ||
            it.message?.contains("timeout", ignoreCase = true) == true
    }
}

/**
 * Not prod: no network, canned but VALID results, so the whole pipeline (aliases, parsing, ledger) runs.
 * - chat: a session conversation gets a Shape-1 outfit picked from the wardrobe JSON in the first user message
 *   (one top, one bottom, shoes if any; rotates each turn); anything else gets a {review, occasion} reply.
 * - readImage: a fixed superset object; name/category depend on the image size, so it is deterministic.
 * - editImage: the input photo as a PNG (longest side IMAGE_MAX_SIDE).
 */
@Component
@Profile("!prod")
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

    override fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiText {
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
