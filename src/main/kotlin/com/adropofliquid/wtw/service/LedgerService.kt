package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.domain.LedgerEntry
import com.adropofliquid.wtw.repository.LedgerEntryRepository
import com.adropofliquid.wtw.repository.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
    /** Flat fee per suggestion session, charged at start; ref = session id. */
    const val OUTFIT_SUGGESTION = "outfit_suggestion"
    /** ref = `idem:{Idempotency-Key}` when the app sends one, else `call:{uuid}`. Same for OUTFIT_DESCRIPTION. */
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
 *
 * Premium is resolved BEFORE the transaction opens (cache, then RevenueCat with its timeout), so a slow
 * RevenueCat never holds a pooled connection or the profile row lock. Each method then runs its DB work in
 * [tx] (REQUIRED: it joins a caller's transaction, like @Transactional did). Do not call the ledger from
 * inside your own transaction: the RevenueCat lookup would then run inside it.
 */
@Service
class LedgerService(
    private val entries: LedgerEntryRepository,
    private val profiles: UserProfileRepository,
    private val entitlements: EntitlementService,
    private val config: AppConfigService,
    txManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(txManager)

    fun read(uid: String): LedgerState {
        val premium = entitlements.isPremium(uid)
        return tx.execute {
            lock(uid)
            grantAndState(uid, premium)
        }!!
    }

    /** 402 INSUFFICIENT_CREDITS unless the balance covers [cost]. Charges nothing (suggest/start). */
    fun requireBalance(uid: String, cost: Int): LedgerState {
        val premium = entitlements.isPremium(uid)
        return tx.execute {
            lock(uid)
            val state = grantAndState(uid, premium)
            if (state.balance < cost) throw ApiException.insufficientCredits(state.balance, cost)
            state
        }!!
    }

    /**
     * Debits [cost] credits. [ref] makes it idempotent (e.g. the suggestion id); null → a fresh ref per call.
     * A repeat of the same (reason, ref) charges nothing and returns charged=false.
     */
    fun spend(uid: String, reason: String, cost: Int, ref: String? = null): Charge {
        val premium = entitlements.isPremium(uid)
        return tx.execute { spendLocked(uid, reason, cost, ref, premium) }!!
    }

    private fun spendLocked(uid: String, reason: String, cost: Int, ref: String?, premium: Boolean): Charge {
        lock(uid)
        val r = ref ?: "call:${UUID.randomUUID()}"
        var state = grantAndState(uid, premium)
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
    fun refund(uid: String, charge: Charge): LedgerState {
        val premium = entitlements.isPremium(uid)
        return tx.execute { refundLocked(uid, charge, premium) }!!
    }

    private fun refundLocked(uid: String, charge: Charge, premium: Boolean): LedgerState {
        lock(uid)
        if (!charge.charged || charge.weekly + charge.purchased == 0) return grantAndState(uid, premium)
        val week = currentWeek(uid)
        if (charge.weekly > 0 && !entries.existsByUserIdAndReasonAndRef(uid, Reason.REFUND_FAILED_CALL, "refund:${charge.ref}")) {
            entries.save(LedgerEntry(userId = uid, bucket = WEEKLY, delta = charge.weekly, reason = Reason.REFUND_FAILED_CALL, weekStart = week, ref = "refund:${charge.ref}"))
        }
        if (charge.purchased > 0 && !entries.existsByUserIdAndReasonAndRef(uid, Reason.REFUND_FAILED_CALL, "refund:${charge.ref}#purchased")) {
            entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = charge.purchased, reason = Reason.REFUND_FAILED_CALL, ref = "refund:${charge.ref}#purchased"))
        }
        return grantAndState(uid, premium)
    }

    /** Credits PURCHASED (pack purchase, admin). Returns false if this (reason, ref) was already applied. */
    fun creditPurchased(uid: String, credits: Int, reason: String, ref: String): Boolean = tx.execute {
        lock(uid)
        if (entries.existsByUserIdAndReasonAndRef(uid, reason, ref)) return@execute false
        entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = credits, reason = reason, ref = ref))
        true
    }!!

    /** Debits PURCHASED by up to [credits], never below 0 (pack refund). Returns the amount debited, or -1 if repeated. */
    fun debitPurchasedFloor(uid: String, credits: Int, reason: String, ref: String): Int = tx.execute {
        lock(uid)
        if (entries.existsByUserIdAndReasonAndRef(uid, reason, ref)) return@execute -1
        val have = entries.findByUserIdAndBucket(uid, PURCHASED).sumOf { it.delta }.coerceAtLeast(0)
        val take = minOf(have, credits)
        if (take > 0) entries.save(LedgerEntry(userId = uid, bucket = PURCHASED, delta = -take, reason = reason, ref = ref))
        take
    }!!

    fun currentWeek(uid: String): LocalDate = LocalDate.now(zone(uid)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun lock(uid: String) {
        profiles.findWithLockById(uid) ?: throw ApiException.notFound("No profile")
    }

    private fun zone(uid: String): ZoneId = profiles.findById(uid).orElse(null)?.timezone
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")

    /** Lazy weekly grant and mid-week premium top-up, then the numbers. Caller holds the lock; [premium] was resolved before it. */
    private fun grantAndState(uid: String, premium: Boolean): LedgerState {
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
