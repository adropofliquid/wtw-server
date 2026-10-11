package com.adropofliquid.wtw.client.weather

import com.adropofliquid.wtw.dto.WeatherForecastResponse
import com.adropofliquid.wtw.service.ApiException

/** A forecast for [days] days from today, already in the Open-Meteo shape (brief 4.8). Throws ApiException 502/504. */
fun interface WeatherClient {
    fun forecast(latitude: Double, longitude: Double, days: Int): WeatherForecastResponse
}
