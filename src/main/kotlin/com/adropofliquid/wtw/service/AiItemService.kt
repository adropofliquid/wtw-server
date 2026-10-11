package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.rembg.BackgroundRemovalClient
import com.adropofliquid.wtw.client.gemini.GeminiCallLog
import com.adropofliquid.wtw.client.gemini.GeminiClient
import com.adropofliquid.wtw.config.WtwProperties
import com.google.genai.types.Schema
import com.google.genai.types.Type
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Photo routes (brief 4.4 routes 14–15, 4.5 route 21): read attributes (free, capped), AI studio image
 * (cost_ai_image, charged first, refunded on failure), background removal (free, capped). Nothing is stored.
 * Photos sent to Gemini are shrunk to a 1024px JPEG on white first, to save tokens and upload time.
 */
@Service
class AiItemService(
    private val gemini: GeminiClient,
    private val remover: BackgroundRemovalClient,
    private val prompts: PromptBuilder,
    private val images: ImageProcessor,
    private val ledger: LedgerService,
    private val caps: DailyCapService,
    private val config: AppConfigService,
    private val props: WtwProperties,
    private val json: JsonMapper,
    private val idempotency: IdempotencyService,
) {
    /** Route 14: the superset ItemAttributes object (camelCase) plus `filter`. */
    fun attributes(uid: String, photo: MultipartFile?): Map<String, Any?> {
        val bytes = readPhoto(photo)
        caps.hit(uid, "attributes", config.ledger().attributesDailyCap)
        val r = gemini.readImage(props.ai.modelAttributes, prompts.attributes, forGemini(bytes), "image/jpeg", ATTRIBUTES_SCHEMA)
        GeminiCallLog.log("ai/attributes", r.usage, 0)
        val raw = parseJson(r.text) ?: throw ApiException.upstreamFailed("Gemini", "attributes reply is not JSON")
        return normalize(raw)
    }

    /**
     * Route 15: a clean studio PNG of the garment. Returns the PNG and the balance after the charge.
     * [idempotencyKey] (optional header): a retry with the same key is never charged twice (see IdempotencyService).
     */
    fun generateImage(uid: String, photo: MultipartFile?, idempotencyKey: String? = null): Metered<ByteArray> {
        val key = idempotency.clean(idempotencyKey)
        val bytes = readPhoto(photo)
        return idempotency.once(uid, "ai/generate-image", key) { ref -> generateImageCharged(uid, bytes, ref) }
    }

    private fun generateImageCharged(uid: String, bytes: ByteArray, ref: String?): Metered<ByteArray> {
        val cost = config.ledger().costAiImage
        val charge = ledger.spend(uid, Reason.AI_IMAGE, cost, ref)
        if (!charge.charged) throw IdempotencyService.keyUsed()
        try {
            val r = gemini.editImage(props.ai.modelImage, prompts.generateImage, forGemini(bytes), "image/jpeg")
            GeminiCallLog.log("ai/generate-image", r.usage, cost)
            val png = runCatching { images.toPng(r.bytes) }.getOrElse { throw ApiException.upstreamFailed("Gemini", "image unreadable", it) }
            return Metered(charge.state.balance, png)
        } catch (e: Exception) {
            ledger.refund(uid, charge)
            throw e as? ApiException ?: ApiException.upstreamFailed("Gemini", e)
        }
    }

    /** Route 21: proxy to rembg. Free; stores nothing. */
    fun removeBackground(uid: String, photo: MultipartFile?): ByteArray {
        val bytes = readPhoto(photo)
        caps.hit(uid, "remove_bg", config.ledger().removeBgDailyCap)
        return remover.remove(bytes)
    }

    /** Multipart `photo`: required, ≤ UPLOAD_MAX_BYTES (413), and really a JPEG, PNG or WebP (by its bytes). */
    fun readPhoto(photo: MultipartFile?): ByteArray {
        if (photo == null || photo.isEmpty) throw ApiException.validation("photo is required")
        if (photo.size > props.image.uploadMaxBytes) throw ApiException.tooLarge("photo is larger than ${props.image.uploadMaxBytes} bytes")
        val bytes = photo.bytes
        if (!isJpeg(bytes) && !isPng(bytes) && !isWebp(bytes)) throw ApiException.validation("photo must be a jpeg, png or webp image")
        return bytes
    }

    /** Longest side ≤ 1024, transparent areas on white, JPEG q85. */
    private fun forGemini(bytes: ByteArray): ByteArray {
        val small = images.shrink(images.decode(bytes), GEMINI_MAX_SIDE)
        val rgb = BufferedImage(small.width, small.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, small.width, small.height)
            drawImage(small, 0, 0, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.85f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(rgb, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /**
     * The model's reply → the superset object the app can always decode: the KMP ten are never null
     * (safe defaults), the old seven are null when missing or off-vocabulary. snake_case keys are accepted too.
     */
    private fun normalize(raw: Map<String, Any?>): Map<String, Any?> {
        fun v(key: String): Any? = raw[key] ?: raw[key.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }]
        fun str(key: String) = (v(key) as? String)?.trim()?.ifBlank { null }
        fun oneOf(key: String, allowed: List<String>) = str(key)?.lowercase()?.takeIf { it in allowed }
        fun list(key: String, allowed: List<String>? = null) = (v(key) as? List<*>).orEmpty()
            .mapNotNull { (it as? String)?.trim()?.lowercase()?.ifBlank { null } }
            .filter { allowed == null || it in allowed }.distinct()

        val category = toCategory(str("category") ?: str("type")) ?: "tops"
        @Suppress("UNCHECKED_CAST")
        val filter = v("filter") as? Map<String, Any?>
        return linkedMapOf(
            "name" to (str("name")?.take(80) ?: DEFAULT_NAME.getValue(category)),
            "category" to category,
            "description" to (str("description")?.take(600) ?: ""),
            "formality" to (oneOf("formality", FORMALITY) ?: "casual"),
            "polish" to (oneOf("polish", POLISH) ?: "casual"),
            "fitShape" to (oneOf("fitShape", FIT) ?: "relaxed"),
            "layeringRole" to (oneOf("layeringRole", LAYERING) ?: DEFAULT_LAYERING.getValue(category)),
            "colors" to list("colors").take(5),
            "season" to (oneOf("season", SEASONS) ?: "all"),
            "tags" to list("tags").take(12),
            "silhouetteRole" to oneOf("silhouetteRole", SILHOUETTE),
            "statementLevel" to oneOf("statementLevel", STATEMENT),
            "comfortCost" to oneOf("comfortCost", COMFORT_COST),
            "skinCoverageOverall" to oneOf("skinCoverageOverall", COVERAGE),
            "colorStory" to list("colorStory", COLOR_STORY),
            "fabricHand" to list("fabricHand", FABRIC_HAND),
            "skinCoverageZones" to list("skinCoverageZones", ZONES),
            // Unknown → the cautious answer, so an unread photo never passes the pool filter.
            "filter" to linkedMapOf(
                "singleGarment" to ((filter?.get("singleGarment") ?: filter?.get("single_garment")) as? Boolean ?: false),
                "personPresent" to ((filter?.get("personPresent") ?: filter?.get("person_present")) as? Boolean ?: true),
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String): Map<String, Any?>? {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.readValue(clean, Map::class.java) as Map<String, Any?> }.getOrNull()
    }

    private fun toCategory(s: String?): String? = when (s?.trim()?.lowercase()) {
        "tops", "top" -> "tops"
        "bottoms", "bottom" -> "bottoms"
        "outerwear", "outerwears", "outer" -> "outerwear"
        "shoes", "shoe", "footwear" -> "shoes"
        "accessories", "accessory" -> "accessories"
        else -> null
    }

    private fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
    private fun isWebp(b: ByteArray) = b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"

    private companion object {
        const val GEMINI_MAX_SIDE = 1024
        val FORMALITY = listOf("lounge", "casual", "elevated_casual", "smart_casual", "business", "cocktail", "formal")
        val POLISH = listOf("unfinished", "casual", "considered", "polished", "sharp")
        val FIT = listOf("tight", "tailored", "relaxed", "boxy", "oversized", "slouchy")
        val LAYERING = listOf("base", "mid", "outerwear", "standalone", "statement-layer")
        val SEASONS = listOf("summer", "winter", "all")
        val SILHOUETTE = listOf("structured", "draped", "vertical", "body-skimming", "body-revealing", "body-hiding")
        val STATEMENT = listOf("quiet", "mid", "statement", "loud")
        val COMFORT_COST = listOf("none", "low", "medium", "high")
        val COVERAGE = listOf("high", "medium", "low")
        val COLOR_STORY = listOf("neutral", "muted", "saturated", "jewel", "monochrome", "high-contrast", "low-contrast", "dark")
        val FABRIC_HAND = listOf("crisp", "soft", "drapey", "structured", "stretchy", "slinky", "plush")
        val ZONES = listOf("chest", "arm", "leg", "back", "midriff", "shoulder")
        val DEFAULT_NAME = mapOf("tops" to "Top", "bottoms" to "Bottoms", "outerwear" to "Jacket", "shoes" to "Shoes", "accessories" to "Accessory")
        val DEFAULT_LAYERING = mapOf("tops" to "base", "bottoms" to "base", "outerwear" to "outerwear", "shoes" to "standalone", "accessories" to "standalone")
        val CATEGORY_NAMES = listOf("tops", "bottoms", "outerwear", "shoes", "accessories")

        /**
         * Gemini `responseSchema` for /ai/attributes: the superset object (same keys and lists as [normalize]).
         * It cuts "not JSON" replies; [normalize] still cleans every reply as a safety net.
         */
        val ATTRIBUTES_SCHEMA: Schema = run {
            fun str() = Schema.builder().type(Type.Known.STRING).build()
            fun oneOf(values: List<String>, nullable: Boolean = false) =
                Schema.builder().type(Type.Known.STRING).enum_(values).apply { if (nullable) nullable(true) }.build()
            fun listOf(item: Schema) = Schema.builder().type(Type.Known.ARRAY).items(item).build()
            fun bool() = Schema.builder().type(Type.Known.BOOLEAN).build()
            val props = linkedMapOf(
                "name" to str(),
                "category" to oneOf(CATEGORY_NAMES),
                "description" to str(),
                "formality" to oneOf(FORMALITY),
                "polish" to oneOf(POLISH),
                "fitShape" to oneOf(FIT),
                "layeringRole" to oneOf(LAYERING),
                "colors" to listOf(str()),
                "season" to oneOf(SEASONS),
                "tags" to listOf(str()),
                "silhouetteRole" to oneOf(SILHOUETTE, nullable = true),
                "statementLevel" to oneOf(STATEMENT, nullable = true),
                "comfortCost" to oneOf(COMFORT_COST, nullable = true),
                "skinCoverageOverall" to oneOf(COVERAGE, nullable = true),
                "colorStory" to listOf(oneOf(COLOR_STORY)),
                "fabricHand" to listOf(oneOf(FABRIC_HAND)),
                "skinCoverageZones" to listOf(oneOf(ZONES)),
                "filter" to Schema.builder().type(Type.Known.OBJECT)
                    .properties(mapOf("singleGarment" to bool(), "personPresent" to bool()))
                    .required("singleGarment", "personPresent").build(),
            )
            Schema.builder().type(Type.Known.OBJECT).properties(props).required(props.keys.toList())
                .propertyOrdering(props.keys.toList()).build()
        }
    }
}
