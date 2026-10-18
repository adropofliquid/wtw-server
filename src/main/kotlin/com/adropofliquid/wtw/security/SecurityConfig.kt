package com.adropofliquid.wtw.security

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.service.UserProfileService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless. Public: `/health`, `/webhooks/revenuecat` (own shared-secret check).
 * Web only: one-off commands (`--seed-library`, `--data-copy`) start without a web server and need no filter chain.
 */
@Configuration
@ConditionalOnWebApplication
class SecurityConfig {
    @Bean
    fun filterChain(http: HttpSecurity, verifier: TokenVerifier, profiles: UserProfileService, props: WtwProperties): SecurityFilterChain {
        if (props.auth.devBypassEnabled) {
            LoggerFactory.getLogger(javaClass).warn("AUTH_DEV_BYPASS_ENABLED=true: X-Dev-User-Id is accepted. Never in production.")
        }
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/health", "/webhooks/revenuecat", "/error").permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ ->
                    res.status = HttpStatus.UNAUTHORIZED.value()
                    res.contentType = "application/json"
                    res.writer.write("""{"error":"Sign-in required","code":"UNAUTHENTICATED"}""")
                }
            }
            .addFilterBefore(AuthFilter(verifier, profiles, props.auth.devBypassEnabled), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
