package com.charly.wallpapermap.location

object LocationPredictor {

    // ⚠️ SUBIMOS EL ALPHA: De 0.2 a 0.7
    // Esto hace que el target se actualice casi instantáneamente.
    // La animación visual (Lerp) se encargará de que no se vea el salto.
    private const val ALPHA = 0.7

    private var smoothedLat: Double? = null
    private var smoothedLon: Double? = null

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

    // ... (El resto de los métodos como bearingTo y predictNext podés dejarlos igual o borrarlos si no los usás) ...

    fun getLastKnown(): Pair<Double, Double>? {
        return if (smoothedLat != null && smoothedLon != null) {
            smoothedLat!! to smoothedLon!!
        } else null
    }

    fun reset() {
        smoothedLat = null
        smoothedLon = null
    }
}