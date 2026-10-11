package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.dto.WeatherForecastResponse
import com.adropofliquid.wtw.service.WeatherService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Route 27 (brief 4.8): Open-Meteo-shaped forecast, so the app's parser stays unchanged. */
@RestController
class WeatherController(private val weather: WeatherService) {
    @GetMapping("/weather/forecast")
    fun forecast(
        @RequestParam(required = false) latitude: Double?,
        @RequestParam(required = false) longitude: Double?,
        @RequestParam(required = false) days: Int?,
    ): WeatherForecastResponse = weather.forecast(latitude, longitude, days)
}
