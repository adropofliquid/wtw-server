package com.adropofliquid.wtw.client.weather

import com.adropofliquid.wtw.dto.CurrentWeather
import com.adropofliquid.wtw.dto.DailyWeather
import com.adropofliquid.wtw.dto.WeatherForecastResponse
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

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
