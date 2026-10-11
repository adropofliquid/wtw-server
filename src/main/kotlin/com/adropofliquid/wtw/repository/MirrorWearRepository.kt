package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorWear
import com.adropofliquid.wtw.domain.UserDateId
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

interface MirrorWearRepository : JpaRepository<MirrorWear, UserDateId> {
    fun findByUserIdAndDateIn(userId: String, dates: Collection<LocalDate>): List<MirrorWear>
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<MirrorWear>
    fun findByUserIdAndSyncedAtAfter(userId: String, since: Instant): List<MirrorWear>
    /** Habit builder: live rows with [status] for these users on these dates (one query per hourly run). */
    fun findByUserIdInAndDateInAndStatusAndDeletedAtIsNull(
        userIds: Collection<String>, dates: Collection<LocalDate>, status: String,
    ): List<MirrorWear>
    fun deleteByUserId(userId: String): Long
}
