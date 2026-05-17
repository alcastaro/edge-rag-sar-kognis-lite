package io.kognis.tactical.core

import android.util.Log
import java.io.File

object ThermalGovernor {
    private const val TAG = "ThermalGovernor"
    private const val THERMAL_DIR = "/sys/class/thermal"

    /**
     * Attempts to read CPU/SoC thermal zones and returns the max temperature in Celsius.
     * Returns null if it cannot read any thermal zones.
     */
    fun getCpuTemperature(): Double? {
        val thermalDir = File(THERMAL_DIR)
        if (!thermalDir.exists() || !thermalDir.isDirectory) {
            Log.w(TAG, "Thermal directory does not exist or is not a directory.")
            return null
        }

        val temperatures = mutableListOf<Double>()
        try {
            val zones = thermalDir.listFiles { file -> file.name.startsWith("thermal_zone") } ?: return null

            for (zone in zones) {
                val tempFile = File(zone, "temp")
                val typeFile = File(zone, "type")

                if (tempFile.exists() && tempFile.canRead()) {
                    val rawTemp = tempFile.readText().trim().toLongOrNull()
                    val type = if (typeFile.exists() && typeFile.canRead()) typeFile.readText().trim() else "unknown"

                    // Ignore non-CPU/SoC related zones if we want to be strict,
                    // but for a general thermal view, we can just look at anything that gets hot.
                    // usually battery is handle separately. Let's just track everything that is a valid temp.
                    if (rawTemp != null && rawTemp > 0) {
                        // Some devices report in millidegrees Celsius, some in degrees.
                        // Assuming CPU idle temps are rarely < 10°C. If rawTemp > 1000, we assume millidegrees.
                        val tempCelsius = if (rawTemp > 1000) rawTemp / 1000.0 else rawTemp.toDouble()
                        
                        // Ignore absurd values (e.g., > 150°C which might be uncalibrated sensors)
                        if (tempCelsius in 10.0..150.0) {
                            temperatures.add(tempCelsius)
                            // Log.d(TAG, "Zone $type -> $tempCelsius °C")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading thermal zones: \${e.message}")
        }

        return if (temperatures.isNotEmpty()) {
            temperatures.maxOrNull()
        } else {
            null
        }
    }

    // Soft limit: above this the model runs hot enough to throttle CPU freq.
    // Hard limit: above HARD_LIMIT_C the query is rejected to protect the device.
    private const val SOFT_LIMIT_C = 80.0
    private const val HARD_LIMIT_C = 85.0

    /** True when temperature exceeds the hard thermal limit and inference should be blocked. */
    fun isOverheating(): Boolean = (getCpuTemperature() ?: 0.0) >= HARD_LIMIT_C

    /** Current thermal status string for UI display. */
    fun statusLabel(temp: Double?): String = when {
        temp == null   -> "—"
        temp >= HARD_LIMIT_C -> "⚠️ ${"%.0f".format(temp)}°C LIMITE"
        temp >= SOFT_LIMIT_C -> "${"%.0f".format(temp)}°C ↑"
        else           -> "${"%.0f".format(temp)}°C"
    }
}
