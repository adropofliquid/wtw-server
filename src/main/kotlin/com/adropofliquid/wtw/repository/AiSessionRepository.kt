package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.AiSession
import org.springframework.data.jpa.repository.JpaRepository

interface AiSessionRepository : JpaRepository<AiSession, String> {
    fun findByIdAndUserId(id: String, userId: String): AiSession?
}
