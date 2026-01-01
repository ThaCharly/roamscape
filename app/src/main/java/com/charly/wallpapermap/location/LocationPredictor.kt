package com.charly.wallpapermap.location

import android.location.Location
import kotlin.math.cos

object LocationPredictor {

    private var lastLocation: Location? = null

    // Cacheamos el factor de escala
    private var longitudeScaleFactor: Double = 1.0

    private const val MAX_PREDICTION_TIME_MS = 2000L
    private const val DEGREES_PER_METER_LAT = 1.0 / 111132.0

    fun update(location: Location) {
        lastLocation = location
        // Optimizacion: Pre-calcular coseno
        longitudeScaleFactor = cos(Math.toRadians(location.latitude))
    }

    /**
     * Predice la ubicación actual basada en velocidad y tiempo.
     * @param currentTimeMs Tiempo actual.
     * @param outResult Array de Double de tamaño 2 donde se escribirá [Lat, Lon].
     * Esto evita crear objetos Pair en cada frame (Zero-Allocation).
     */
    fun predictLocation(currentTimeMs: Long, outResult: DoubleArray) {
        val base = lastLocation

        if (base == null) {
            outResult[0] = 0.0
            outResult[1] = 0.0
            return
        }

        val timeDeltaMs = currentTimeMs - base.time

        // Si la predicción es muy vieja o futura, devolvemos la real.
        if (timeDeltaMs < 0 || timeDeltaMs > MAX_PREDICTION_TIME_MS) {
            outResult[0] = base.latitude
            outResult[1] = base.longitude
            return
        }

        val speed = base.speed.toDouble()
        if (speed < 0.1) {
            outResult[0] = base.latitude
            outResult[1] = base.longitude
            return
        }

        val dtSeconds = timeDeltaMs / 1000.0
        val distanceMeters = speed * dtSeconds
        val bearingRad = Math.toRadians(base.bearing.toDouble())

        // Matemática Euclidiana (Flat Earth)
        val deltaYMeters = distanceMeters * kotlin.math.cos(bearingRad)
        val deltaXMeters = distanceMeters * kotlin.math.sin(bearingRad)

        val deltaLat = deltaYMeters * DEGREES_PER_METER_LAT
        val degreesPerMeterLon = DEGREES_PER_METER_LAT / longitudeScaleFactor
        val deltaLon = deltaXMeters * degreesPerMeterLon

        outResult[0] = base.latitude + deltaLat
        outResult[1] = base.longitude + deltaLon
    }

    fun getLastAccuracy(): Float {
        return lastLocation?.accuracy ?: 0f
    }

    fun getLastKnown(): Pair<Double, Double>? {
        return lastLocation?.let { it.latitude to it.longitude }
    }

    fun reset() {
        lastLocation = null
        longitudeScaleFactor = 1.0
    }
}