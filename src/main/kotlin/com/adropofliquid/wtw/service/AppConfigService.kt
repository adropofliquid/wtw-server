package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.repository.AppConfigRepository
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Live config from `app_config` (brief 3.7), cached 5 minutes: an SQL edit applies within 5 minutes, no deploy.
 * Rows: `ledger` (numbers), `weekly_reset_notification`, `habit_builder_notification` (copy).
 */
@Service
class AppConfigService(private val repo: AppConfigRepository) {
    private val cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build<String, Map<String, Any?>>()

    fun get(name: String): Map<String, Any?> = cache.get(name) { repo.findByName(it)?.configMap ?: emptyMap() }

    fun string(name: String, key: String, default: String): String = get(name)[key]?.toString() ?: default

    fun ledger(): LedgerSettings = LedgerSettings(get("ledger"))
}

/** Typed view of the `ledger` config row. Defaults match V2__seed_config.sql. */
class LedgerSettings(private val m: Map<String, Any?>) {
    private fun int(key: String, default: Int) = (m[key] as? Number)?.toInt() ?: m[key]?.toString()?.toIntOrNull() ?: default

    val freeWeeklyCredits get() = int("free_weekly_credits", 7)
    val premiumWeeklyCredits get() = int("premium_weekly_credits", 100)
    val costOutfitSuggestion get() = int("cost_outfit_suggestion", 1)
    val costAiImage get() = int("cost_ai_image", 2)
    val costOutfitDescription get() = int("cost_outfit_description", 1)
    val freeDailyGenerations get() = int("free_daily_generations", 20)
    val premiumDailyGenerations get() = int("premium_daily_generations", 200)
    val maxAttemptsPerSession get() = int("max_attempts_per_session", 10)
    val attributesDailyCap get() = int("attributes_daily_cap", 200)
    val removeBgDailyCap get() = int("remove_bg_daily_cap", 300)
    val freeItemLimit get() = int("free_item_limit", 100)

    /** RevenueCat product id → credits. */
    val packs: Map<String, Int>
        get() = (m["packs"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
            val n = (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull()
            if (k != null && n != null) k.toString() to n else null
        }?.toMap() ?: emptyMap()
}
