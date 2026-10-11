package com.adropofliquid.wtw.security

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** prod: verifies a Firebase ID token (no revocation check, same as the old server). */
@Component
@Profile("prod")
class FirebaseTokenVerifier(app: FirebaseApp) : TokenVerifier {
    private val log = LoggerFactory.getLogger(javaClass)
    private val auth = FirebaseAuth.getInstance(app)

    override fun verify(token: String): String? = try {
        auth.verifyIdToken(token).uid
    } catch (e: Exception) {
        log.debug("Token rejected: {}", e.javaClass.simpleName)
        null
    }
}
