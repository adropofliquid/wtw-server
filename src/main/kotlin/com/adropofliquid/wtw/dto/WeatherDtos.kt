package com.adropofliquid.wtw.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * GET /weather/forecast (brief 4.8, route 27). Open-Meteo shape, field names exactly as the app's
 * `WeatherRepository.parse` reads them. Daily lists are parallel (one entry per day).
 */
data class WeatherForecastResponse(
    val current: CurrentWeather?,
    val daily: DailyWeather,
)

data class CurrentWeather(
    @JsonProperty("temperature_2m") val temperature2m: Double?,
    @JsonProperty("weather_code") val weatherCode: Int?,
)

data class DailyWeather(
    val time: List<String>,
    @JsonProperty("weather_code") val weatherCode: List<Int>,
    @JsonProperty("temperature_2m_max") val temperature2mMax: List<Double>,
    @JsonProperty("temperature_2m_min") val temperature2mMin: List<Double>,
    @JsonProperty("precipitation_probability_max") val precipitationProbabilityMax: List<Int>,
)
