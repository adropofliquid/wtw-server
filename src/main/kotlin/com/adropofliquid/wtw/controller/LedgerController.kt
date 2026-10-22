package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.LedgerResponse
import com.adropofliquid.wtw.service.AppConfigService
import com.adropofliquid.wtw.service.EntitlementService
import com.adropofliquid.wtw.service.LedgerService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Route 22 (brief 4.6): the caller's credits. A read also writes the lazy weekly grant / premium top-up (3.5).
 * `?refresh=true` (after a purchase): skip the cached premium answer and ask RevenueCat now, so a new subscriber
 * is premium, and topped up, in this same response, without waiting for the webhook or the cache to expire.
 */
@RestController
class LedgerController(
    private val ledger: LedgerService,
    private val config: AppConfigService,
    private val entitlements: EntitlementService,
) {
    @GetMapping("/ledger")
    fun get(@AuthenticationPrincipal uid: String, @RequestParam(defaultValue = "false") refresh: Boolean): ResponseEntity<LedgerResponse> {
        if (refresh) entitlements.evict(uid)
        val s = ledger.read(uid)
        val cfg = config.ledger()
        val body = LedgerResponse(
            balance = s.balance,
            weeklyAllowance = s.weeklyAllowance,
            weeklyRemaining = s.weeklyRemaining,
            purchasedRemaining = s.purchasedRemaining,
            premium = s.premium,
            weekStart = s.weekStart.toString(),
            resetsAt = s.resetsAt.toEpochMilli(),
            costs = linkedMapOf(
                "OUTFIT_SUGGESTION" to cfg.costOutfitSuggestion,
                "AI_IMAGE" to cfg.costAiImage,
                "OUTFIT_DESCRIPTION" to cfg.costOutfitDescription,
            ),
            freeItemLimit = cfg.freeItemLimit,
            freeWeeklyCredits = cfg.freeWeeklyCredits,
            premiumWeeklyCredits = cfg.premiumWeeklyCredits,
        )
        return okWithCredits(s.balance, body)
    }
}
