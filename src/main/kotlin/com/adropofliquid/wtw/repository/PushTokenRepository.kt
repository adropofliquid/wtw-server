package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.PushToken
import org.springframework.data.jpa.repository.JpaRepository

interface PushTokenRepository : JpaRepository<PushToken, java.util.UUID> {
    fun findByFcmToken(fcmToken: String): PushToken?
    fun findByUserId(userId: String): List<PushToken>
}
