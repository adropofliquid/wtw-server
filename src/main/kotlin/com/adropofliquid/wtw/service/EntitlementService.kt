package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.RevenueCatClient
import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.config.WtwProperties
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * isPremium(uid) with a Caffeine cache (brief 5.7): TTL RC_CACHE_TTL, max 50,000 entries.
 * A RevenueCat error = not premium for this call, not cached, WARN logged, never a 500.
 * The webhook evicts entries so purchases show up at once.
 */
@Service
class EntitlementService(private val rc: RevenueCatClient, props: WtwProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(props.revenuecat.cacheTtl).build<String, Boolean>()

    fun isPremium(uid: String): Boolean {
        cache.getIfPresent(uid)?.let { return it }
        val start = System.nanoTime()
        return try {
            rc.isPremium(uid).also { cache.put(uid, it) }
        } catch (e: Exception) {
            log.warn("upstream=revenuecat failed uid={} ms={} err={}", uidHash(uid), (System.nanoTime() - start) / 1_000_000, e.javaClass.simpleName)
            false
        }
    }

    fun evict(uid: String) = cache.invalidate(uid)
}
