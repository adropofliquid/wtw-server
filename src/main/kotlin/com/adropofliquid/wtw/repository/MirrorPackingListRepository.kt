package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorPackingList
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface MirrorPackingListRepository : JpaRepository<MirrorPackingList, String> {
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<MirrorPackingList>
    fun findByUserIdAndSyncedAtAfter(userId: String, since: Instant): List<MirrorPackingList>
    fun deleteByUserId(userId: String): Long
}
