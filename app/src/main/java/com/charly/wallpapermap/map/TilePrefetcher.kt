package com.charly.wallpapermap.map

import android.location.Location
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.util.concurrent.Executors
import kotlin.math.*
import com.charly.wallpapermap.map.decode.TileDecodeScheduler
import com.charly.wallpapermap.map.decode.TileDecodeScheduler.PRIORITY_PREFETCH

object DirectionalTilePrefetcher {

    private const val TAG = "DirectionalPrefetch"

    private const val BASE_RADIUS_TILES = 4
    private const val FORWARD_EXTENSION_TILES = 6
    private const val MAX_TILES_PER_CYCLE = 60
    private const val PREFETCH_INTERVAL_MS = 3000L
    private const val TILE_EXPIRATION_MS = 2 * 60 * 1000L

    // ---------------- Dispatcher dedicado ----------------

    private val dispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "TilePrefetchThread")
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // Canal conflated → siempre procesa la última location
    private val locationChannel =
        Channel<Pair<Location, MapView>>(Channel.CONFLATED)

    private var lastPrefetch = 0L

    private val requestedTiles = HashMap<Long, Long>()

    // ---------------- INIT ----------------

    init {
        scope.launch {

            Log.d(
                TAG,
                "🚀 Prefetch Worker iniciado [Hilo: ${Thread.currentThread().name}]"
            )

            for ((location, mapView) in locationChannel) {
                processPrefetch(location, mapView)
            }
        }
    }

    // ---------------- API pública ----------------

    fun onLocationUpdate(location: Location, mapView: MapView) {
        locationChannel.trySend(location to mapView)
    }

    // ---------------- Worker principal ----------------

    private suspend fun processPrefetch(
        location: Location,
        mapView: MapView
    ) {

        val now = System.currentTimeMillis()
        if (now - lastPrefetch < PREFETCH_INTERVAL_MS) return
        lastPrefetch = now

        try {

            val zoom = mapView.zoomLevelDouble.toInt()

            val tileXY = latLonToTile(
                location.latitude,
                location.longitude,
                zoom
            )

            val centerX = tileXY.first
            val centerY = tileXY.second

            val speed = location.speed
            val bearing = location.bearing.toDouble()

            val tilesToRequest = mutableListOf<Long>()

            // ---------- RADIO BASE ----------
            for (dx in -BASE_RADIUS_TILES..BASE_RADIUS_TILES) {
                for (dy in -BASE_RADIUS_TILES..BASE_RADIUS_TILES) {

                    val x = centerX + dx
                    val y = centerY + dy

                    tilesToRequest.add(
                        MapTileIndex.getTileIndex(zoom, x, y)
                    )
                }
            }

            // ---------- EXTENSIÓN DIRECCIONAL ----------
            if (speed > 1.5f) {

                val (forward, side) = bearingToVector(bearing)

                for (step in 1..FORWARD_EXTENSION_TILES) {

                    val fx = centerX + (forward.first * step).roundToInt()
                    val fy = centerY + (forward.second * step).roundToInt()

                    for (spread in -2..2) {

                        val sx = fx + (side.first * spread).roundToInt()
                        val sy = fy + (side.second * spread).roundToInt()

                        tilesToRequest.add(
                            MapTileIndex.getTileIndex(zoom, sx, sy)
                        )
                    }
                }
            }

            val finalList = tilesToRequest
                .distinct()
                .filter { !requestedTiles.contains(it) }
                .take(MAX_TILES_PER_CYCLE)

            if (finalList.isEmpty()) return

            // OSMDroid necesita esto en main
            withContext(Dispatchers.Main) {

                val provider = mapView.tileProvider

                finalList.forEach { tileIndex ->
                    provider.getMapTile(tileIndex)
                    TileDecodeScheduler.schedule(
                        tileIndex,
                        PRIORITY_PREFETCH,
                        mapView.tileProvider.tileSource.name()
                    )
                    requestedTiles[tileIndex] = System.currentTimeMillis()
                }
            }

            cleanupRequestedTiles()

            Log.d(
                TAG,
                "📥 PREFETCH [Hilo: ${Thread.currentThread().name}] | " +
                        "Tiles: ${finalList.size} | Zoom: $zoom | " +
                        "Centro: ${location.latitude}, ${location.longitude}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Prefetch error: ${e.message}")
        }
    }

    // ---------------- Limpieza ----------------

    private fun cleanupRequestedTiles() {

        val now = System.currentTimeMillis()

        requestedTiles.entries.removeIf {
            now - it.value > TILE_EXPIRATION_MS
        }
    }

    // ---------------- Utils ----------------

    private fun latLonToTile(
        lat: Double,
        lon: Double,
        zoom: Int
    ): Pair<Int, Int> {

        val n = 2.0.pow(zoom.toDouble())

        val x = ((lon + 180.0) / 360.0 * n).toInt()

        val latRad = Math.toRadians(lat)

        val y = (
                (1.0 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) /
                        2.0 * n
                ).toInt()

        return x to y
    }

    private fun bearingToVector(
        bearing: Double
    ): Pair<Pair<Double, Double>, Pair<Double, Double>> {

        val rad = Math.toRadians(bearing)

        val forwardX = sin(rad)
        val forwardY = -cos(rad)

        val sideX = -forwardY
        val sideY = forwardX

        return (forwardX to forwardY) to (sideX to sideY)
    }
}
