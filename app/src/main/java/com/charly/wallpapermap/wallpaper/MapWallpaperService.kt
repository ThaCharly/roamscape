package com.charly.wallpapermap.wallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View
import androidx.preference.PreferenceManager
import com.charly.wallpapermap.location.LocationManager
import com.charly.wallpapermap.location.LocationPredictor
import com.charly.wallpapermap.map.MapRenderer
import com.charly.wallpapermap.settings.SettingsManager
import android.util.Log

class MapWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MapEngine()

    inner class MapEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var renderer: MapRenderer
        private lateinit var prefs: SharedPreferences

        // Estado
        private var isVisible = false
        private var isAnimating = false
        private var isMoving = false

        // Render targets
        private var renderLat: Double = 0.0
        private var renderLon: Double = 0.0

        // Configuración original respetada
        private val FPS = 30
        private val FRAME_DELAY = (1000 / FPS).toLong()
        private val LERP_FACTOR = 0.1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            LocationManager.init(applicationContext)

            // Inicialización con predicción
            renderer = MapRenderer(applicationContext) {
                LocationPredictor.predictLocation(System.currentTimeMillis())
            }

            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
        }

        // --- BURST FRAMES (Tu código original) ---
        private var burstFrames = 0
        private fun startBurstRender() {
            burstFrames = 6
            drawBurstFrame()
        }
        private fun drawBurstFrame() {
            if (burstFrames <= 0) return
            drawFrame()
            burstFrames--
            handler.postDelayed({ drawBurstFrame() }, 150)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                updateSettings()
                startBurstRender()

                // LocationManager ahora devuelve Location
                LocationManager.start { location ->
                    onLocationUpdate(location)
                }
            } else {
                LocationManager.stop()
                stopAnimationLoop()
            }
        }

        private fun onLocationUpdate(location: Location) {
            // Actualizamos física
            LocationPredictor.update(location)

            // Init
            if (renderLat == 0.0 && renderLon == 0.0) {
                renderLat = location.latitude
                renderLon = location.longitude
                renderer.centerOn(renderLat, renderLon)
            }

            // Gestión de movimiento
            val wasMoving = isMoving
            isMoving = location.speed > 0.5f

            if (isMoving && !wasMoving) {
                startAnimationLoop()
            }

            // Frame estático si paramos
            if (!isMoving) {
                val (predLat, predLon) = LocationPredictor.predictLocation(System.currentTimeMillis())
                renderer.centerOn(predLat, predLon)
                drawFrame()
            }
        }

        // Loop de Renderizado (Sin rotación, solo posición)
        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!isVisible) return

                val now = System.currentTimeMillis()

                // Obtenemos posición interpolada basada en Híbrido (GPS/Brújula)
                val (targetLat, targetLon) = LocationPredictor.predictLocation(now)

                // Lerp suave
                renderLat += (targetLat - renderLat) * LERP_FACTOR
                renderLon += (targetLon - renderLon) * LERP_FACTOR

                renderer.centerOn(renderLat, renderLon)
                drawFrame()

                if (isMoving) {
                    handler.postDelayed(this, FRAME_DELAY)
                } else {
                    isAnimating = false
                }
            }
        }

        private fun startAnimationLoop() {
            if (!isAnimating) {
                isAnimating = true
                handler.removeCallbacks(renderRunnable)
                handler.post(renderRunnable)
            }
        }

        private fun stopAnimationLoop() {
            isAnimating = false
            handler.removeCallbacks(renderRunnable)
        }

        private fun drawFrame() {
            val holder: SurfaceHolder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val mapView = renderer.mapView
                    val width = canvas.width
                    val height = canvas.height

                    if (mapView.width != width || mapView.height != height) {
                        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                        mapView.measure(wSpec, hSpec)
                        mapView.layout(0, 0, width, height)
                    }
                    mapView.draw(canvas)
                }
            } catch (e: Exception) {
                Log.e("MapEngine", "Error drawing", e)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == SettingsManager.KEY_MAP_STYLE) renderer.applyStyle(SettingsManager.getMapStyle(applicationContext))
            if (key == SettingsManager.KEY_ZOOM) renderer.setZoom(SettingsManager.getMapZoom(applicationContext).toFloat())
            if (isVisible) drawFrame()
        }

        private fun updateSettings() {
            val ctx = applicationContext
            renderer.applyStyle(SettingsManager.getMapStyle(ctx))
            renderer.setZoom(SettingsManager.getMapZoom(ctx).toFloat())
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            LocationManager.stop()
        }
    }
}