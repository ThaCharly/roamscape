package com.charly.wallpapermap.map.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import kotlinx.coroutines.*
import org.osmdroid.views.MapView
import java.util.concurrent.Executors
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

object TileDecodeWorkers {

    // 🧠 MEJORA 1: Workers dinámicos según potencia del CPU
    // Mínimo 2 hilos, Máximo 4 (para no saturar en teléfonos gama alta)
    private val WORKER_COUNT = run {
        val cores = Runtime.getRuntime().availableProcessors()
        // Usamos la mitad de los cores disponibles, clavado entre 2 y 4.
        (cores / 2).coerceIn(2, 4)
    }

    private val dispatcher = Executors
        .newFixedThreadPool(WORKER_COUNT) { r -> Thread(r, "TileDecodeWorker") }
        .asCoroutineDispatcher()

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var mapViewRef: WeakReference<MapView>? = null

    // 🧠 MEJORA 3: Protección de ciclo de vida
    @Volatile
    private var isStarted = false

    fun setMapView(mapView: MapView) {
        mapViewRef = WeakReference(mapView)
    }

    fun start(context: Context) {
        if (isStarted) return
        isStarted = true

        Log.d(
            "TileDecode",
            "🚀 Workers iniciados | Cantidad: $WORKER_COUNT | CPU: ${Runtime.getRuntime().availableProcessors()}"
        )

        repeat(WORKER_COUNT) {
            scope.launch {
                workerLoop(context)
            }
        }
    }


    private suspend fun CoroutineScope.workerLoop(ctx: Context) {
        while (isActive) {

            val job = TileDecodeQueue.take()

            if (TileBitmapCache.get(job.tileIndex) != null) continue

            val mapView = mapViewRef?.get()

            if (mapView != null) {
                try {
                    if (mapView.tileProvider.tileCache.getMapTile(job.tileIndex) != null) {
                        Log.v("TileDecode", "🟡 SKIP PROVIDER CACHE | Tile: ${job.tileIndex}")
                        continue
                    }
                } catch (_: Exception) {}
            }

            decodeTile(ctx, job)
        }
    }


    private fun decodeTile(ctx: Context, job: TileDecodeJob) {

        val threadName = Thread.currentThread().name
        val start = System.currentTimeMillis()

        try {

            val file = TileFileResolver.resolve(ctx, job.tileIndex, job.tileSourceName)
                ?: return

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
            }

            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
                ?: return

            TileBitmapCache.put(job.tileIndex, bmp)

            mapViewRef?.get()?.let { map ->

                val drawable = BitmapDrawable(ctx.resources, bmp)

                map.post {

                    try {
                        map.tileProvider.tileCache.putTile(job.tileIndex, drawable)

                        if (job.priority == TileDecodeScheduler.PRIORITY_VISIBLE) {
                            map.invalidate()
                        }

                    } catch (_: Exception) {}
                }
            }

            val duration = System.currentTimeMillis() - start

            Log.d(
                "TileDecode",
                "🧩 DECODE OK | Tile: ${job.tileIndex} | " +
                        "Priority: ${job.priority} | " +
                        "Tiempo: ${duration}ms | " +
                        "Hilo: $threadName"
            )

        } catch (e: Exception) {

            Log.e(
                "TileDecode",
                "💥 DECODE ERROR | Tile: ${job.tileIndex} | ${e.message}"
            )
        }
    }


}