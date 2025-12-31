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
import kotlin.math.abs

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

        // Render targets (Guardamos la última posición DIBUJADA)
        private var renderLat: Double = 0.0
        private var renderLon: Double = 0.0

        // Configuración de Rendimiento
        private var frameDelay: Long = 33L
        private val LERP_FACTOR = 0.1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            LocationManager.init(applicationContext)

            renderer = MapRenderer(applicationContext) {
                LocationPredictor.predictLocation(System.currentTimeMillis())
            }

            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
        }

        // --- BURST OPTIMIZADO ---
        private var burstFrames = 0

        // Burst corto: Solo para cuando saltamos de lugar y necesitamos cargar tiles nuevos
        private fun startShortBurst() {
            burstFrames = 3 // Bajamos de 6 a 3. Suficiente para cargar caché de disco.
            drawBurstFrame()
        }

        private fun drawBurstFrame() {
            if (burstFrames <= 0) return
            drawFrame()
            burstFrames--
            handler.postDelayed({ drawBurstFrame() }, 100)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                updateSettings()

                // --- LÓGICA INTELIGENTE DE RENDERIZADO ---
                val lastKnown = LocationManager.lastKnownLocation()

                if (shouldRedrawFull(lastKnown)) {
                    // Caso A: Nos movimos lejos o es el inicio.
                    // Hacemos un burst cortito para cargar tiles y acomodar.
                    Log.d("MapEngine", "🔄 Salto detectado o inicio. Burst activado.")

                    // Actualizamos render targets inmediatamente para el snap
                    if (lastKnown != null) {
                        renderLat = lastKnown.latitude
                        renderLon = lastKnown.longitude
                        renderer.centerOn(renderLat, renderLon)
                    }
                    startShortBurst()
                } else {
                    // Caso B: Estamos en el mismo lugar que antes.
                    // Dibujamos 1 SOLO cuadro para restaurar la pantalla (buffer restore).
                    // Osmdroid es eficiente: si los tiles ya están, drawFrame no gasta nada.
                    Log.d("MapEngine", "⏸️ Posición estable. Dibujado único (Eco Mode).")
                    drawFrame()
                }

                // Arrancamos el Manager
                LocationManager.start { location ->
                    onLocationUpdate(location)
                }
            } else {
                LocationManager.stop()
                stopAnimationLoop()
                isMoving = false
            }
        }

        // Verifica si la posición renderizada difiere de la real
        private fun shouldRedrawFull(location: Location?): Boolean {
            if (location == null) return true // Nunca tuvimos ubicación, burst necesario
            if (renderLat == 0.0 && renderLon == 0.0) return true // Primer arranque

            val results = FloatArray(1)
            Location.distanceBetween(renderLat, renderLon, location.latitude, location.longitude, results)
            val distance = results[0]

            // Si nos movimos más de 2 metros con la pantalla apagada, justificamos un redraw
            return distance > 2.0f
        }

        private fun onLocationUpdate(location: Location) {
            LocationPredictor.update(location)

            if (renderLat == 0.0 && renderLon == 0.0) {
                renderLat = location.latitude
                renderLon = location.longitude
                renderer.centerOn(renderLat, renderLon)
            }

            isMoving = location.speed > 0.5f

            if (isMoving && !isAnimating) {
                startAnimationLoop()
            }

            if (!isMoving) {
                val (predLat, predLon) = LocationPredictor.predictLocation(System.currentTimeMillis())
                renderer.centerOn(predLat, predLon)
                drawFrame()
            }
        }

        // ... (El resto del código: renderRunnable, startAnimationLoop, stopAnimationLoop, drawFrame, onSharedPreferenceChanged, updateSettings, updateFpsConfig, onDestroy IGUAL QUE ANTES) ...

        // Te copio drawFrame para referencia (mantené tu versión con el debug FPS si querés)
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

                    // ... Acá iría tu código de Debug FPS si lo tenés ...
                }
            } catch (e: Exception) {
                Log.e("MapEngine", "Error drawing", e)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        // ...

        // Métodos que faltaban en el snippet anterior para completar la clase:
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

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == SettingsManager.KEY_MAP_STYLE) renderer.applyStyle(SettingsManager.getMapStyle(applicationContext))
            if (key == SettingsManager.KEY_ZOOM) renderer.setZoom(SettingsManager.getMapZoom(applicationContext).toFloat())
            if (key == SettingsManager.KEY_MOTION_SENSOR) LocationManager.updateSettings(applicationContext)
            if (key == SettingsManager.KEY_SHOW_BLUE_DOT || key == SettingsManager.KEY_SHOW_ACCURACY) renderer.updateBlueDot()
            if (key == SettingsManager.KEY_TARGET_FPS) updateFpsConfig()
            if (isVisible) drawFrame()
        }

        private fun updateSettings() {
            val ctx = applicationContext
            renderer.applyStyle(SettingsManager.getMapStyle(ctx))
            renderer.setZoom(SettingsManager.getMapZoom(ctx).toFloat())
            updateFpsConfig()
        }

        private fun updateFpsConfig() {
            val fps = SettingsManager.getTargetFps(applicationContext)
            frameDelay = (1000 / fps).toLong()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            LocationManager.stop()
        }
    }
}