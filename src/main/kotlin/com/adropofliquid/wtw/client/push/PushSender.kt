package com.adropofliquid.wtw.client.push

/** What happened to one push. DEAD_TOKEN = FCM says the token is gone; the caller deletes it (brief 3.6). */
enum class PushOutcome { SENT, DEAD_TOKEN, FAILED }

/** Sends one notification (title + body) to one FCM token. Never throws. */
fun interface PushSender {
    fun send(token: String, title: String, body: String): PushOutcome
}
