package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorWear
import com.adropofliquid.wtw.domain.UserDateId
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorWearRepository : JpaRepository<MirrorWear, UserDateId>
