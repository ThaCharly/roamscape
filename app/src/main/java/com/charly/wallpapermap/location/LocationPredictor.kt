package com.charly.wallpapermap.location

import android.location.Location
import kotlin.math.cos

object LocationPredictor {

    private var lastLocation: Location? = null

    // Cacheamos el factor de escala de longitud para no calcular coseno en cada frame
    // Se actualiza solo cuando llega un nuevo fix de GPS.
    private var longitudeScaleFactor: Double = 1.0

    private const val MAX_PREDICTION_TIME_MS = 2000L

    // Constantes para "Flat Earth Math" (Grados por metro aprox)
    // 1 grado de latitud ~= 111,132 metros
    private const val DEGREES_PER_METER_LAT = 1.0 / 111132.0

    fun update(location: Location) {
        lastLocation = location
        // Optimizacion: Pre-calcular el factor de corrección de longitud según la latitud actual.
        // Math.toRadians es liviano, pero sacarlo del loop de render es mejor.
        longitudeScaleFactor = cos(Math.toRadians(location.latitude))
    }

    fun predictLocation(currentTimeMs: Long): Pair<Double, Double> {
        val base = lastLocation ?: return (0.0 to 0.0)

        val timeDeltaMs = currentTimeMs - base.time

        // Si la predicción es muy vieja (>2s) o futura (<0), devolvemos la real y cortamos.
        if (timeDeltaMs < 0 || timeDeltaMs > MAX_PREDICTION_TIME_MS) {
            return base.latitude to base.longitude
        }

        val dtSeconds = timeDeltaMs / 1000.0
        val speed = base.speed.toDouble()

        // Si estamos casi quietos, ni gastamos CPU calculando
        if (speed < 0.1) return base.latitude to base.longitude

        val distanceMeters = speed * dtSeconds
        val bearingRad = Math.toRadians(base.bearing.toDouble())

        // --- MATEMÁTICA DE TIERRA PLANA (Euclidiana) ---
        // Mucho más rápido que la trigonometría esférica para distancias cortas.
        // dy = distancia * cos(rumbo)
        // dx = distancia * sin(rumbo)

        val deltaYMeters = distanceMeters * kotlin.math.cos(bearingRad)
        val deltaXMeters = distanceMeters * kotlin.math.sin(bearingRad)

        // Convertimos metros a grados
        val deltaLat = deltaYMeters * DEGREES_PER_METER_LAT
        // Para longitud, ajustamos por la latitud (la tierra se achica hacia los polos)
        // 1 grado lon = 111132 * cos(lat) metros
        val degreesPerMeterLon = DEGREES_PER_METER_LAT / longitudeScaleFactor
        val deltaLon = deltaXMeters * degreesPerMeterLon

        val nextLat = base.latitude + deltaLat
        val nextLon = base.longitude + deltaLon

        return nextLat to nextLon
    }

    fun getLastKnown(): Pair<Double, Double>? {
        return lastLocation?.let { it.latitude to it.longitude }
    }

    fun getLastAccuracy(): Float {
        return lastLocation?.accuracy ?: 0f
    }

    fun reset() {
        lastLocation = null
        longitudeScaleFactor = 1.0
    }
}