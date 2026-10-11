package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorPackingList
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorPackingListRepository : JpaRepository<MirrorPackingList, String>
