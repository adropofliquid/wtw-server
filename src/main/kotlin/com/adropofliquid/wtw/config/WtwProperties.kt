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
    /** The API's public address, used to build image links (e.g. https://api-test.whattowearapp.com). */
    val publicBaseUrl: String = "http://localhost:8080",
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
        val vertexLocation: String = "global",
        val modelSuggest: String = "gemini-2.5-flash",
        val modelAttributes: String = "gemini-2.5-flash-lite",
        val modelImage: String = "gemini-3.1-flash-lite-image", // 2.5-flash-image was shut down 2026-10-02
        val timeoutMs: Long = 600_000,
        /** The image call (/ai/generate-image) alone; set per request. */
        val imageTimeoutMs: Long = 600_000,
        /** Not prod only: `stub` (canned) or `local` (LocalModelGeminiClient). Prod always uses Vertex. */
        val mode: String = "stub",
        val localModelUrl: String = "http://localhost:11434",
        val localModelName: String = "qwen2.5vl:3b",
    )

    /** mode: http (real rembg service) or stub (no network). */
    /**
     * `concurrency`: calls in flight to rembg at once (it works through one photo at a time). Further calls wait
     * in line up to `queueWaitMs`, then get 504. `timeoutMs` covers one call, not the wait.
     */
    data class Rembg(
        val mode: String = "http",
        val url: String = "http://localhost:8000/rembg",
        val timeoutMs: Long = 60_000,
        val concurrency: Int = 1,
        val queueWaitMs: Long = 180_000,
    )

    /** mode: s3 (S3 / R2 / MinIO) or local (filesystem, dev). */
    data class Storage(
        val mode: String = "s3",
        val endpoint: String = "",
        val region: String = "auto",
        val accessKey: String = "",
        val secretKey: String = "",
        val bucketUser: String = "wtw-user",
        val bucketLibrary: String = "wtw-library",
        val localDir: String = ".local-storage",
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
