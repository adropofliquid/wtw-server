package com.adropofliquid.wtw.client.weather

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
import kotlin.math.roundToInt

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
