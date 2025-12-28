package com.charly.wallpapermap.location

import android.location.Location
import kotlin.math.cos
import kotlin.math.sin

object LocationPredictor {

    private var lastLocation: Location? = null

    // Si pasan más de 2s sin datos, cortamos la predicción.
    private const val MAX_PREDICTION_TIME_MS = 2000L
    private const val EARTH_RADIUS = 6378137.0

    // Ahora recibimos el objeto con toda la data (Bearing híbrido incluido)
    fun update(location: Location) {
        lastLocation = location
    }

    /**
     * Calcula dónde debería estar el usuario AHORA MISMO.
     * Retorna Pair(Lat, Lon)
     */
    fun predictLocation(currentTimeMs: Long): Pair<Double, Double> {
        val base = lastLocation ?: return (0.0 to 0.0)

        // Delta T: Tiempo desde el fix hasta ahora
        val timeDeltaMs = currentTimeMs - base.time

        if (timeDeltaMs < 0 || timeDeltaMs > MAX_PREDICTION_TIME_MS) {
            return base.latitude to base.longitude
        }

        val dtSeconds = timeDeltaMs / 1000.0
        val speed = base.speed.toDouble()

        // Si estamos casi quietos, no interpolamos para evitar drift
        if (speed < 0.1) {
            return base.latitude to base.longitude
        }

        // --- FÍSICA: Dead Reckoning ---
        // Acá sucede la magia. Si speed < 1m/s, 'base.bearing' es el de la BRÚJULA (gracias al Manager).
        // Entonces la interpolación mueve el mapa hacia donde mirás.
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

    fun reset() {
        lastLocation = null
    }
}