package com.adropofliquid.wtw.client.gemini

import com.adropofliquid.wtw.service.ApiException
import com.google.genai.types.Schema
import org.slf4j.LoggerFactory

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

    /** Image + prompt → JSON text. [schema], if given, is sent as Gemini's `responseSchema`. */
    fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String, schema: Schema? = null): GeminiText

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
