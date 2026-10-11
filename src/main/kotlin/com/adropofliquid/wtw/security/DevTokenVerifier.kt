package com.adropofliquid.wtw.security

import com.adropofliquid.wtw.config.WtwProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Not prod: no Firebase. `Bearer <AUTH_DEV_TOKEN>` (default `dev-token`) signs in as `AUTH_DEV_UID` (default `dev-user`).
 * Any other user: send `X-Dev-User-Id` (dev bypass, on by default in the dev profile).
 */
@Component
@Profile("!prod")
class DevTokenVerifier(private val props: WtwProperties) : TokenVerifier {
    init {
        LoggerFactory.getLogger(javaClass).warn("DEV token verifier active: Bearer {} = uid {}. Never use in production.",
            props.auth.devToken, props.auth.devUid)
    }

    override fun verify(token: String): String? = if (token == props.auth.devToken) props.auth.devUid else null
}
