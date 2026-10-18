package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.ProfileRequest
import com.adropofliquid.wtw.dto.ProfileResponse
import com.adropofliquid.wtw.service.ApiException
import com.adropofliquid.wtw.service.UserProfileService
import com.adropofliquid.wtw.service.Vocab
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** Routes 1–7 (brief 4.1). Shapes match the app's HttpProfile exactly. Routes 3–7 create the row if missing. */
@RestController
@RequestMapping("/user/profile")
class ProfileController(private val profiles: UserProfileService) {

    @PostMapping
    fun upsert(@AuthenticationPrincipal uid: String, @RequestBody req: ProfileRequest): ProfileResponse = profiles.upsert(uid, req)

    @GetMapping
    fun get(@AuthenticationPrincipal uid: String): ProfileResponse = profiles.get(uid)

    @PatchMapping("/name")
    fun name(@AuthenticationPrincipal uid: String, @RequestBody req: ProfileRequest): ProfileResponse =
        profiles.update(uid) { it.displayName = req.displayName }

    @PutMapping("/vibes")
    fun vibes(@AuthenticationPrincipal uid: String, @RequestBody req: ProfileRequest): ProfileResponse =
        profiles.update(uid) { it.vibes = Vocab.vibes(req.vibes ?: throw ApiException.validation("vibes is required")) }

    @PutMapping("/event")
    fun event(@AuthenticationPrincipal uid: String, @RequestBody req: ProfileRequest): ProfileResponse =
        profiles.update(uid) { it.events = Vocab.occasions(req.event ?: throw ApiException.validation("event is required")) }

    @PatchMapping("/style")
    fun style(@AuthenticationPrincipal uid: String, @RequestBody req: ProfileRequest): ProfileResponse =
        profiles.update(uid) { it.style = req.style }

    @PatchMapping("/timezone")
    fun timezone(@AuthenticationPrincipal uid: String, @RequestBody req: ProfileRequest): ProfileResponse {
        val tz = profiles.validZone(req.timezone ?: throw ApiException.validation("timezone is required"))
        return profiles.update(uid) { it.timezone = tz }
    }
}
