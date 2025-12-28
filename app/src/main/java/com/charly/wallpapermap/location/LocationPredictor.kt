package com.charly.wallpapermap.location

import android.location.Location
import kotlin.math.cos
import kotlin.math.sin

object LocationPredictor {

    private var lastLocation: Location? = null

    // ... (resto de constantes y métodos igual que antes) ...
    private const val MAX_PREDICTION_TIME_MS = 2000L
    private const val EARTH_RADIUS = 6378137.0

    fun update(location: Location) {
        lastLocation = location
    }

    // ... (método predictLocation igual que antes) ...
    fun predictLocation(currentTimeMs: Long): Pair<Double, Double> {
        // ... (Tu lógica de Dead Reckoning que ya funciona bien) ...
        // (Copiala del anterior o dejala como está, solo agregamos el método de abajo)
        val base = lastLocation ?: return (0.0 to 0.0)

        val timeDeltaMs = currentTimeMs - base.time
        if (timeDeltaMs < 0 || timeDeltaMs > MAX_PREDICTION_TIME_MS) {
            return base.latitude to base.longitude
        }
        val dtSeconds = timeDeltaMs / 1000.0
        val speed = base.speed.toDouble()
        if (speed < 0.1) return base.latitude to base.longitude

        val distanceMeters = speed * dtSeconds
        val bearingRad = Math.toRadians(base.bearing.toDouble())
        val latRad = Math.toRadians(base.latitude)
        val nextLatRad = latRad + (distanceMeters * cos(bearingRad)) / EARTH_RADIUS
        val nextLonRad = Math.toRadians(base.longitude) + (distanceMeters * sin(bearingRad)) / (EARTH_RADIUS * cos(latRad))
        return Math.toDegrees(nextLatRad) to Math.toDegrees(nextLonRad)
    }

    fun getLastKnown(): Pair<Double, Double>? {
        return lastLocation?.let { it.latitude to it.longitude }
    }

    // NUEVO: Devuelve la precisión en metros (o 0 si no hay dato)
    fun getLastAccuracy(): Float {
        return lastLocation?.accuracy ?: 0f
    }

    fun reset() {
        lastLocation = null
    }
}