package com.adropofliquid.wtw.repository

import com.adropofliquid.wtw.domain.UserProfile
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface UserProfileRepository : JpaRepository<UserProfile, String> {
    /** SELECT … FOR UPDATE on the user root. Every ledger spend takes this lock first (brief 3.5). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: String): UserProfile?
}
