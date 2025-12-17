package com.charly.wallpapermap.wallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.preference.PreferenceManager
import com.charly.wallpapermap.location.LocationManager
import com.charly.wallpapermap.map.MapRenderer
import com.charly.wallpapermap.settings.SettingsManager
import org.osmdroid.util.GeoPoint
import android.util.Log
import android.view.View

class MapWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MapEngine()

    inner class MapEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val handler = Handler(Looper.getMainLooper())
        private lateinit var renderer: MapRenderer
        private lateinit var prefs: SharedPreferences

        private var burstFrames = 0
        private var lastLocation: GeoPoint? = null
        private var isRendering = false
        private var isLocationActive = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            // Inicializar LocationManager (Global)
            LocationManager.init(applicationContext)

            // Inicializar Renderer
            renderer = MapRenderer(applicationContext) {
                lastLocation?.let { it.latitude to it.longitude }
            }

            // Registrar escucha de preferencias
            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)

            // Cargar estado inicial
            updateSettings()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isRendering = visible
            if (visible) {
                // Al volver visible, forzamos actualización de settings por si cambiaron mientras estaba oculto
                updateSettings()
                startBurstRender()

                if (!isLocationActive) {
                    isLocationActive = true
                    LocationManager.start { loc ->
                        val newPoint = GeoPoint(loc.first, loc.second)
                        if (shouldUpdate(newPoint)) {
                            lastLocation = newPoint
                            // Centramos y dibujamos
                            renderer.centerOn(loc.first, loc.second)
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

        // 👂 Escuchamos cambios en tiempo real
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                SettingsManager.KEY_MAP_STYLE -> {
                    renderer.applyStyle(SettingsManager.getMapStyle(applicationContext))
                    drawFrame()
                }
                SettingsManager.KEY_ZOOM -> {
                    renderer.setZoom(SettingsManager.getMapZoom(applicationContext).toFloat())
                    drawFrame()
                }
            }
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
            Log.d("MapEngine", "Engine destruido")
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

        private fun drawFrame() {
            if (!isRendering) return
            val holder: SurfaceHolder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val mapView = renderer.mapView

                    // Medir y Layout manual porque no somos una View normal
                    val width = canvas.width
                    val height = canvas.height
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)

                    mapView.measure(widthSpec, heightSpec)
                    mapView.layout(0, 0, width, height)

                    // ❌ YA NO hacemos ensureZoomUpdated ni ensureStyleUpdated aquí.
                    // El renderer ya tiene los datos correctos gracias al listener.

                    mapView.draw(canvas)
                }
            } catch (e: Exception) {
                Log.e("MapEngine", "Error en drawFrame", e)
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun shouldUpdate(newPoint: GeoPoint): Boolean {
            val last = lastLocation ?: return true
            return newPoint.distanceToAsDouble(last) > 2.0 // metros
        }
    }
}