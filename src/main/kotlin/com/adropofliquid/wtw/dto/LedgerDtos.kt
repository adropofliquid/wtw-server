package com.adropofliquid.wtw.dto

/** GET /ledger (brief 4.6, route 22). `resetsAt` is epoch ms; `weekStart` is yyyy-MM-dd. */
data class LedgerResponse(
    val balance: Int,
    val weeklyAllowance: Int,
    val weeklyRemaining: Int,
    val purchasedRemaining: Int,
    val premium: Boolean,
    val weekStart: String,
    val resetsAt: Long,
    /** OUTFIT_SUGGESTION, AI_IMAGE, OUTFIT_DESCRIPTION → credits. */
    val costs: Map<String, Int>,
    val freeItemLimit: Int,
    /** What a free and a premium user get each week, so the paywall shows the same numbers as the meter. */
    val freeWeeklyCredits: Int,
    val premiumWeeklyCredits: Int,
)
