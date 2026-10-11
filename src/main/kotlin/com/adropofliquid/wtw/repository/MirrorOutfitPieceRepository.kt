package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorOutfitPiece
import com.adropofliquid.wtw.domain.OutfitPieceId
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorOutfitPieceRepository : JpaRepository<MirrorOutfitPiece, OutfitPieceId> {
    /** Data copy: the current pieces of these outfits. */
    fun findByOutfitIdIn(outfitIds: Collection<String>): List<MirrorOutfitPiece>
    fun countByUserId(userId: String): Long
}
