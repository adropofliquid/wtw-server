package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.domain.LedgerEntry
import com.adropofliquid.wtw.repository.LedgerEntryRepository
import com.adropofliquid.wtw.repository.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** Ledger reasons (brief 3.5). */
object Reason {
    const val WEEKLY_GRANT = "weekly_grant"
    const val PREMIUM_TOPUP = "premium_topup"
    const val PACK_PURCHASE = "pack_purchase"
    const val PACK_REFUND = "pack_refund"
    const val OUTFIT_SUGGESTION = "outfit_suggestion"
    const val AI_IMAGE = "ai_image"
    const val OUTFIT_DESCRIPTION = "outfit_description"
    const val REFUND_FAILED_CALL = "refund_failed_call"
    const val ADMIN_ADJUST = "admin_adjust"
}

data class LedgerState(
    val balance: Int,
    val weeklyAllowance: Int,
    val weeklyRemaining: Int,
    val purchasedRemaining: Int,
    val premium: Boolean,
    val weekStart: LocalDate,
    val resetsAt: Instant,
)

/** What one spend wrote, so a failed call can be refunded exactly. [charged] = false on a repeat (same ref). */
data class Charge(val ref: String, val reason: String, val weekly: Int, val purchased: Int, val charged: Boolean, val state: LedgerState)

/**
 * The credit ledger (brief 3.5). Append-only rows; balance = this week's WEEKLY rows + all PURCHASED rows.
 * - Week = Monday 00:00 in the profile's timezone (UTC if none). Lazy weekly grant on first read/spend of a week.
 * - Spend: WEEKLY first, then PURCHASED; 402 if short. Each call locks the user_profile row first.
 * - Idempotent by (user, reason, ref).
 *
 * Metered pattern (CONTRIBUTING "Add a metered action"): call [spend] (its own transaction, commits at once),
 * then the upstream call OUTSIDE any transaction, then [refund] if the call failed. Never hold a DB
 * transaction open across a Gemini call.
 */
