package com.adropofliquid.wtw.config

import com.adropofliquid.wtw.security.AuthFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.io.PrintWriter
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

/**
 * One INFO line per request (brief 5.6): method, path template, status, total ms, auth ms, bytes in and out,
 * uid hash. Never bodies or tokens. Sets `rid` (a short request id) in the MDC so every line logged while
 * serving the request can be grepped together; AuthFilter adds `uid`. The response is counted, not buffered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger("request")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val start = System.nanoTime()
        MDC.put("rid", "%08x".format(ThreadLocalRandom.current().nextInt()))
        val counted = CountingResponse(res)
        try {
            chain.doFilter(req, counted)
        } finally {
            val path = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: req.requestURI
            val uid = req.getAttribute(AuthFilter.UID_ATTRIBUTE) as? String
            if (path != "/health") {
                log.info("{} {} {} {}ms auth={}ms in={}B out={}B uid={}", req.method, path, res.status, msSince(start),
                    req.getAttribute(AuthFilter.AUTH_MS_ATTRIBUTE) ?: "-", req.contentLengthLong.coerceAtLeast(0),
                    counted.bytes, uidHash(uid))
            }
            MDC.remove("rid")
            MDC.remove("uid")
        }
    }

    /** Counts the bytes written to the body. */
    private class CountingResponse(res: HttpServletResponse) : HttpServletResponseWrapper(res) {
        var bytes = 0L
        private var stream: ServletOutputStream? = null
        private var writer: PrintWriter? = null

        override fun getOutputStream(): ServletOutputStream = stream ?: object : ServletOutputStream() {
            val inner = super@CountingResponse.getOutputStream()
            override fun write(b: Int) { inner.write(b); bytes++ }
            override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); bytes += len }
            override fun flush() = inner.flush()
            override fun close() = inner.close()
            override fun isReady() = inner.isReady
            override fun setWriteListener(l: WriteListener) = inner.setWriteListener(l)
        }.also { stream = it }

        override fun getWriter(): PrintWriter = writer ?: PrintWriter(
            java.io.OutputStreamWriter(outputStream, characterEncoding ?: "UTF-8"), false,
        ).also { writer = it }

        override fun flushBuffer() {
            writer?.flush()
            super.flushBuffer()
        }
    }

    companion object {
        /** First 8 hex chars of SHA-256(uid). Use this anywhere a uid is logged. */
        fun uidHash(uid: String?): String = uid?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }.take(8)
        } ?: "-"
    }
}
