package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.MirrorOutfit
import org.springframework.data.jpa.repository.JpaRepository

interface MirrorOutfitRepository : JpaRepository<MirrorOutfit, String>
