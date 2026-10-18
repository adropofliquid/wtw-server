package com.adropofliquid.wtw.client.rembg

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.config.msSince
import com.adropofliquid.wtw.service.ApiException
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * REMBG_MODE=http (prod default): multipart POST (field `file`) to REMBG_URL, the Python rembg service. Timeout REMBG_TIMEOUT_MS → 504.
 * At most REMBG_CONCURRENCY calls go to rembg at once; the rest wait in line here (fair order) up to REMBG_QUEUE_WAIT_MS,
 * then 504. Without the line, a burst lands on rembg all at once, every call times out, and rembg keeps working on
 * photos nobody is waiting for any more.
 */
@Component
@ConditionalOnProperty(name = ["wtw.rembg.mode"], havingValue = "http")
class RembgClient(private val props: WtwProperties) : BackgroundRemovalClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(Duration.ofMillis(props.rembg.timeoutMs)) })
        .build()
    private val line = Semaphore(props.rembg.concurrency.coerceAtLeast(1), true)

    override fun remove(photo: ByteArray): ByteArray {
        val queued = System.nanoTime()
        if (!line.tryAcquire(props.rembg.queueWaitMs, TimeUnit.MILLISECONDS)) {
            log.warn("upstream=rembg busy waitedMs={}", (System.nanoTime() - queued) / 1_000_000)
            throw ApiException.upstreamTimeout("Background removal")
        }
        val waitedMs = msSince(queued)
        try {
            return call(photo).also {
                log.info("upstream=rembg ok waitMs={} callMs={} inBytes={} outBytes={}", waitedMs, msSince(queued) - waitedMs, photo.size, it.size)
            }
        } finally {
            line.release()
        }
    }

    private fun call(photo: ByteArray): ByteArray {
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
            // A read timeout mid-body surfaces as "closed", not HttpTimeoutException: the elapsed time tells.
            val timeout = generateSequence<Throwable>(e) { it.cause }.take(10).any { it is HttpTimeoutException || it is InterruptedIOException } ||
                (System.nanoTime() - start) / 1_000_000 >= props.rembg.timeoutMs
            warn(null, start)
            throw if (timeout) ApiException.upstreamTimeout("Background removal", e) else ApiException.upstreamFailed("Background removal", e)
        }
    }

    private fun warn(status: Int?, start: Long) =
        log.warn("upstream=rembg failed status={} ms={}", status ?: "-", (System.nanoTime() - start) / 1_000_000)
}
