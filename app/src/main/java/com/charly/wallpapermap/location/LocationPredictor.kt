package com.charly.wallpapermap.location

import kotlin.math.*

object LocationPredictor {

    private const val ALPHA = 0.2      // EMA smoothing
    private const val FUTURE_SECS = 2.0 // segundos para predecir
    private const val BEARING_WINDOW = 5 // cantidad de muestras para bearing

    private var smoothedLat: Double? = null
    private var smoothedLon: Double? = null
    private val bearingBuffer = mutableListOf<Double>()

    /**
     * Recibe la ubicación cruda y devuelve la versión suavizada (EMA)
     */
    fun update(lat: Double, lon: Double): Pair<Double, Double> {
        if (smoothedLat == null || smoothedLon == null) {
            smoothedLat = lat
            smoothedLon = lon
            return lat to lon
        }

        smoothedLat = smoothedLat!! * (1 - ALPHA) + lat * ALPHA
        smoothedLon = smoothedLon!! * (1 - ALPHA) + lon * ALPHA
        return smoothedLat!! to smoothedLon!!
    }

    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val brng = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return brng
    }

    fun predictNext(lat: Double, lon: Double, speed: Double): Pair<Double, Double> {
        val avgBearing = bearingBuffer.takeLast(BEARING_WINDOW).averageOrNull() ?: return lat to lon
        val distance = speed * FUTURE_SECS
        val R = 6371000.0
        val δ = distance / R
        val θ = Math.toRadians(avgBearing)

        val φ1 = Math.toRadians(lat)
        val λ1 = Math.toRadians(lon)

        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(
            sin(θ) * sin(δ) * cos(φ1),
            cos(δ) - sin(φ1) * sin(φ2)
        )

        return Math.toDegrees(φ2) to Math.toDegrees(λ2)
    }

    fun pushBearing(bearing: Double) {
        bearingBuffer.add(bearing)
        if (bearingBuffer.size > BEARING_WINDOW * 2)
            bearingBuffer.removeFirst()
    }

    /**
     * (NUEVA) Devuelve la última ubicación suavizada conocida.
     */
    fun getLastKnown(): Pair<Double, Double>? {
        return if (smoothedLat != null && smoothedLon != null) {
            smoothedLat!! to smoothedLon!!
        } else null
    }

    /**
     * (NUEVA) Limpia los valores almacenados (para usar al iniciar el servicio).
     */
    fun reset() {
        smoothedLat = null
        smoothedLon = null
        bearingBuffer.clear()
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isNotEmpty()) average() else null
}