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

        private var isVisible = false
        private var isAnimating = false
        private var isMoving = false

        // Posición actual de la CÁMARA (Interpolada)
        private var renderLat: Double = 0.0
        private var renderLon: Double = 0.0

        private val FPS = 30
        private val FRAME_DELAY = (1000 / FPS).toLong()
        private val LERP_FACTOR = 0.1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            LocationManager.init(applicationContext)

            // El punto azul usa la predicción en tiempo real
            renderer = MapRenderer(applicationContext) {
                LocationPredictor.predictLocation(System.currentTimeMillis())
            }

            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
        }

        // Burst para evitar pantalla gris al desbloquear
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

                // Iniciamos GPS recibiendo objeto Location
                LocationManager.start { location ->
                    onLocationUpdate(location)
                }
            } else {
                LocationManager.stop()
                stopAnimationLoop()
            }
        }

        private fun onLocationUpdate(location: Location) {
            // 1. Alimentamos la física
            LocationPredictor.update(location)

            // 2. Inicialización
            if (renderLat == 0.0 && renderLon == 0.0) {
                renderLat = location.latitude
                renderLon = location.longitude
                renderer.centerOn(renderLat, renderLon)
            }

            // 3. Gestión de Energía: ¿Prendemos el motor gráfico?
            val wasMoving = isMoving
            isMoving = location.speed > 0.5f

            if (isMoving && !wasMoving) {
                startAnimationLoop()
            }

            // Si estamos quietos, forzamos un frame para actualizar posición estática
            if (!isMoving) {
                val (predLat, predLon) = LocationPredictor.predictLocation(System.currentTimeMillis())
                renderer.centerOn(predLat, predLon)
                drawFrame()
            }
        }

        // --- GAME LOOP (30 FPS) ---
        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!isVisible) return

                val now = System.currentTimeMillis()

                // A. Predicción Física (¿Dónde estamos YA?)
                val (targetLat, targetLon) = LocationPredictor.predictLocation(now)

                // B. Interpolación Suave de Cámara
                renderLat += (targetLat - renderLat) * LERP_FACTOR
                renderLon += (targetLon - renderLon) * LERP_FACTOR

                // C. Dibujar
                renderer.centerOn(renderLat, renderLon)
                drawFrame()

                // D. Continuidad
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