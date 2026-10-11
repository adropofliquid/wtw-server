package com.adropofliquid.wtw.service

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abuse caps for FREE actions (attributes, background removal): in-memory counters per uid per UTC day.
 * They reset on restart; that is accepted (DECISIONS D-012). Paid actions are capped by the ledger instead;
 * the suggestion generation cap counts ai_suggestion rows (SuggestionService).
 */
@Service
class DailyCapService {
    private val counters = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(26)).maximumSize(200_000).build<String, AtomicInteger>()

    /** Counts one use of [which]; throws 429 LIMIT_REACHED when it would exceed [limit]. */
    fun hit(uid: String, which: String, limit: Int) {
        val key = "$uid|$which|${LocalDate.now(ZoneOffset.UTC)}"
        val n = counters.get(key) { AtomicInteger() }.incrementAndGet()
        if (n > limit) throw ApiException.limitReached(which)
    }
}
