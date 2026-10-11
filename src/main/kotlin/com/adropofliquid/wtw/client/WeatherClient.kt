package com.adropofliquid.wtw.client

import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.dto.CurrentWeather
import com.adropofliquid.wtw.dto.DailyWeather
import com.adropofliquid.wtw.dto.WeatherForecastResponse
import com.adropofliquid.wtw.service.ApiException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/** A forecast for [days] days from today, already in the Open-Meteo shape (brief 4.8). Throws ApiException 502/504. */
fun interface WeatherClient {
    fun forecast(latitude: Double, longitude: Double, days: Int): WeatherForecastResponse
}

/**
 * prod: WeatherAPI.com `forecast.json?key=…&q={lat},{lon}&days={n}`, mapped to the Open-Meteo shape.
 * Condition codes go through [WeatherCodes.toWmo]. Dates are WeatherAPI's, which are local to the location
 * (the same as Open-Meteo's `timezone=auto` that the app used).
 */
@Component
@Profile("prod")
class WeatherApiClient(private val props: WtwProperties) : WeatherClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = RestClient.builder()
        .baseUrl("https://api.weatherapi.com")
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(Duration.ofSeconds(10)) })
        .build()

    override fun forecast(latitude: Double, longitude: Double, days: Int): WeatherForecastResponse {
        val start = System.nanoTime()
        fun ms() = (System.nanoTime() - start) / 1_000_000
        val body = try {
            http.get().uri { it.path("/v1/forecast.json")
                    .queryParam("key", props.weather.apiKey)
                    .queryParam("q", "$latitude,$longitude")
                    .queryParam("days", days)
                    .queryParam("aqi", "no").queryParam("alerts", "no").build() }
                .retrieve().body<Map<String, Any?>>() ?: throw ApiException.upstreamFailed("Weather")
        } catch (e: RestClientResponseException) {
            log.warn("upstream=weatherapi status={} ms={}", e.statusCode.value(), ms())
            throw ApiException.upstreamFailed("Weather", e)
        } catch (e: ResourceAccessException) {
            val timeout = e.cause is HttpTimeoutException || e.cause is SocketTimeoutException
            log.warn("upstream=weatherapi status={} ms={}", if (timeout) "timeout" else "io", ms())
            throw if (timeout) ApiException.upstreamTimeout("Weather", e) else ApiException.upstreamFailed("Weather", e)
        }
        return try {
            map(body)
        } catch (e: Exception) {
            log.warn("upstream=weatherapi status=bad_body ms={}", ms())
            throw ApiException.upstreamFailed("Weather", e)
        }
    }

    private fun map(body: Map<String, Any?>): WeatherForecastResponse {
        val current = body["current"] as? Map<*, *>
        val forecastDays = ((body["forecast"] as Map<*, *>)["forecastday"] as List<*>).map { it as Map<*, *> }
        fun Any?.num() = (this as? Number)?.toDouble()
        fun Map<*, *>.code() = ((this["condition"] as? Map<*, *>)?.get("code") as? Number)?.toInt()
        val dayMaps = forecastDays.map { it["day"] as Map<*, *> }
        return WeatherForecastResponse(
            current = current?.let { CurrentWeather(it["temp_c"].num(), it.code()?.let(WeatherCodes::toWmo)) },
            daily = DailyWeather(
                time = forecastDays.map { it["date"].toString() },
                weatherCode = dayMaps.map { WeatherCodes.toWmo(it.code() ?: 0) },
                temperature2mMax = dayMaps.map { it["maxtemp_c"].num() ?: 0.0 },
                temperature2mMin = dayMaps.map { it["mintemp_c"].num() ?: 0.0 },
                precipitationProbabilityMax = dayMaps.map {
                    maxOf(it["daily_chance_of_rain"].num() ?: 0.0, it["daily_chance_of_snow"].num() ?: 0.0).roundToInt()
                },
            ),
        )
    }
}

/** Not prod: no network. A canned forecast that depends only on the coordinates and today's date (UTC). */
@Component
@Profile("!prod")
class StubWeatherClient : WeatherClient {
    override fun forecast(latitude: Double, longitude: Double, days: Int): WeatherForecastResponse {
        val today = LocalDate.now(ZoneOffset.UTC)
        val base = 18.0 - abs(latitude) / 6.0
        val codes = listOf(0, 2, 3, 61, 3, 2, 45, 0, 61, 71, 95)
        val shift = abs((latitude * 7 + longitude * 3).toInt())
        val idx = (0 until days).map { (it + shift) % codes.size }
        fun r1(d: Double) = (d * 10).roundToInt() / 10.0
        return WeatherForecastResponse(
            current = CurrentWeather(r1(base), codes[idx.first()]),
            daily = DailyWeather(
                time = (0 until days).map { today.plusDays(it.toLong()).toString() },
                weatherCode = idx.map { codes[it] },
                temperature2mMax = (0 until days).map { r1(base + 3 + (it % 4)) },
                temperature2mMin = (0 until days).map { r1(base - 4 + (it % 3)) },
                precipitationProbabilityMax = idx.map { if (codes[it] >= 61) 80 else 10 * (it % 3) },
            ),
        )
    }
}

/** WeatherAPI.com condition code → WMO code (brief 4.8). Only needs to land in the app's right group. */
object WeatherCodes {
    fun toWmo(code: Int): Int = when (code) {
        1000 -> 0
        1003 -> 2
        1006, 1009 -> 3
        1030, 1135, 1147 -> 45
        1063, 1072, in 1150..1201, in 1240..1246 -> 61
        1066, 1069, 1114, 1117, in 1204..1237, in 1249..1264 -> 71
        1087, in 1273..1282 -> 95
        else -> 3
    }
}
