package com.adropofliquid.wtw.security

/** Checks a bearer token and returns the uid, or null if the token is not valid. */
fun interface TokenVerifier {
    fun verify(token: String): String?
}
