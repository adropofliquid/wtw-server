package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.WeatherClient
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.dto.WeatherForecastResponse
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Weather proxy (brief 4.8, route 27). Validates the query, caps `days` at WEATHER_MAX_DAYS, and caches
 * answers for WEATHER_CACHE_TTL keyed by lat/lon rounded to 2 decimals + days. Upstream errors are not cached.
 */
@Service
class WeatherService(private val client: WeatherClient, private val props: WtwProperties) {
    private val cache = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(props.weather.cacheTtl)
        .build<String, WeatherForecastResponse>()

    fun forecast(latitude: Double?, longitude: Double?, days: Int?): WeatherForecastResponse {
        if (latitude == null || longitude == null) throw ApiException.validation("latitude and longitude are required")
        if (latitude.isNaN() || latitude !in -90.0..90.0) throw ApiException.validation("latitude must be between -90 and 90")
        if (longitude.isNaN() || longitude !in -180.0..180.0) throw ApiException.validation("longitude must be between -180 and 180")
        val n = (days ?: DEFAULT_DAYS).coerceIn(1, props.weather.maxDays.coerceAtLeast(1))
        val lat = round2(latitude)
        val lon = round2(longitude)
        return cache.get("$lat,$lon,$n") { client.forecast(lat, lon, n) }
    }

    private fun round2(d: Double) = BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).toDouble()

    companion object { const val DEFAULT_DAYS = 16 }
}
