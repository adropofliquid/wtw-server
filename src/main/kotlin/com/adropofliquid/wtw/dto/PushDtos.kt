package com.adropofliquid.wtw.dto

/** Routes 24–25 (brief 4.7). Matches the app's HttpPush NotificationTokenRequest. `userId` is ignored. */
data class PushTokenRequest(
    val userId: String? = null,
    val fcmToken: String? = null,
    val platform: String? = null,
)

data class PushTokenResponse(val id: String, val fcmToken: String, val platform: String)

/** Route 26: DELETE /user/notification. */
data class PushTokenDeleteRequest(val fcmToken: String? = null)
