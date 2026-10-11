package com.adropofliquid.wtw.controller

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/** Every metered route returns `X-Credits-Balance: <int>` (brief 4.0). */
const val CREDITS_HEADER = "X-Credits-Balance"

fun <T : Any> okWithCredits(balance: Int, body: T, contentType: MediaType? = null): ResponseEntity<T> =
    ResponseEntity.ok().header(CREDITS_HEADER, balance.toString())
        .apply { if (contentType != null) header(HttpHeaders.CONTENT_TYPE, contentType.toString()) }
        .body(body)
