package com.adropofliquid.wtw.client.push

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Not prod: no network. Logs "would send" (never the token) and succeeds.
 * A token starting with `dead-` answers DEAD_TOKEN, so the token clean-up path can be tried locally.
 */
@Component
@Profile("!prod")
class StubPushSender : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(token: String, title: String, body: String): PushOutcome {
        if (token.startsWith("dead-")) return PushOutcome.DEAD_TOKEN
        log.info("push stub: would send title=\"{}\"", title)
        return PushOutcome.SENT
    }
}
