package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorPlanned
import com.adropofliquid.wtw.domain.UserDateId
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorPlannedRepository : JpaRepository<MirrorPlanned, UserDateId> {
    fun findByUserId(userId: String): List<MirrorPlanned>
    fun countByUserId(userId: String): Long
}
