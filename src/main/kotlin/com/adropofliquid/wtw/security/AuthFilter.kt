package com.adropofliquid.wtw.security

import com.adropofliquid.wtw.service.UserProfileService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Bearer token → uid principal (brief 4.0). With the dev bypass on, `X-Dev-User-Id` also works.
 * On first sight of a uid it makes the stub `user_profile` row. No token → unauthenticated → 401 from SecurityConfig.
 * Controllers read the uid with `@AuthenticationPrincipal uid: String`.
 */
class AuthFilter(
    private val verifier: TokenVerifier,
    private val profiles: UserProfileService,
    private val devBypass: Boolean,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val header = req.getHeader("Authorization")
        val uid = when {
            header != null && header.startsWith("Bearer ", ignoreCase = true) -> verifier.verify(header.substring(7).trim())
            devBypass -> req.getHeader("X-Dev-User-Id")?.takeIf { it.isNotBlank() && it.length <= 128 }
            else -> null
        }
        if (uid != null) {
            profiles.ensureStub(uid)
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(uid, null, emptyList())
            req.setAttribute(UID_ATTRIBUTE, uid)
        }
        chain.doFilter(req, res)
    }

    companion object { const val UID_ATTRIBUTE = "wtw.uid" }
}
