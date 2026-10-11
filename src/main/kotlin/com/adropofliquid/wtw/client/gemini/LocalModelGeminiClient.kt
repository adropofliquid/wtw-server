package com.adropofliquid.wtw.client.gemini

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.service.ApiException
import com.adropofliquid.wtw.service.ImageProcessor
import com.google.genai.types.Schema
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

/**
 * Dev only, GEMINI_MODE=local: a "cheap real feel" with a model running on this machine. Calls an
 * OpenAI-compatible server (Ollama, mlx-vlm serve, ...) at LOCAL_MODEL_URL `/v1/chat/completions` with
 * LOCAL_MODEL_NAME for chat and photo reading. The server never starts or stops the model: if nothing is
 * listening, the call fails with a clear 502. The Gemini [Schema] is not sent (JSON mode only); the
 * services clean every reply anyway.
 *
 * editImage is a PLACEHOLDER: it returns the input photo as a PNG (like the stub). No image model is wired.
 * Never active in prod (@Profile("!prod")).
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = ["wtw.ai.mode"], havingValue = "local")
class LocalModelGeminiClient(private val props: WtwProperties, private val images: ImageProcessor) : GeminiClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = RestClient.builder()
        .baseUrl(props.ai.localModelUrl.trimEnd('/'))
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build())
            .apply { setReadTimeout(Duration.ofMillis(props.ai.timeoutMs)) })
        .build()

    init {
        log.warn("GEMINI_MODE=local: AI calls go to {} model={} (image generation is a placeholder)",
            props.ai.localModelUrl, props.ai.localModelName)
    }

    override fun chat(model: String, system: String, messages: List<ChatMessage>, json: Boolean): GeminiText {
        val msgs = listOf(mapOf("role" to "system", "content" to system)) +
            messages.filter { it.role != "system" }.map { mapOf("role" to it.role, "content" to it.content) }
        return complete(msgs, json)
    }

    override fun readImage(model: String, prompt: String, image: ByteArray, mimeType: String, schema: Schema?): GeminiText {
        val dataUrl = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(image)
        val content = listOf(
            mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUrl)),
            mapOf("type" to "text", "text" to prompt),
        )
        return complete(listOf(mapOf("role" to "user", "content" to content)), json = true)
    }

    override fun editImage(model: String, prompt: String, image: ByteArray, mimeType: String): GeminiImage {
        log.info("local model: generate-image is a placeholder (the input photo as a PNG)")
        return GeminiImage(images.toPng(image), "image/png", GeminiUsage("local-placeholder", 0, 0, 0))
    }

    private fun complete(messages: List<Map<String, Any?>>, json: Boolean): GeminiText {
        val name = props.ai.localModelName
        val body = linkedMapOf<String, Any?>("model" to name, "messages" to messages, "stream" to false)
        if (json) body["response_format"] = mapOf("type" to "json_object")
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        val reply = try {
            http.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body<Map<String, Any?>>()
        } catch (e: RestClientResponseException) {
            log.warn("upstream=local-model status={} ms={}", e.statusCode.value(), ms())
            throw ApiException.upstreamFailed("Local model", "HTTP ${e.statusCode.value()} from ${props.ai.localModelUrl} (is model '$name' pulled?)", e)
        } catch (e: Exception) {
            val chain = generateSequence<Throwable>(e) { it.cause }.take(10).toList()
            log.warn("upstream=local-model status={} ms={}", e.javaClass.simpleName, ms())
            if (chain.any { it is HttpTimeoutException || it is InterruptedIOException }) throw ApiException.upstreamTimeout("Local model", e)
            if (chain.any { it is ConnectException }) throw ApiException.upstreamFailed("Local model", "nothing is listening at ${props.ai.localModelUrl}; start it yourself (see README)", e)
            throw ApiException.upstreamFailed("Local model", e)
        }
        val text = ((reply?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>)
            ?.let { (it["message"] as? Map<*, *>)?.get("content") as? String }.orEmpty()
        val usage = reply?.get("usage") as? Map<*, *>
        return GeminiText(text, GeminiUsage(
            model = "local:$name",
            inputTokens = (usage?.get("prompt_tokens") as? Number)?.toInt() ?: 0,
            outputTokens = (usage?.get("completion_tokens") as? Number)?.toInt() ?: 0,
            ms = ms(),
        ))
    }
}
