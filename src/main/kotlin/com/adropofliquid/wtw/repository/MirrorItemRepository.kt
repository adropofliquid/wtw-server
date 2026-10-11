package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorItem
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorItemRepository : JpaRepository<MirrorItem, String> {
    fun findByIdAndUserId(id: String, userId: String): MirrorItem?
    /** Data copy (7.9): per-user count. */
    fun countByUserId(userId: String): Long
}
