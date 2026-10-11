package com.adropofliquid.wtw.client.gemini

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.service.ApiException
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.HttpOptions
import com.google.genai.types.Part
import com.google.genai.types.Schema
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

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

    override fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String, schema: Schema?): GeminiText {
        val content = Content.builder().role("user").parts(Part.fromBytes(image, mimeType), Part.fromText(prompt)).build()
        val config = GenerateContentConfig.builder().responseMimeType("application/json")
            .apply { if (schema != null) responseSchema(schema) }.build()
        return call(model) { client.models.generateContent(model, content, config) }.let { (r, u) -> GeminiText(r.text() ?: "", u) }
    }

    override fun editImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiImage {
        val content = Content.builder().role("user").parts(Part.fromBytes(image, mimeType), Part.fromText(prompt)).build()
        // Its own timeout (AI_IMAGE_TIMEOUT_MS), set on this request only.
        val config = GenerateContentConfig.builder().responseModalities("TEXT", "IMAGE")
            .httpOptions(HttpOptions.builder().timeout(props.ai.imageTimeoutMs.toInt()).build()).build()
        val (r, u) = call(model) { client.models.generateContent(model, content, config) }
        val blob = r.parts()?.firstNotNullOfOrNull { p -> p.inlineData().orElse(null)?.takeIf { it.data().isPresent } }
            ?: throw ApiException.upstreamFailed("Gemini", "no image in reply")
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
