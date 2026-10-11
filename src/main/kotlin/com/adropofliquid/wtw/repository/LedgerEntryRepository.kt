package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.LedgerEntry
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface LedgerEntryRepository : JpaRepository<LedgerEntry, java.util.UUID> {
    fun findByUserIdAndBucketAndWeekStart(userId: String, bucket: String, weekStart: LocalDate): List<LedgerEntry>
    fun findByUserIdAndBucket(userId: String, bucket: String): List<LedgerEntry>
    fun existsByUserIdAndReasonAndRef(userId: String, reason: String, ref: String): Boolean
    fun deleteByUserId(userId: String): Long
}
