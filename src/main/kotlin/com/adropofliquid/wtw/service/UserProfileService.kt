package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.domain.UserProfile
import com.adropofliquid.wtw.dto.ProfileRequest
import com.adropofliquid.wtw.dto.ProfileResponse
import com.adropofliquid.wtw.repository.UserProfileRepository
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.Duration

/** Profile routes (brief 4.1) and the stub-row rule (3.0). Same behaviour as the old UserProfileService. */
@Service
class UserProfileService(private val repo: UserProfileRepository) {
    /** uids known to have a row; avoids a DB hit on every request. Evicted on account deletion. */
    private val known = Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofHours(6)).build<String, Boolean>()

    fun ensureStub(uid: String) {
        if (known.getIfPresent(uid) == true) return
        if (!repo.existsById(uid)) {
            try {
                repo.save(UserProfile(id = uid))
            } catch (_: DataIntegrityViolationException) {
                // another request made it first
            }
        }
        known.put(uid, true)
    }

    fun forget(uid: String) = known.invalidate(uid)

    @Transactional(readOnly = true)
    fun get(uid: String): ProfileResponse {
        val p = repo.findById(uid).orElse(null)
        if (p == null || p.isStub) throw ApiException.notFound("No profile yet")
        return p.toResponse()
    }

    /** POST /user/profile: fields left out stay as they are. */
    @Transactional
    fun upsert(uid: String, req: ProfileRequest): ProfileResponse = update(uid) { p ->
        req.displayName?.let { p.displayName = it }
        req.vibes?.let { p.vibes = Vocab.vibes(it) }
        req.event?.let { p.events = Vocab.occasions(it) }
        req.style?.let { p.style = it }
        req.timezone?.let { p.timezone = validZone(it) }
    }

    @Transactional
    fun update(uid: String, change: (UserProfile) -> Unit): ProfileResponse {
        val p = repo.findById(uid).orElseGet { UserProfile(id = uid) }
        change(p)
        p.updatedAt = Instant.now()
        return repo.save(p).toResponse()
    }

    fun validZone(tz: String): String = try {
        ZoneId.of(tz).id
    } catch (_: DateTimeException) {
        throw ApiException.validation("timezone is not a valid IANA zone")
    }

    fun clean(list: List<String>) = list.map { it.trim() }.filter { it.isNotEmpty() }

    fun zoneOf(uid: String): ZoneId = repo.findById(uid).orElse(null)?.timezone
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")

    /** Old words are mapped on read too (rows written before the change, e.g. by the data copy). */
    private fun UserProfile.toResponse() = ProfileResponse(id, displayName, Vocab.vibes(vibes), Vocab.occasions(events), style, timezone)
}