@Service
class LedgerService(
    private val entries: LedgerEntryRepository,
    private val profiles: UserProfileRepository,
    private val entitlements: EntitlementService,
    private val config: AppConfigService,
) {
    @Transactional
    fun read(uid: String): LedgerState {
        lock(uid)
        return grantAndState(uid)
    }

    /** 402 INSUFFICIENT_CREDITS unless the balance covers [cost]. Charges nothing (suggest/start). */
    @Transactional
    fun requireBalance(uid: String, cost: Int): LedgerState {
        val state = read(uid)
        if (state.balance < cost) throw ApiException.insufficientCredits(state.balance, cost)
        return state
    }

    /**
     * Debits [cost] credits. [ref] makes it idempotent (e.g. the suggestion id); null → a fresh ref per call.
     * A repeat of the same (reason, ref) charges nothing and returns charged=false.
     */
    @Transactional
    fun spend(uid: String, reason: String, cost: Int, ref: String? = null): Charge {
        lock(uid)
        val r = ref ?: "call:${UUID.randomUUID()}"
        var state = grantAndState(uid)
        if (entries.existsByUserIdAndReasonAndRef(uid, reason, r)) return Charge(r, reason, 0, 0, false, state)
        if (cost <= 0) return Charge(r, reason, 0, 0, true, state)
        if (state.balance < cost) throw ApiException.insufficientCredits(state.balance, cost)
        val fromWeekly = minOf(cost, state.weeklyRemaining)
        val fromPurchased = cost - fromWeekly
        if (fromWeekly > 0) entries.save(LedgerEntry(userId = uid, bucket = WEEKLY, delta = -fromWeekly, reason = reason, weekStart = state.weekStart, ref = r))
        if (fromPurchased > 0) entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = -fromPurchased, reason = reason, ref = if (fromWeekly > 0) "$r#purchased" else r))
        state = state(uid, state.premium)
        return Charge(r, reason, fromWeekly, fromPurchased, true, state)
    }

    /** Gives back exactly what [charge] took (reason refund_failed_call, ref `refund:{ref}`). Safe to repeat. */
    @Transactional
    fun refund(uid: String, charge: Charge): LedgerState {
        lock(uid)
        if (!charge.charged || charge.weekly + charge.purchased == 0) return grantAndState(uid)
        val week = currentWeek(uid)
        if (charge.weekly > 0 && !entries.existsByUserIdAndReasonAndRef(uid, Reason.REFUND_FAILED_CALL, "refund:${charge.ref}")) {
            entries.save(LedgerEntry(userId = uid, bucket = WEEKLY, delta = charge.weekly, reason = Reason.REFUND_FAILED_CALL, weekStart = week, ref = "refund:${charge.ref}"))
        }
        if (charge.purchased > 0 && !entries.existsByUserIdAndReasonAndRef(uid, Reason.REFUND_FAILED_CALL, "refund:${charge.ref}#purchased")) {
            entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = charge.purchased, reason = Reason.REFUND_FAILED_CALL, ref = "refund:${charge.ref}#purchased"))
        }
        return grantAndState(uid)
    }

    /** Credits PURCHASED (pack purchase, admin). Returns false if this (reason, ref) was already applied. */
    @Transactional
    fun creditPurchased(uid: String, credits: Int, reason: String, ref: String): Boolean {
        lock(uid)
        if (entries.existsByUserIdAndReasonAndRef(uid, reason, ref)) return false
        entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = credits, reason = reason, ref = ref))
        return true
    }

    /** Debits PURCHASED by up to [credits], never below 0 (pack refund). Returns the amount debited, or -1 if repeated. */
    @Transactional
    fun debitPurchasedFloor(uid: String, credits: Int, reason: String, ref: String): Int {
        lock(uid)
        if (entries.existsByUserIdAndReasonAndRef(uid, reason, ref)) return -1
        val have = entries.findByUserIdAndBucket(uid, PURCHASED).sumOf { it.delta }.coerceAtLeast(0)
        val take = minOf(have, credits)
        if (take > 0) entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = -take, reason = reason, ref = ref))
        return take
    }

    fun currentWeek(uid: String): LocalDate = LocalDate.now(zone(uid)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun lock(uid: String) {
        profiles.findWithLockById(uid) ?: throw ApiException.notFound("No profile")
    }

    private fun zone(uid: String): ZoneId = profiles.findById(uid).orElse(null)?.timezone
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")

    /** Lazy weekly grant and mid-week premium top-up, then the numbers. Caller holds the lock. */
    private fun grantAndState(uid: String): LedgerState {
        val premium = entitlements.isPremium(uid)
        val week = currentWeek(uid)
        val cfg = config.ledger()
        val weekRows = entries.findByUserIdAndBucketAndWeekStart(uid, WEEKLY, week)
        val weekRef = "week:$week"
        if (weekRows.none { it.reason == Reason.WEEKLY_GRANT }) {
            val grant = if (premium) cfg.premiumWeeklyCredits else cfg.freeWeeklyCredits
            entries.save(LedgerEntry(userId = uid, bucket = WEEKLY, delta = grant, reason = Reason.WEEKLY_GRANT, weekStart = week, ref = weekRef))
        } else if (premium) {
            val granted = weekRows.filter { it.reason == Reason.WEEKLY_GRANT || it.reason == Reason.PREMIUM_TOPUP }.sumOf { it.delta }
            val gap = cfg.premiumWeeklyCredits - granted
            if (gap > 0 && weekRows.none { it.reason == Reason.PREMIUM_TOPUP }) {
                entries.save(LedgerEntry(userId = uid, bucket = WEEKLY, delta = gap, reason = Reason.PREMIUM_TOPUP, weekStart = week, ref = "topup:$week"))
            }
        }
        return state(uid, premium)
    }

    private fun state(uid: String, premium: Boolean): LedgerState {
        val zone = zone(uid)
        val week = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekRows = entries.findByUserIdAndBucketAndWeekStart(uid, WEEKLY, week)
        val weekly = weekRows.sumOf { it.delta }.coerceAtLeast(0)
        val allowance = weekRows.filter { it.reason == Reason.WEEKLY_GRANT || it.reason == Reason.PREMIUM_TOPUP }.sumOf { it.delta }
        val purchased = entries.findByUserIdAndBucket(uid, PURCHASED).sumOf { it.delta }.coerceAtLeast(0)
        return LedgerState(
            balance = weekly + purchased,
            weeklyAllowance = allowance,
            weeklyRemaining = weekly,
            purchasedRemaining = purchased,
            premium = premium,
            weekStart = week,
            resetsAt = week.plusWeeks(1).atStartOfDay(zone).toInstant(),
        )
    }

    companion object {
        const val WEEKLY = "WEEKLY"
        const val PURCHASED = "PURCHASED"
    }
}
