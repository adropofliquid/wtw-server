package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorOutfit
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorOutfitRepository : JpaRepository<MirrorOutfit, String> {
    /** Data copy (7.9): per-user count. */
    fun countByUserId(userId: String): Long
}
