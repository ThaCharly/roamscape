package com.charly.wallpapermap.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager as AndroidLocationManager
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.charly.wallpapermap.settings.SettingsManager
import com.google.android.gms.location.*
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.Manifest
import kotlin.math.sqrt

object LocationManager : SensorEventListener {

    private const val TAG = "LocationManager"

    // --- CONFIGURACIÓN ---
    private const val NOISE_SPEED_THRESHOLD = 0.5f
    private const val SIGNIFICANT_DISTANCE = 3.0f
    private const val MAX_IGNORED_FIXES = 20

    // --- CONFIGURACIÓN DE SIESTA (SLEEP MODE) ---
    private const val MOTION_THRESHOLD = 0.5f
    private const val SOFT_SLEEP_TIMEOUT_MS = 30_000L // 30s
    private const val HARD_SLEEP_TIMEOUT_MS = 90_000L // 90s

    // Configuración del Buffer de Confianza GPS
    private const val GPS_MOTION_CONFIDENCE_THRESHOLD = 3
    private var gpsMotionConfidence = 0

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sensorManager: SensorManager? = null

    // NUEVO: Hilo dedicado para el procesamiento de GPS
    private var locationThread: HandlerThread? = null

    // Callbacks
    private var locationCallback: LocationCallback? = null
    private var listener: ((Location) -> Unit)? = null

    // Estado GPS
    private var isStarted = false
    private var isGpsPaused = false
    private var isInSoftSleep = false
    private var lastValidLocation: Location? = null
    private var ignoredFixesCount = 0

    // Estado Sensores
    private var hasCompass = false
    private var hasAccelerometer = false
    private var useMotionSensorFeature = false

