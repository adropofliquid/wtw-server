package com.adropofliquid.wtw.client

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** What happened to one push. DEAD_TOKEN = FCM says the token is gone; the caller deletes it (brief 3.6). */
enum class PushOutcome { SENT, DEAD_TOKEN, FAILED }

/** Sends one notification (title + body) to one FCM token. Never throws. */
fun interface PushSender {
    fun send(token: String, title: String, body: String): PushOutcome
}

/** prod: Firebase Cloud Messaging through Firebase Admin (same message shape as the old NotificationDispatchService). */
@Component
@Profile("prod")
class FcmPushSender(private val firebaseApp: FirebaseApp) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(token: String, title: String, body: String): PushOutcome {
        val start = System.nanoTime()
        return try {
            val msg = Message.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .setToken(token)
                .build()
            FirebaseMessaging.getInstance(firebaseApp).send(msg)
            PushOutcome.SENT
        } catch (e: FirebaseMessagingException) {
            val code = e.messagingErrorCode
            log.warn("upstream=fcm failed status={} ms={}", code, (System.nanoTime() - start) / 1_000_000)
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) PushOutcome.DEAD_TOKEN else PushOutcome.FAILED
        } catch (e: Exception) {
            log.warn("upstream=fcm failed status={} ms={}", e.javaClass.simpleName, (System.nanoTime() - start) / 1_000_000)
            PushOutcome.FAILED
        }
    }
}

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
