package com.adropofliquid.wtw.controller

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.WebRequest

/**
 * An image on a stable, versioned link: cached for a year and never revalidated (`immutable`), because a new
 * image gets a new `?v=`. ETag = the version; If-None-Match → 304. [private] keeps shared caches (a CDN) from
 * storing a user's own image; the phone still caches it. A request whose `v` is not the current version gets the
 * current bytes with `no-cache`. Returns null after a 304 (already written).
 */
fun imageResponse(
    req: WebRequest, res: HttpServletResponse, bytes: ByteArray, version: String, contentType: String?, private: Boolean,
): ResponseEntity<ByteArray>? {
    // A link with an old (or no) `v` still gets the current image, but must not cache it for good under that link.
    val current = req.getParameter("v") == version
    res.setHeader("Cache-Control", if (current) "${if (private) "private" else "public"}, max-age=31536000, immutable" else "no-cache")
    if (req.checkNotModified("\"$version\"")) return null
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(contentType ?: "image/webp"))
        .contentLength(bytes.size.toLong())
        .body(bytes)
}
