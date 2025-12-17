package com.charly.wallpapermap.wallpaper

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.charly.wallpapermap.location.LocationManager
import com.charly.wallpapermap.map.MapRenderer
import org.osmdroid.util.GeoPoint
import android.util.Log
import kotlin.math.*

class MapWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MapEngine()



    inner class MapEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var renderer: MapRenderer

        private var burstFrames = 0

        private var lastLocation: GeoPoint? = null
        private var isRendering = false
        private var isLocationActive = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = MapRenderer(applicationContext) { lastLocation?.let { it.latitude to it.longitude } }

            LocationManager.init(applicationContext)
        }

        private fun startBurstRender() {
            burstFrames = 3
            drawBurstFrame()
        }

        private fun drawBurstFrame() {
            if (burstFrames <= 0) return
            drawFrame()
            burstFrames--
            handler.postDelayed({ drawBurstFrame() }, 170)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isRendering = visible
            if (visible) {
                startBurstRender()
                // drawFrame()
              //  handler.postDelayed({ drawFrame() }, 100)
              //  handler.postDelayed({ drawFrame() }, 220)
              //  handler.postDelayed({ drawFrame() }, 400)
              //  handler.postDelayed({ drawFrame() }, 800)
             //   handler.postDelayed({ drawFrame() }, 1600)
                if (!isLocationActive) {
                    isLocationActive = true
                    LocationManager.start { loc ->
                        val newPoint = GeoPoint(loc.first, loc.second)
                        if (shouldUpdate(newPoint)) {
                            lastLocation = newPoint
                            drawFrame()
                        }
                    }
                }
            } else {
                if (isLocationActive) {
                    isLocationActive = false
                    LocationManager.stop()
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            LocationManager.stop()

            Log.d("MapEngine", "Engine destruido")
        }

        /** Dibuja el MapView al canvas actual */
        private fun drawFrame() {
            if (!isRendering) return
            val holder: SurfaceHolder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null && ::renderer.isInitialized) {
                    val mapView = renderer.mapView
                    mapView.measure(
                        SurfaceCompatMeasureSpec(canvas.width),
                        SurfaceCompatMeasureSpec(canvas.height)
                    )
                    mapView.layout(0, 0, canvas.width, canvas.height)

                    // ✅ Asegura que el zoom esté actualizado
                    renderer.ensureZoomUpdated()
                    renderer.ensureStyleUpdated()

                    lastLocation?.let {
                        renderer.centerOn(it.latitude, it.longitude)
                    }

                    mapView.draw(canvas)
                }
            } catch (e: Exception) {
                Log.e("MapEngine", "Error en drawFrame", e)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        /** Evita repintar si no te moviste lo suficiente */
        private fun shouldUpdate(newPoint: GeoPoint): Boolean {
            val last = lastLocation ?: return true
            return newPoint.distanceToAsDouble(last) > 2.0 // metros
        }
    }
}
/** Helper para medida exacta */
private fun SurfaceCompatMeasureSpec(size: Int): Int {
    return android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
}
