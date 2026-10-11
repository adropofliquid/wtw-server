package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.RevenueCatEvent
import org.springframework.data.jpa.repository.JpaRepository

interface RevenueCatEventRepository : JpaRepository<RevenueCatEvent, String>
