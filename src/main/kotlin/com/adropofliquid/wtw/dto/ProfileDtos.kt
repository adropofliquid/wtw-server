package com.adropofliquid.wtw.dto

/** Matches the app's HttpProfile.ProfileRequest. Any `id` in the body is ignored (uid comes from the token). */
data class ProfileRequest(
    val displayName: String? = null,
    val vibes: List<String>? = null,
    val event: List<String>? = null,
    val style: String? = null,
    val timezone: String? = null,
)

data class ProfileResponse(
    val id: String,
    val displayName: String?,
    val vibes: List<String>,
    val event: List<String>,
    val style: String?,
    val timezone: String?,
)
