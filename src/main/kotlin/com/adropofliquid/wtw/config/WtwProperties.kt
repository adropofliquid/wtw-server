package com.adropofliquid.wtw.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Every environment setting, bound from `application.yml` (`wtw.*`), which maps each one to its env var.
 * Live numbers (credits, costs, caps, notification copy) are NOT here: they are in `app_config` (AppConfigService).
 */
@ConfigurationProperties("wtw")
data class WtwProperties(
    val databaseUrl: String,
    val dbPoolSize: Int = 10,
    val auth: Auth = Auth(),
    val ai: Ai = Ai(),
    val rembg: Rembg = Rembg(),
    val storage: Storage = Storage(),
    val image: Image = Image(),
    val revenuecat: RevenueCat = RevenueCat(),
    val weather: Weather = Weather(),
    val mirror: Mirror = Mirror(),
    val notifications: Notifications = Notifications(),
    val library: Library = Library(),
    val old: Old = Old(),
) {
    data class Auth(
        val devBypassEnabled: Boolean = false,
        val firebaseCredentialsPath: String = "",
        val devToken: String = "dev-token",
        val devUid: String = "dev-user",
    )

    data class Ai(
        val vertexProjectId: String = "",
        val vertexLocation: String = "us-central1",
        val modelSuggest: String = "gemini-2.5-flash",
        val modelAttributes: String = "gemini-2.5-flash-lite",
        val modelImage: String = "gemini-2.5-flash-image",
        val timeoutMs: Long = 60_000,
    )

    data class Rembg(val url: String = "http://localhost:8000/rembg", val timeoutMs: Long = 60_000)

    data class Storage(
        val endpoint: String = "",
        val region: String = "auto",
        val accessKey: String = "",
        val secretKey: String = "",
        val bucketUser: String = "wtw-user",
        val bucketLibrary: String = "wtw-library",
        val libraryPublicUrl: String = "",
        val presignTtl: Duration = Duration.ofDays(7),
        val localDir: String = ".local-storage",
        val localBaseUrl: String = "http://localhost:8080",
    )

    data class Image(val maxSide: Int = 600, val webpQuality: Int = 80, val uploadMaxBytes: Long = 10L * 1024 * 1024)

    data class RevenueCat(
        val apiKey: String = "",
        val projectId: String = "",
        val entitlementId: String = "",
        val entitlementIdentifier: String = "Pro",
        val webhookAuth: String = "",
        val cacheTtl: Duration = Duration.ofMinutes(15),
        val devPremiumUids: String = "",
    )

    data class Weather(val apiKey: String = "", val maxDays: Int = 14, val cacheTtl: Duration = Duration.ofMinutes(30))
    data class Mirror(val maxRowsPerPush: Int = 1000)
    data class Notifications(val enabled: Boolean = true)
    data class Library(val seedDir: String = "")
    data class Old(
        val dbUrl: String = "",
        val dbUser: String = "",
        val dbPass: String = "",
        val s3AccessKey: String = "",
        val s3Secret: String = "",
        val s3Region: String = "eu-west-2",
    )
}
