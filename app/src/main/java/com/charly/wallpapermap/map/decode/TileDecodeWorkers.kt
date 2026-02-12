package com.charly.wallpapermap.map.decode

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import kotlinx.coroutines.*
import org.osmdroid.views.MapView
import java.util.concurrent.Executors
import java.lang.ref.WeakReference

object TileDecodeWorkers {

    private const val WORKER_COUNT = 2 // 2 hilos dedicados solo a descomprimir PNGs

    private val dispatcher = Executors
        .newFixedThreadPool(WORKER_COUNT) { r -> Thread(r, "TileDecodeWorker") }
        .asCoroutineDispatcher()

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // Referencia débil al mapa para inyectarle los tiles
    private var mapViewRef: WeakReference<MapView>? = null

    fun setMapView(mapView: MapView) {
        mapViewRef = WeakReference(mapView)
    }

    fun start(context: Context) {
        repeat(WORKER_COUNT) {
            scope.launch {
                workerLoop(context)
            }
        }
    }

    // CORRECCIÓN: Agregamos 'CoroutineScope.' antes del nombre
    private suspend fun CoroutineScope.workerLoop(ctx: Context) {
        while (isActive) { // Ahora sí funciona isActive
            // Esto bloquea hasta que haya trabajo (es eficiente)
            val job = TileDecodeQueue.take()

            // 1. Chequeo rápido: ¿Ya lo tenemos en NUESTRA caché?
            if (TileBitmapCache.get(job.tileIndex) != null) {
                continue
            }

            // 2. Chequeo de Osmdroid: ¿Ya lo tiene el mapa? (Evitamos decode al pedo)
            val mapView = mapViewRef?.get()
            if (mapView != null) {
                // Accedemos a la cache de memoria de osmdroid (protegida pero accesible)
                // Si devuelve algo, es que ya está listo para dibujar.
                // Usamos try/catch por si osmdroid cambia API interna, aunque es estable.
                try {
                    if (mapView.tileProvider.tileCache.getMapTile(job.tileIndex) != null) {
                        continue
                    }
                } catch (e: Exception) {
                    // Ignorar
                }
            }

            decodeTile(ctx, job)
        }
    }

    private fun decodeTile(ctx: Context, job: TileDecodeJob) {
        try {
            val file = TileFileResolver.resolve(ctx, job.tileIndex, job.tileSourceName)
                ?: return // No está descargado todavía

            // DECODE: La operación pesada (CPU)
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
                ?: return

            // 1. Guardar en nuestra caché LRU
            TileBitmapCache.put(job.tileIndex, bmp)

            // 2. INYECCIÓN DIRECTA (El truco maestro)
            mapViewRef?.get()?.let { map ->
                val drawable = BitmapDrawable(ctx.resources, bmp)
                // Inyectamos a la caché de Osmdroid
                map.tileProvider.tileCache.putTile(job.tileIndex, drawable)

                // Opcional: Forzar repintado si es muy urgente (PRIORITY_VISIBLE)
                if (job.priority == TileDecodeScheduler.PRIORITY_VISIBLE) {
                    map.postInvalidate()
                }
            }

        } catch (e: Exception) {
            Log.e("TileDecode", "Error decodificando tile: ${e.message}")
        }
    }
}