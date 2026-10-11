package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorPackingItem
import com.adropofliquid.wtw.domain.PackingItemId
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorPackingItemRepository : JpaRepository<MirrorPackingItem, PackingItemId>
