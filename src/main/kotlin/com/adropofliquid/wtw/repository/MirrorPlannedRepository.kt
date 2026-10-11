package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorPlanned
import com.adropofliquid.wtw.domain.UserDateId
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate

interface MirrorPlannedRepository : JpaRepository<MirrorPlanned, UserDateId> {
    fun findByUserIdAndDateIn(userId: String, dates: Collection<LocalDate>): List<MirrorPlanned>
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<MirrorPlanned>
    fun findByUserIdAndSyncedAtAfter(userId: String, since: Instant): List<MirrorPlanned>
    fun deleteByUserId(userId: String): Long
}
