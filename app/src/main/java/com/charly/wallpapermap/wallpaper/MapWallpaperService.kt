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

        // Configuración de Rendimiento (Dinámico)
        private var frameDelay: Long = 33L // Default (aprox 30fps por seguridad)
        private val LERP_FACTOR = 0.1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            LocationManager.init(applicationContext)

            renderer = MapRenderer(applicationContext) {
                LocationPredictor.predictLocation(System.currentTimeMillis())
            }

            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings() // Carga FPS y Estilos
        }

        private var burstFrames = 0
        private fun startBurstRender() {
            burstFrames = 4
            drawBurstFrame()
        }
        private fun drawBurstFrame() {
            if (burstFrames <= 0) return
            drawFrame()
            burstFrames--
            handler.postDelayed({ drawBurstFrame() }, 100) // Un poquito más rápido el burst
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                drawFrame()
                updateSettings()
                startBurstRender()

                LocationManager.start { location ->
                    onLocationUpdate(location)
                }
            } else {
                LocationManager.stop()
                stopAnimationLoop()
                isMoving = false
            }
        }

        private fun onLocationUpdate(location: Location) {
            LocationPredictor.update(location)

            if (renderLat == 0.0 && renderLon == 0.0) {
                renderLat = location.latitude
                renderLon = location.longitude
                renderer.centerOn(renderLat, renderLon)
            }

            // FIX SHERLOCK 2: Lógica robusta de estado
            isMoving = location.speed > 0.5f

            // Si hay movimiento y el motor está apagado, lo prendemos.
            if (isMoving && !isAnimating) {
                startAnimationLoop()
            }

            // Si estamos quietos, frame estático de corrección
            if (!isMoving) {
                val (predLat, predLon) = LocationPredictor.predictLocation(System.currentTimeMillis())
                renderer.centerOn(predLat, predLon)
                drawFrame()
            }
        }

        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!isVisible) return

                val now = System.currentTimeMillis()
                val (targetLat, targetLon) = LocationPredictor.predictLocation(now)

                renderLat += (targetLat - renderLat) * LERP_FACTOR
                renderLon += (targetLon - renderLon) * LERP_FACTOR

                renderer.centerOn(renderLat, renderLon)
                drawFrame()

                if (isMoving) {
                    // Usamos el delay calculado dinámicamente según los FPS elegidos
                    handler.postDelayed(this, frameDelay)
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
            // Estilos
            if (key == SettingsManager.KEY_MAP_STYLE) renderer.applyStyle(SettingsManager.getMapStyle(applicationContext))
            if (key == SettingsManager.KEY_ZOOM) renderer.setZoom(SettingsManager.getMapZoom(applicationContext).toFloat())

            // Motion Sensor (Hot Reload)
            if (key == SettingsManager.KEY_MOTION_SENSOR) {
                LocationManager.updateSettings(applicationContext)
            }

            // Blue Dot (Hot Reload)
            if (key == SettingsManager.KEY_SHOW_BLUE_DOT || key == SettingsManager.KEY_SHOW_ACCURACY) {
                renderer.updateBlueDot()
            }

            // FPS (Hot Reload)
            if (key == SettingsManager.KEY_TARGET_FPS) {
                updateFpsConfig()
            }

            if (isVisible) drawFrame()
        }

        // Método dedicado para leer FPS y calcular el delay
        private fun updateSettings() {
            val ctx = applicationContext
            renderer.applyStyle(SettingsManager.getMapStyle(ctx))
            renderer.setZoom(SettingsManager.getMapZoom(ctx).toFloat())
            updateFpsConfig()
        }

        private fun updateFpsConfig() {
            val fps = SettingsManager.getTargetFps(applicationContext)
            frameDelay = (1000 / fps).toLong()
            Log.d("MapEngine", "🚀 FPS Objetivo actualizado a: $fps (Delay: ${frameDelay}ms)")
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            LocationManager.stop()
        }
    }
}