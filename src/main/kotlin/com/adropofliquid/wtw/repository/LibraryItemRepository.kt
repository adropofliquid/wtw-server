package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.LibraryItem
import org.springframework.data.jpa.repository.JpaRepository

interface LibraryItemRepository : JpaRepository<LibraryItem, String> {
    fun findByActiveTrue(): List<LibraryItem>
}
