package com.charly.wallpapermap.wallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.View
import androidx.preference.PreferenceManager
import com.charly.wallpapermap.location.LocationManager
import com.charly.wallpapermap.map.MapRenderer
import com.charly.wallpapermap.settings.SettingsManager
import android.util.Log
import kotlin.math.abs

class MapWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MapEngine()

    inner class MapEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var renderer: MapRenderer
        private lateinit var prefs: SharedPreferences

        // Estado de Renderizado
        private var isVisible = false
        private var isAnimating = false

        // Coordenadas para Interpolación (Lerp)
        private var targetLat: Double = 0.0
        private var targetLon: Double = 0.0
        private var currentLat: Double = 0.0
        private var currentLon: Double = 0.0

        // Configuración de animación
        private val FPS = 30
        private val FRAME_DELAY = (1000 / FPS).toLong() // ~33ms
        private val LERP_FACTOR = 0.1f // Qué tan rápido se acerca al target (0.1 = suave, 0.3 = rápido)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            LocationManager.init(applicationContext)
            renderer = MapRenderer(applicationContext) {
                // El punto azul sigue mostrando la "realidad interpolada"
                currentLat to currentLon
            }

            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
        }

        // Esto se da cuando se prende el teléfono, una forma de asegurar dibujar el mapa en tiempo y forma al hacerse visible el wallpaper, provisorio

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

                // Iniciamos GPS
                LocationManager.start { loc ->
                    updateTargetLocation(loc.first, loc.second)
                }
            } else {
                LocationManager.stop()
                stopAnimationLoop()
            }
        }

        // --- MAGIA: Recibimos el target y despertamos el loop ---
        private fun updateTargetLocation(lat: Double, lon: Double) {
            // Si es la primera vez, teletransportamos para no viajar desde el (0,0)
            if (currentLat == 0.0 && currentLon == 0.0) {
                currentLat = lat
                currentLon = lon
                renderer.centerOn(lat, lon)
                drawFrame() // Un cuadro estático inicial
            }

            targetLat = lat
            targetLon = lon

            // Si no estamos animando, arrancamos el loop
            if (!isAnimating && isVisible) {
                startAnimationLoop()
            }
        }

        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!isVisible) return

                // 1. Interpolación (Lerp)
                // Nos movemos un % de la distancia hacia el objetivo
                currentLat += (targetLat - currentLat) * LERP_FACTOR
                currentLon += (targetLon - currentLon) * LERP_FACTOR

                // 2. Mover Mapa y Dibujar
                renderer.centerOn(currentLat, currentLon)
                drawFrame()

                // 3. Chequeo de parada ("On Demand")
                // Si estamos muy cerca, cortamos el loop para ahorrar batería
                val diffLat = abs(targetLat - currentLat)
                val diffLon = abs(targetLon - currentLon)

                if (diffLat < 0.0000005 && diffLon < 0.0000005) {
                    // Ya llegamos ("snap" final para asegurar)
                    currentLat = targetLat
                    currentLon = targetLon
                    renderer.centerOn(currentLat, currentLon)
                    drawFrame()
                    isAnimating = false // Se duerme
                    // No llamamos a postDelayed
                } else {
                    // Seguimos bailando
                    handler.postDelayed(this, FRAME_DELAY)
                }
            }
        }

        private fun startAnimationLoop() {
            isAnimating = true
            handler.removeCallbacks(renderRunnable) // Por seguridad
            handler.post(renderRunnable)
        }

        private fun stopAnimationLoop() {
            isAnimating = false
            handler.removeCallbacks(renderRunnable)
        }

        // --- Renderizado Estándar ---
        private fun drawFrame() {
            val holder: SurfaceHolder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val mapView = renderer.mapView
                    val width = canvas.width
                    val height = canvas.height

                    // Medimos una vez
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
            // Actualización simple de settings
            if (key == SettingsManager.KEY_MAP_STYLE) renderer.applyStyle(SettingsManager.getMapStyle(applicationContext))
            if (key == SettingsManager.KEY_ZOOM) renderer.setZoom(SettingsManager.getMapZoom(applicationContext).toFloat())
            if (isVisible) drawFrame() // Redibujar forzado al cambiar config
        }

        private fun updateSettings() {
            // (Igual que antes)
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