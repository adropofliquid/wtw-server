package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.*
import com.adropofliquid.wtw.service.SuggestionService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** Routes 17–20 (brief 4.4): one-at-a-time suggestion sessions. Every response carries X-Credits-Balance. */
@RestController
@RequestMapping("/outfit/suggest")
class SuggestionController(private val suggestions: SuggestionService) {

    @PostMapping("/start")
    fun start(@AuthenticationPrincipal uid: String, @RequestBody req: SuggestionRequest): ResponseEntity<SuggestionResponse> =
        suggestions.start(uid, req).let { okWithCredits(it.balance, it.body) }

    @PostMapping("/next")
    fun next(@AuthenticationPrincipal uid: String, @RequestBody req: NextRequest): ResponseEntity<SuggestionResponse> =
        suggestions.next(uid, req).let { okWithCredits(it.balance, it.body) }

    @PostMapping("/refine")
    fun refine(@AuthenticationPrincipal uid: String, @RequestBody req: RefineRequest): ResponseEntity<SuggestionResponse> =
        suggestions.refine(uid, req).let { okWithCredits(it.balance, it.body) }

    /** Records the accept; charges nothing (the fee is taken at start). {charged: false, balance}. */
    @PostMapping("/accept")
    fun accept(@AuthenticationPrincipal uid: String, @RequestBody req: AcceptRequest): ResponseEntity<AcceptResponse> =
        suggestions.accept(uid, req).let { okWithCredits(it.balance, it) }
}
