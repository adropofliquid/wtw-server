package com.adropofliquid.wtw.config

import com.adropofliquid.wtw.security.AuthFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.security.MessageDigest

/** One INFO line per request: method, path template, status, ms, uid hash (brief 5.6). Never bodies or tokens. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger("request")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val start = System.nanoTime()
        try {
            chain.doFilter(req, res)
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            val path = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: req.requestURI
            val uid = req.getAttribute(AuthFilter.UID_ATTRIBUTE) as? String
            if (path != "/health") log.info("{} {} {} {}ms uid={}", req.method, path, res.status, ms, uidHash(uid))
        }
    }

    companion object {
        /** First 8 hex chars of SHA-256(uid). Use this anywhere a uid is logged. */
        fun uidHash(uid: String?): String = uid?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }.take(8)
        } ?: "-"
    }
}
