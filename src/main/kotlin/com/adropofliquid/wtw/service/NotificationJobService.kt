package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.PushOutcome
import com.adropofliquid.wtw.client.PushSender
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.domain.UserProfile
import com.adropofliquid.wtw.repository.AiSessionRepository
import com.adropofliquid.wtw.repository.MirrorWearRepository
import com.adropofliquid.wtw.repository.PushTokenRepository
import com.adropofliquid.wtw.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What one push check did (logged; also returned by the dev trigger route). */
data class PushCheckReport(val candidates: Int, val habitSent: Int, val weeklySent: Int, val deadTokensRemoved: Int, val failed: Int)

/**
 * Scheduled jobs (brief 5.5).
 * - Push check, top of every hour UTC (off when NOTIFICATIONS_ENABLED=false). Candidates: users with at least one
 *   token, a valid timezone, and no push yet today (local date of last_notified_at).
 *   Habit builder: local hour 7, and yesterday (local) the user accepted a suggestion or has a WORN wear row.
 *   Weekly reset: Sunday, local hour 19. Copy from app_config. Sends to all the user's tokens; dead tokens are deleted.
 * - Session sweep, 03:00 UTC: ACTIVE ai_session rows idle for 24 h → ABANDONED.
 */
@Service
class NotificationJobService(
    private val tokens: PushTokenRepository,
    private val profiles: UserProfileRepository,
    private val wear: MirrorWearRepository,
    private val sessions: AiSessionRepository,
    private val config: AppConfigService,
    private val sender: PushSender,
    private val props: WtwProperties,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    fun hourlyPushCheck() {
        if (!props.notifications.enabled) return
        runCatching { pushCheck(Instant.now()) }.onFailure { log.error("push check failed", it) }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    fun nightlySessionSweep() {
        runCatching { sweepSessions(Instant.now()) }.onFailure { log.error("session sweep failed", it) }
    }

    /** One push check as if the clock read [now]. Ignores NOTIFICATIONS_ENABLED (the scheduled wrapper checks it). */
    fun pushCheck(now: Instant): PushCheckReport {
        // Two queries for everyone: all tokens (grouped by user), then those users' profiles.
        val tokensByUser = tokens.findAll().groupBy { it.userId }
        if (tokensByUser.isEmpty()) return PushCheckReport(0, 0, 0, 0, 0).also { log(it) }
        val candidates = profiles.findAllById(tokensByUser.keys).mapNotNull { p ->
            val zone = p.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return@mapNotNull null
            val local = now.atZone(zone)
            if (p.lastNotifiedAt?.atZone(zone)?.toLocalDate() == local.toLocalDate()) return@mapNotNull null
            Candidate(p, local)
        }

        val habit = candidates.filter { it.local.hour == 7 }
        val weekly = candidates.filter { it.local.dayOfWeek == DayOfWeek.SUNDAY && it.local.hour == 19 }

        // Habit builder: one query for WORN rows on each candidate's local yesterday ...
        val worn = if (habit.isEmpty()) emptySet() else {
            val yesterdayOf = habit.associate { it.profile.id to it.local.toLocalDate().minusDays(1) }
            wear.findByUserIdInAndDateInAndStatusAndDeletedAtIsNull(yesterdayOf.keys, yesterdayOf.values.toSet(), "WORN")
                .filter { yesterdayOf[it.userId] == it.date }.map { it.userId }.toSet()
        }
        // ... then, only for hour-7 users without one, the accepted-suggestion check (about 1/24 of users per run).
        val habitDue = habit.filter { c ->
            c.profile.id in worn || run {
                val today = c.local.toLocalDate().atStartOfDay(c.local.zone).toInstant()
                val yesterday = c.local.toLocalDate().minusDays(1).atStartOfDay(c.local.zone).toInstant()
                sessions.existsByUserIdAndAcceptedSuggestionIdIsNotNullAndLastActivityAtGreaterThanEqualAndLastActivityAtLessThan(
                    c.profile.id, yesterday, today,
                )
            }
        }

        var dead = 0
        var failed = 0
        fun sendAll(list: List<Candidate>, configName: String, defTitle: String, defBody: String): Int {
            if (list.isEmpty()) return 0
            val title = config.string(configName, "title", defTitle)
            val body = config.string(configName, "body", defBody)
            var sent = 0
            for (c in list) {
                var any = false
                for (t in tokensByUser[c.profile.id].orEmpty()) {
                    when (sender.send(t.fcmToken, title, body)) {
                        PushOutcome.SENT -> any = true
                        PushOutcome.DEAD_TOKEN -> { tokens.deleteById(t.id); dead++ }
                        PushOutcome.FAILED -> failed++
                    }
                }
                if (any) { markNotified(c.profile.id, now); sent++ }
            }
            return sent
        }

        val report = PushCheckReport(
            candidates = candidates.size,
            habitSent = sendAll(habitDue, "habit_builder_notification", "Ready for today's outfit? ☀️", "You looked great yesterday — let's keep that energy going! 💪"),
            weeklySent = sendAll(weekly, "weekly_reset_notification", "New week, fresh fits! 🎉", "Time to put together something you'll love wearing. ✨"),
            deadTokensRemoved = dead,
            failed = failed,
        )
        log(report)
        return report
    }

    /** Marks ACTIVE sessions idle since [now] − 24 h as ABANDONED. Returns how many. */
    fun sweepSessions(now: Instant): Int {
        val n = tx.execute {
            val idle = sessions.findByStatusAndLastActivityAtBefore("ACTIVE", now.minus(Duration.ofHours(24)))
            idle.forEach { it.status = "ABANDONED"; it.updatedAt = now }
            idle.size
        }!!
        log.info("session sweep: abandoned={}", n)
        return n
    }

    private fun markNotified(uid: String, now: Instant) = tx.executeWithoutResult {
        profiles.findById(uid).ifPresent { it.lastNotifiedAt = now }
    }

    private fun log(r: PushCheckReport) = log.info(
        "push check: candidates={} habitSent={} weeklySent={} deadTokens={} failed={}",
        r.candidates, r.habitSent, r.weeklySent, r.deadTokensRemoved, r.failed,
    )

    private class Candidate(val profile: UserProfile, val local: ZonedDateTime)
}
