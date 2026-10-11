package com.adropofliquid.wtw.service

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * `Idempotency-Key` for the metered AI routes (review S-3). The key is the ledger charge ref (`idem:{key}`),
 * so one key is charged at most once. On top of that, a repeat of the same key:
 * - while the first request is still running → waits for it and returns the same result;
 * - after it finished OK (within [RESULT_TTL], in memory) → returns the stored result, no new call;
 * - otherwise (the first one failed and was refunded, or the result is gone after a restart) →
 *   409 IDEMPOTENCY_KEY_USED from the route's service, so the user is never charged twice.
 * No key → the old behaviour (a fresh ref per call).
 */
@Service
class IdempotencyService {
    private val running = ConcurrentHashMap<String, CompletableFuture<Metered<*>>>()
    private val done = Caffeine.newBuilder()
        .expireAfterWrite(RESULT_TTL)
        .maximumWeight(MAX_BYTES)
        .weigher<String, Metered<*>> { _, v -> ((v.body as? ByteArray)?.size ?: 1024).coerceAtLeast(1) }
        .build<String, Metered<*>>()

    /** Checks the header value: null/blank → null; otherwise 1–64 chars of `A-Z a-z 0-9 - _ . :` or 400. */
    fun clean(key: String?): String? {
        val k = key?.trim()?.ifBlank { null } ?: return null
        if (k.length > 64 || !KEY.matches(k)) throw ApiException.validation("Idempotency-Key must be 1-64 characters of A-Z a-z 0-9 - _ . :")
        return k
    }

    /** Runs [block] once per (uid, route, key). [block] gets the ledger ref to charge with (null when no key). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> once(uid: String, route: String, key: String?, block: (ref: String?) -> Metered<T>): Metered<T> {
        if (key == null) return block(null)
        val id = "$uid|$route|$key"
        done.getIfPresent(id)?.let { return it as Metered<T> }
        val mine = CompletableFuture<Metered<*>>()
        running.putIfAbsent(id, mine)?.let { other ->
            return try {
                other.join() as Metered<T>
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
        }
        try {
            done.getIfPresent(id)?.let { mine.complete(it); return it as Metered<T> }
            val result = block("idem:$key")
            done.put(id, result)
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            running.remove(id, mine)
        }
    }

    companion object {
        val RESULT_TTL: Duration = Duration.ofHours(1)
        const val MAX_BYTES = 64L * 1024 * 1024
        private val KEY = Regex("[A-Za-z0-9._:-]+")

        fun keyUsed() = ApiException.conflict(
            "IDEMPOTENCY_KEY_USED",
            "This Idempotency-Key was already used by a request that failed or whose result is gone. Send a new key.",
        )
    }
}
