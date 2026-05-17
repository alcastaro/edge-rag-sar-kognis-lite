package io.kognis.tactical.core.map

import java.util.Locale
import kotlin.math.*

object GeoUtils {

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    // Locale.US on every number that may reach the LLM prompt — Spanish locale produces
    // comma decimals ("0,845") which the model parses as 0845 = 845 m → corrupts to 8445 m
    // when it re-renders distance. Reproduced 2026-05-17: actual 845 m → output 8445 m.
    fun formatDistance(km: Double): String = when {
        km < 1.0  -> String.format(Locale.US, "%.0f m", km * 1000)
        km < 10.0 -> String.format(Locale.US, "%.1f km", km)
        else      -> String.format(Locale.US, "%.0f km", km)
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val deg = (Math.toDegrees(atan2(y, x)) + 360) % 360
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(deg / 45).toInt() % 8]
    }

    fun distanceLabel(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val km = haversineKm(fromLat, fromLon, toLat, toLon)
        val dir = bearing(fromLat, fromLon, toLat, toLon)
        return "${formatDistance(km)} $dir"
    }
}
