package com.adropofliquid.wtw.client.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

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
            log.info("upstream=fcm ok ms={}", (System.nanoTime() - start) / 1_000_000)
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
