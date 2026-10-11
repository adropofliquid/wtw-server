package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.LibraryItemResponse
import com.adropofliquid.wtw.dto.StarterItemResponse
import com.adropofliquid.wtw.service.LibraryService
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** Library routes 11–13 (brief 4.3). Routes 12 and 13 are compat routes for the app's current HttpCatalog. */
@RestController
class LibraryController(private val library: LibraryService) {

    @GetMapping("/library")
    fun all(): List<LibraryItemResponse> = library.all()

    @GetMapping("/user/onboarding/starter-items")
    fun starterItems(@AuthenticationPrincipal uid: String): List<StarterItemResponse> = library.starterItems(uid)

    /** Library images only (never user images). WebP, cached a day by clients. */
    @GetMapping("/image/{id}")
    fun image(@PathVariable id: String): ResponseEntity<ByteArray> {
        val obj = library.image(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("image/webp"))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .contentLength(obj.bytes.size.toLong())
            .body(obj.bytes)
    }
}
