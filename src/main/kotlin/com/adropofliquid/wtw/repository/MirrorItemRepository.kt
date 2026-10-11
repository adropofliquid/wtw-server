package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorItem
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface MirrorItemRepository : JpaRepository<MirrorItem, String> {
    fun findByIdAndUserId(id: String, userId: String): MirrorItem?
    /** Pull with since=0 (restore): live rows only. */
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<MirrorItem>
    /** Pull with since>0: every row written after `since`, tombstones included. */
    fun findByUserIdAndSyncedAtAfter(userId: String, since: Instant): List<MirrorItem>
    fun deleteByUserId(userId: String): Long
    /** Data copy (7.9): per-user count. */
    fun countByUserId(userId: String): Long
}
