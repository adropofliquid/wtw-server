package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorOutfit
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface MirrorOutfitRepository : JpaRepository<MirrorOutfit, String> {
    fun findByUserIdAndDeletedAtIsNull(userId: String): List<MirrorOutfit>
    fun findByUserIdAndSyncedAtAfter(userId: String, since: Instant): List<MirrorOutfit>
    fun deleteByUserId(userId: String): Long
}
