package com.adropofliquid.wtw.client.weather

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
