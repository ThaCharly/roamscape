package com.charly.wallpapermap.map

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.sin

object TilePrefetcher {

    private const val TAG = "TilePrefetcher"

    // Configuración: Ajustá esto según tu gusto de consumo de datos vs suavidad
    private const val BASE_RADIUS_METERS = 300.0
    private const val PREDICTION_SECONDS = 6.0  // Cuantos segundos "adelante" miramos
    private const val MIN_SPEED_THRESHOLD = 2.0  // m/s. Si camina lento, no predecimos tanto.
    private const val PREFETCH_INTERVAL_MS = 4000L // Para no saturar la cola de descargas

    private var prefetchJob: Job? = null
    private var lastPrefetchTime = 0L

    fun onLocationUpdate(context: Context, location: Location, mapView: MapView) {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchTime < PREFETCH_INTERVAL_MS) return

        // Si ya hay un laburo corriendo, dejalo terminar o cancelalo si querés ser agresivo.
        // Por ahora dejamos que termine para no desperdiciar descargas a medio camino.
        if (prefetchJob?.isActive == true) return

        lastPrefetchTime = now

        prefetchJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val speed = location.speed

                // 1. Calcular desplazamiento del centro (Mira hacia adelante)
                val lookAheadSeconds = if (speed > MIN_SPEED_THRESHOLD) PREDICTION_SECONDS else 0.0
                val distanceAhead = speed * lookAheadSeconds

                val bearingRad = Math.toRadians(location.bearing.toDouble())
                val latOffsetM = distanceAhead * cos(bearingRad)
                val lonOffsetM = distanceAhead * sin(bearingRad)

                // Conversión aproximada Metros -> Grados
                val metersPerDegLat = 111320.0
                val metersPerDegLon = 111320.0 * cos(Math.toRadians(location.latitude))

                val centerLat = location.latitude + (latOffsetM / metersPerDegLat)
                val centerLon = location.longitude + (lonOffsetM / metersPerDegLon)

                // 2. Calcular Radio Dinámico (Más rápido = Más área)
                val dynamicRadius = BASE_RADIUS_METERS + (speed * PREDICTION_SECONDS * 0.4)

                val latRadiusDeg = dynamicRadius / metersPerDegLat
                val lonRadiusDeg = dynamicRadius / metersPerDegLon

                // 3. Definir la caja (BoundingBox)
                val box = BoundingBox(
                    centerLat + latRadiusDeg, // North
                    centerLon + lonRadiusDeg, // East
                    centerLat - latRadiusDeg, // South
                    centerLon - lonRadiusDeg  // West
                )

                // 4. Descargar
                // Usamos zoom actual y zoom+1 por si el usuario hace zoom in
                // (Ojo: mapView no es thread-safe, pero leer zoomLevelDouble suele ser seguro)
                val currentZoom = mapView.zoomLevelDouble.toInt()

                // IMPORTANTE: CacheManager usa hilos propios, pero downloadAreaAsync es bloqueante
                // en su propia lógica si no se maneja bien, por eso estamos en Dispatchers.IO
                val cacheManager = CacheManager(mapView)

                // Esto baja los tiles que faltan al disco
                cacheManager.downloadAreaAsync(context, box, currentZoom, currentZoom)

                // Log silencioso para debug
                // Log.d(TAG, "Prefetch: Vel=${speed.toInt()}m/s -> Adelante ${distanceAhead.toInt()}m")

            } catch (e: Exception) {
                Log.e(TAG, "Error en prefetch: ${e.message}")
            }
        }
    }
}