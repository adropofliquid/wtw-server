package com.adropofliquid.wtw.dto

/** POST /feedback. Matches the app's contract (whattowear/db/IN_APP_FEEDBACK.md). Unknown fields are ignored. */
data class FeedbackRequest(
    val id: String? = null,
    val message: String? = null,
    val category: String? = null,
    val canReply: Boolean? = null,
    val email: String? = null,
    val uid: String? = null,
    val appVersion: String? = null,
    val platform: String? = null,
    val osVersion: String? = null,
    val createdAt: Long? = null,
)
