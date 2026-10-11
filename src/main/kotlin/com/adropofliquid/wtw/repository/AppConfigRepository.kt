package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.AppConfigEntry
import org.springframework.data.jpa.repository.JpaRepository

interface AppConfigRepository : JpaRepository<AppConfigEntry, String> {
    fun findByName(name: String): AppConfigEntry?
}