    // Datos de Sensores
    private var currentCompassBearing: Float = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var lastMovementTimestamp: Long = 0L

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private fun createLocationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L) // Bajamos un poco para no saturar el hilo
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()

    fun setUseAccelerometer(enabled: Boolean) { }

    @SuppressLint("MissingPermission")
    fun start(onUpdate: (Location) -> Unit) {
        if (isStarted) return
        val ctx = context ?: error("Context no inicializado")

        if (!hasLocationPermission(ctx)) {
            Log.w(TAG, "🚫 Permisos de ubicación no concedidos")
            return
        }

        // --- PUNTO 1: El Misterio del Caché y el GPS Apagado ---
        val androidLocManager = ctx.getSystemService(Context.LOCATION_SERVICE) as AndroidLocationManager
        val isGpsEnabled = androidLocManager.isProviderEnabled(AndroidLocationManager.GPS_PROVIDER) ||
                androidLocManager.isProviderEnabled(AndroidLocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled) {
            Log.w(TAG, "🚫 Ubicación desactivada por usuario. Cache inaccesible.")
        }
        // --------------------------------------------------------

        isStarted = true
        isGpsPaused = false
        isInSoftSleep = false
        gpsMotionConfidence = 0
        listener = onUpdate
        lastValidLocation = null
        ignoredFixesCount = 0

        // Resetear estado del modo brújula al iniciar
        useCompassMode = true

        useMotionSensorFeature = SettingsManager.isMotionSensorEnabled(ctx)
        lastMovementTimestamp = System.currentTimeMillis()

        // --- PUNTO 2: Gestión de Hilos (Inicio) ---
        locationThread = HandlerThread("RoamScapeLocationThread").apply { start() }
        // ------------------------------------------

        // Solo pedimos caché si el sistema lo permite
        if (isGpsEnabled) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val originalBearing = if (location.hasBearing()) location.bearing else 0f
                    if (location.speed < 1.5f && hasCompass && currentCompassBearing != 0f) {
                        location.bearing = currentCompassBearing
                    }
                    processValidLocation(location, "💾 CACHE", originalBearing)
                } else {
                    Log.d(TAG, "🤷‍♂️ Caché vacío. Esperando GPS...")
                }
            }.addOnFailureListener {
                Log.e(TAG, "❌ Error accediendo al caché: ${it.message}")
            }
        }

        startGpsUpdates()
        startSensors()

        Log.d(TAG, "🚀 LocationManager iniciado en hilo dedicado.")
    }

    fun updateSettings(ctx: Context) {
        val newState = SettingsManager.isMotionSensorEnabled(ctx)
        if (newState != useMotionSensorFeature) {
            useMotionSensorFeature = newState

            sensorManager?.unregisterListener(this)
            startSensors()

            if (!useMotionSensorFeature) {
                lastMovementTimestamp = System.currentTimeMillis()
                if (isGpsPaused) {
                    isGpsPaused = false
                    startGpsUpdates()
                }
                isInSoftSleep = false
            }
            Log.d(TAG, "⚙️ Configuración actualizada. MotionSensor: $useMotionSensorFeature")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (locationCallback != null) return

        // --- PUNTO 2: Gestión de Hilos (Uso) ---
        val threadLooper = locationThread?.looper ?: return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val rawLocation = result.lastLocation ?: return

                if (useMotionSensorFeature) {
                    val timeSinceMove = System.currentTimeMillis() - lastMovementTimestamp

                    if (timeSinceMove > HARD_SLEEP_TIMEOUT_MS) {
                        Log.d(TAG, "💤 HARD SLEEP: Sin movimiento por ${HARD_SLEEP_TIMEOUT_MS/1000}s. Apagando GPS.")
                        stopGpsUpdates()
                        isGpsPaused = true
                        return
                    }

                    if (timeSinceMove > SOFT_SLEEP_TIMEOUT_MS) {
                        if (!isInSoftSleep) {
                            Log.d(TAG, "💤 SOFT SLEEP: Sin movimiento por ${SOFT_SLEEP_TIMEOUT_MS/1000}s. Entrando en modo reposo.")
                            isInSoftSleep = true
                        }
                    } else {
                        if (isInSoftSleep) {
                            Log.d(TAG, "⚡ WAKE UP: Tiempo de movimiento reseteado externamente.")
                            isInSoftSleep = false
                        }
                    }
                }
                filterAndProcess(rawLocation)
            }
        }

        fusedLocationClient.requestLocationUpdates(createLocationRequest(), locationCallback!!, threadLooper)
    }

    private fun stopGpsUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun startSensors() {
        sensorManager?.let { sm ->
            val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

            if (rotationSensor != null) {
                sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL)
                hasCompass = true
            }

            if (useMotionSensorFeature) {
                val linearAccel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                    ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

                if (linearAccel != null) {
                    sm.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_NORMAL)
                    hasAccelerometer = true
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return

        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            e.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {

            SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            currentCompassBearing = currentCompassBearing * 0.9f + azimuth * 0.1f
        }

        if (useMotionSensorFeature &&
            (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION || e.sensor.type == Sensor.TYPE_ACCELEROMETER)) {

            val x = e.values[0]
            val y = e.values[1]
            val z = e.values[2]
            val magnitude = sqrt((x*x + y*y + z*z).toDouble()).toFloat()

            if (magnitude > MOTION_THRESHOLD) {
                lastMovementTimestamp = System.currentTimeMillis()

                gpsMotionConfidence = GPS_MOTION_CONFIDENCE_THRESHOLD

                if (isGpsPaused) {
                    Log.d(TAG, "⚡ SENSOR WAKE UP: Movimiento físico detectado. Reactivando GPS.")
                    isGpsPaused = false
                    startGpsUpdates()
                } else if (isInSoftSleep) {
                    Log.d(TAG, "⚡ SENSOR WAKE UP: Movimiento físico detectado. Saliendo de Soft Sleep.")
                    isInSoftSleep = false
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private var useCompassMode = true

    private fun filterAndProcess(rawLocation: Location) {
        var originalGpsBearing = rawLocation.bearing
        var isBearingCalculated = false

        if (!rawLocation.hasBearing() && lastValidLocation != null) {
            val distCheck = lastValidLocation!!.distanceTo(rawLocation)
            if (distCheck > 0.5f) {
                originalGpsBearing = lastValidLocation!!.bearingTo(rawLocation)
                if (originalGpsBearing < 0) originalGpsBearing += 360f
                isBearingCalculated = true
                rawLocation.bearing = originalGpsBearing
            }
        }

        if (useCompassMode && rawLocation.speed > 2.0f) {
            useCompassMode = false
        } else if (!useCompassMode && rawLocation.speed < 1.0f) {
            useCompassMode = true
        }

        if (useCompassMode && hasCompass) {
            rawLocation.bearing = currentCompassBearing
        }

        val dist = lastValidLocation?.distanceTo(rawLocation) ?: 100f
        val isNoiseSpeed = rawLocation.speed < NOISE_SPEED_THRESHOLD
        val isSignificantDistance = dist >= SIGNIFICANT_DISTANCE

        val wasMoving = (lastValidLocation?.speed ?: 0f) > NOISE_SPEED_THRESHOLD
        val isJustStopping = wasMoving && isNoiseSpeed

        if (isNoiseSpeed && !isSignificantDistance && !isJustStopping) {
            if (gpsMotionConfidence > 0) gpsMotionConfidence--
            ignoredFixesCount++
            if (ignoredFixesCount > MAX_IGNORED_FIXES) {
                if (!isInSoftSleep) {
                    processValidLocation(rawLocation, "⏰ FORZADO", originalGpsBearing, isBearingCalculated)
                }
            } else {
                Log.v(TAG, "🗑️ Ruido ($ignoredFixesCount) | Vel: ${"%.2f".format(rawLocation.speed)} | Confianza: $gpsMotionConfidence")
            }
            return
        }

        if (isJustStopping) {
            lastValidLocation?.let { last ->
                rawLocation.latitude = last.latitude
                rawLocation.longitude = last.longitude
            }
        }

        if (gpsMotionConfidence < GPS_MOTION_CONFIDENCE_THRESHOLD) {
            gpsMotionConfidence++
        }

        if (gpsMotionConfidence >= GPS_MOTION_CONFIDENCE_THRESHOLD) {
            if (useMotionSensorFeature) {
                val now = System.currentTimeMillis()
                if (isInSoftSleep || (now - lastMovementTimestamp > 5000)) {
                    if (isInSoftSleep) {
                        Log.d(TAG, "🛰️ GPS WAKE UP: 3 fixes válidos seguidos.")
                    }
                }
                lastMovementTimestamp = now
                isInSoftSleep = false
            }
        }

        if (isInSoftSleep && gpsMotionConfidence < GPS_MOTION_CONFIDENCE_THRESHOLD) {
            return
        }

        val tag = if (isJustStopping) "🛑 STOP" else "✅ VALID"
        processValidLocation(rawLocation, tag, originalGpsBearing, isBearingCalculated)
    }

    // --- REFORMATEADO: Volvimos al log detallado original + soporte isCalculated ---
    private fun processValidLocation(location: Location, source: String, originalGpsBearing: Float, isCalculated: Boolean = false) {
        var accel = 0.0
        var dist = 0f

        lastValidLocation?.let { prev ->
            dist = prev.distanceTo(location)
            val timeDelta = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            if (timeDelta > 0) {
                accel = (location.speed - prev.speed) / timeDelta
            }
        }

        ignoredFixesCount = 0
        lastValidLocation = location

        listener?.invoke(location)

        val bearingSource = if (isCalculated) "Calc" else "Raw"

        Log.d(TAG, "📍 $source | " +
                "🌍 ${location.latitude}, ${location.longitude} | " +
                "📏 Dist: ${"%.2f".format(dist)}m | " +
                "🧭 GPS($bearingSource): ${"%.1f".format(originalGpsBearing)}° vs Sensor: ${"%.1f".format(currentCompassBearing)}° -> USED: ${"%.1f".format(location.bearing)}° | " +
                "💨 Vel: ${"%.2f".format(location.speed)}m/s | " +
                "🚀 Acc: ${"%.2f".format(accel)}m/s²")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        stopGpsUpdates()
        listener = null
        sensorManager?.unregisterListener(this)

        // --- PUNTO 2: Limpieza de Hilos ---
        locationThread?.quitSafely()
        locationThread = null

        Log.d(TAG, "🛑 LocationManager detenido.")
    }

    fun lastKnownLocation(): Location? = lastValidLocation

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}