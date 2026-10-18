package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.LibraryItemResponse
import com.adropofliquid.wtw.dto.StarterItemResponse
import com.adropofliquid.wtw.service.LibraryService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest

/** Library routes 11–13 (brief 4.3). Route 12 is a compat route for the app's current HttpCatalog. */
@RestController
class LibraryController(private val library: LibraryService) {

    /** ETag over the active rows; If-None-Match → 304 with no body. */
    @GetMapping("/library")
    fun all(req: WebRequest): List<LibraryItemResponse>? =
        if (req.checkNotModified(library.listEtag())) null else library.all()

    @GetMapping("/user/onboarding/starter-items")
    fun starterItems(@AuthenticationPrincipal uid: String): List<StarterItemResponse> = library.starterItems(uid)

    /** Library images only (never user images). The link carries `?v=`; see [imageResponse]. */
    @GetMapping("/image/{id}")
    fun image(@PathVariable id: String, req: WebRequest, res: HttpServletResponse): ResponseEntity<ByteArray>? {
        val img = library.image(id)
        return imageResponse(req, res, img.bytes, img.version, "image/webp", private = false)
    }
}
