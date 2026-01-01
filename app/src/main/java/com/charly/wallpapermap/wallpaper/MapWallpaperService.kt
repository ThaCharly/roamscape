package com.charly.wallpapermap.wallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
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
        private val choreographer = Choreographer.getInstance()

        private lateinit var renderer: MapRenderer
        private lateinit var prefs: SharedPreferences

        // Estado
        private var isVisible = false
        private var isAnimating = false
        private var isMoving = false

        // Render targets
        private var renderLat: Double = 0.0
        private var renderLon: Double = 0.0

        // Configuración de Rendimiento
        private var frameDelay: Long = 33L
        private val LERP_FACTOR = 0.1f

        // Configuración VSync vs FPS
        private var useVsync = false
        private var debugForceAnim = false
        private var showFps = false

        // OPTIMIZACIÓN: Objetos reutilizables (Zero-Allocation)
        private val distanceResults = FloatArray(1)
        private val predictionResult = DoubleArray(2) // Para recibir lat/lon sin crear objetos Pair

        // --- DEBUG FPS OPTIMIZADO ---
        private var debugLastTime = 0L
        private var debugFrameCount = 0
        private var debugActualFps = 0
        private var fpsTextCache = "" // Cacheamos el String para no crearlo en cada frame

        private val fpsPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 60f
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            LocationManager.init(applicationContext)

            // El renderer usa el predictor, pero ahora pasamos una lambda que maneja el array internamente si hiciera falta,
            // pero OJO: el MapRenderer usa este callback para el BlueDot.
            // Para mantener compatibilidad con BlueDotOverlay que espera () -> Pair, hacemos un wrapper temporal
            // o mejor, optimizamos BlueDot después. Por ahora, dejamos que cree un Pair SOLO para el BlueDot (es menos crítico)
            // pero el LOOP principal de animación NO usará esto.
            renderer = MapRenderer(applicationContext) {
                // Este callback es para el BlueDotOverlay
                LocationPredictor.predictLocation(System.currentTimeMillis(), predictionResult)
                predictionResult[0] to predictionResult[1]
            }

            prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            prefs.registerOnSharedPreferenceChangeListener(this)
            updateSettings()
        }

        private var burstFrames = 0

        private fun startShortBurst() {
            burstFrames = 3
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

                val lastKnown = LocationManager.lastKnownLocation()

                if (shouldRedrawFull(lastKnown)) {
                    Log.d("MapEngine", "🔄 Salto detectado o inicio. Burst activado.")
                    if (lastKnown != null) {
                        renderLat = lastKnown.latitude
                        renderLon = lastKnown.longitude
                        renderer.centerOn(renderLat, renderLon)
                    }
                    startShortBurst()
                } else {
                    Log.d("MapEngine", "⏸️ Posición estable. Dibujado único (Eco Mode).")
                    drawFrame()
                }

                LocationManager.start { location ->
                    handler.post {
                        onLocationUpdate(location)
                    }
                }
            } else {
                LocationManager.stop()
                stopAnimationLoop()
                isMoving = false
            }
        }

        private fun shouldRedrawFull(location: Location?): Boolean {
            if (location == null) return true
            if (renderLat == 0.0 && renderLon == 0.0) return true
            Location.distanceBetween(renderLat, renderLon, location.latitude, location.longitude, distanceResults)
            return distanceResults[0] > 2.0f
        }

        private fun onLocationUpdate(location: Location) {
            // Log.v("MapEngine", "🎨 Recibido en UI [Hilo: ${Thread.currentThread().name}]") // Comentado para no spammear
            LocationPredictor.update(location)

            if (!isVisible) return

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
                // Usamos el array reutilizable
                LocationPredictor.predictLocation(System.currentTimeMillis(), predictionResult)
                renderer.centerOn(predictionResult[0], predictionResult[1])
                drawFrame()
            }
        }

        private fun performRenderStep() {
            if (!isVisible) return

            val now = System.currentTimeMillis()

            // OPTIMIZACIÓN: Usar Array reutilizable en lugar de Pair
            LocationPredictor.predictLocation(now, predictionResult)
            val targetLat = predictionResult[0]
            val targetLon = predictionResult[1]

            renderLat += (targetLat - renderLat) * LERP_FACTOR
            renderLon += (targetLon - renderLon) * LERP_FACTOR

            renderer.centerOn(renderLat, renderLon)
            drawFrame()

            val shouldKeepAnimating = isMoving || debugForceAnim

            if (!shouldKeepAnimating) {
                isAnimating = false
            }
        }

        private val renderRunnable = object : Runnable {
            override fun run() {
                performRenderStep()
                if (isAnimating && !useVsync) {
                    handler.postDelayed(this, frameDelay)
                }
            }
        }

        private val vsyncCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // OPTIMIZACIÓN CRÍTICA: Pedir el siguiente frame ANTES de procesar el actual.
                // Esto asegura que no perdamos la ventana de tiempo del VSync si el dibujo tarda un poco.
                if (isAnimating && useVsync) {
                    choreographer.postFrameCallback(this)
                }

                performRenderStep()
            }
        }

        private fun startAnimationLoop() {
            if (!isAnimating) {
                isAnimating = true
                handler.removeCallbacks(renderRunnable)
                choreographer.removeFrameCallback(vsyncCallback)

                if (useVsync) {
                    Log.d("MapEngine", "🚀 Iniciando Loop VSync (Choreographer)")
                    choreographer.postFrameCallback(vsyncCallback)
                } else {
                    Log.d("MapEngine", "🚂 Iniciando Loop FPS Limitado (Handler: ${1000/frameDelay} fps)")
                    handler.post(renderRunnable)
                }
            }
        }

        private fun stopAnimationLoop() {
            isAnimating = false
            handler.removeCallbacks(renderRunnable)
            choreographer.removeFrameCallback(vsyncCallback)
        }

        private fun drawFrame() {
            val holder: SurfaceHolder = surfaceHolder
            var canvas: Canvas? = null
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    canvas = holder.lockHardwareCanvas()
                } else {
                    canvas = holder.lockCanvas()
                }

                if (canvas != null) {
                    // Nota: Measure/Layout es costoso, idealmente solo hacerlo si cambia el tamaño.
                    // Asumimos que la vista ya tiene el tamaño correcto.
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

                    if (showFps) {
                        val now = System.currentTimeMillis()
                        debugFrameCount++
                        if (now - debugLastTime >= 1000) {
                            debugActualFps = debugFrameCount
                            debugFrameCount = 0
                            debugLastTime = now

                            // OPTIMIZACIÓN: Crear el String solo 1 vez por segundo
                            val mode = if(useVsync) "VSYNC" else "LIMIT"
                            val renderType = if (canvas.isHardwareAccelerated) "GPU" else "CPU"
                            fpsTextCache = "FPS: $debugActualFps ($mode-$renderType)"
                        }
                        // Dibujar el texto cacheado
                        if (fpsTextCache.isNotEmpty()) {
                            canvas.drawText(fpsTextCache, 50f, 200f, fpsPaint)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MapEngine", "Error drawing frame", e)
            } finally {
                canvas?.let {
                    try {
                        holder.unlockCanvasAndPost(it)
                    } catch (e: Exception) {
                        Log.e("MapEngine", "Error unlocking canvas", e)
                    }
                }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == SettingsManager.KEY_MAP_STYLE) renderer.applyStyle(SettingsManager.getMapStyle(applicationContext))
            if (key == SettingsManager.KEY_ZOOM) renderer.setZoom(SettingsManager.getMapZoom(applicationContext).toFloat())
            if (key == SettingsManager.KEY_MOTION_SENSOR) LocationManager.updateSettings(applicationContext)
            if (key == SettingsManager.KEY_SHOW_BLUE_DOT || key == SettingsManager.KEY_SHOW_ACCURACY) renderer.updateBlueDot()

            if (key == SettingsManager.KEY_TARGET_FPS ||
                key == SettingsManager.KEY_VSYNC ||
                key == SettingsManager.KEY_DEBUG_ANIM ||
                key == SettingsManager.KEY_SHOW_FPS) {

                updatePerformanceConfig()

                if (isVisible) drawFrame()

                if (debugForceAnim && !isAnimating) {
                    startAnimationLoop()
                } else if (isAnimating) {
                    stopAnimationLoop()
                    startAnimationLoop()
                }
            }

            if (isVisible) drawFrame()
        }

        private fun updateSettings() {
            val ctx = applicationContext
            renderer.applyStyle(SettingsManager.getMapStyle(ctx))
            renderer.setZoom(SettingsManager.getMapZoom(ctx).toFloat())
            updatePerformanceConfig()
        }

        private fun updatePerformanceConfig() {
            val ctx = applicationContext
            useVsync = SettingsManager.isVsyncEnabled(ctx)
            debugForceAnim = SettingsManager.isDebugAnimEnabled(ctx)
            showFps = SettingsManager.showFpsCounter(ctx)

            val fps = SettingsManager.getTargetFps(ctx)
            frameDelay = (1000 / fps).toLong()

            Log.d("MapEngine", "⚙️ Config: VSync=$useVsync | FPS=$fps | ForceLoop=$debugForceAnim | ShowFPS=$showFps")
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            LocationManager.stop()
        }
    }
}