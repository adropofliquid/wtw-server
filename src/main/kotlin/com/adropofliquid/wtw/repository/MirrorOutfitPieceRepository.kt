package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorOutfitPiece
import com.adropofliquid.wtw.domain.OutfitPieceId
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorOutfitPieceRepository : JpaRepository<MirrorOutfitPiece, OutfitPieceId> {
    fun findByOutfitIdIn(outfitIds: Collection<String>): List<MirrorOutfitPiece>
    fun deleteByUserId(userId: String): Long
    /** Data copy (7.9): per-user count. */
    fun countByUserId(userId: String): Long
}
