package com.adropofliquid.wtw.client

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.service.ApiException
import com.adropofliquid.wtw.service.ImageProcessor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.io.InterruptedIOException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration

/** Background removal (brief 4.5). Returns a transparent PNG. Throws ApiException 502/504 on failure. */
fun interface BackgroundRemovalClient {
    fun remove(photo: ByteArray): ByteArray
}

/** REMBG_MODE=http (prod default): multipart POST (field `file`) to REMBG_URL, the Python rembg service. Timeout REMBG_TIMEOUT_MS → 504. */
@Component
@ConditionalOnProperty(name = ["wtw.rembg.mode"], havingValue = "http")
class RembgClient(private val props: WtwProperties) : BackgroundRemovalClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(Duration.ofMillis(props.rembg.timeoutMs)) })
        .build()

    override fun remove(photo: ByteArray): ByteArray {
        val start = System.nanoTime()
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource(photo) { override fun getFilename() = "photo" })
        }
        try {
            return http.post().uri(props.rembg.url).contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                .retrieve().body<ByteArray>()?.takeIf { it.isNotEmpty() }
                ?: throw ApiException.upstreamFailed("Background removal")
        } catch (e: ApiException) {
            warn(null, start)
            throw e
        } catch (e: RestClientResponseException) {
            warn(e.statusCode.value(), start)
            throw ApiException.upstreamFailed("Background removal", e)
        } catch (e: Exception) {
            val timeout = generateSequence<Throwable>(e) { it.cause }.take(10).any { it is HttpTimeoutException || it is InterruptedIOException }
            warn(null, start)
            throw if (timeout) ApiException.upstreamTimeout("Background removal", e) else ApiException.upstreamFailed("Background removal", e)
        }
    }

    private fun warn(status: Int?, start: Long) =
        log.warn("upstream=rembg failed status={} ms={}", status ?: "-", (System.nanoTime() - start) / 1_000_000)
}

/** REMBG_MODE=stub (dev default): no network. The photo back as a PNG, longest side 600 (like rembg with MAX_SIDE=600). */
@Component
@ConditionalOnProperty(name = ["wtw.rembg.mode"], havingValue = "stub")
class StubBackgroundRemovalClient(private val images: ImageProcessor) : BackgroundRemovalClient {
    override fun remove(photo: ByteArray): ByteArray = images.toPng(photo, 600)
}
