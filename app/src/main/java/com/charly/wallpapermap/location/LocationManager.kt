package com.charly.wallpapermap.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Log
import com.charly.wallpapermap.settings.SettingsManager
import com.google.android.gms.location.*
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.Manifest
import kotlin.math.abs
import kotlin.math.sqrt

object LocationManager : SensorEventListener {

    private const val TAG = "LocationManager"

    // --- CONFIGURACIÓN ---
    private const val NOISE_SPEED_THRESHOLD = 0.5f
    private const val SIGNIFICANT_DISTANCE = 3.0f
    private const val MAX_IGNORED_FIXES = 30

    // --- CONFIGURACIÓN DE SIESTA (SLEEP MODE) ---
    private const val MOTION_THRESHOLD = 0.5f
    private const val SOFT_SLEEP_TIMEOUT_MS = 30_000L // 30s
    private const val HARD_SLEEP_TIMEOUT_MS = 90_000L // 90s

    // Configuración del Buffer de Confianza GPS
    private const val GPS_MOTION_CONFIDENCE_THRESHOLD = 3 // Necesitamos 3 "puntos" para confiar
    private var gpsMotionConfidence = 0 // Acumulador (0 a 3)

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sensorManager: SensorManager? = null

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

    // Timestamp del último movimiento detectado (Sensor O GPS)
    private var lastMovementTimestamp: Long = 0L

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private fun createLocationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(0L)
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

        isStarted = true
        isGpsPaused = false
        isInSoftSleep = false
        gpsMotionConfidence = 0 // Reiniciamos confianza
        listener = onUpdate
        lastValidLocation = null
        ignoredFixesCount = 0
        useCompassMode = true

        useMotionSensorFeature = SettingsManager.isMotionSensorEnabled(ctx)
        lastMovementTimestamp = System.currentTimeMillis()

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val originalBearing = location.bearing
                if (location.speed < 1.5f && hasCompass && currentCompassBearing != 0f) {
                    location.bearing = currentCompassBearing
                }
                processValidLocation(location, "💾 CACHE", originalBearing)
            } else {
                Log.d(TAG, "🤷‍♂️ Caché vacío. Esperando GPS...")
            }
        }

        startGpsUpdates()
        startSensors()

        Log.d(TAG, "🚀 LocationManager iniciado. SensorMovimiento: $useMotionSensorFeature")
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (locationCallback != null) return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val rawLocation = result.lastLocation ?: return

                if (useMotionSensorFeature) {
                    val timeSinceMove = System.currentTimeMillis() - lastMovementTimestamp

                    // A. Hard Sleep Check (> 90s)
                    if (timeSinceMove > HARD_SLEEP_TIMEOUT_MS) {
                        Log.d(TAG, "💤 HARD SLEEP: Sin movimiento por ${HARD_SLEEP_TIMEOUT_MS/1000}s. Apagando GPS.")
                        stopGpsUpdates()
                        isGpsPaused = true
                        return
                    }

                    // B. Soft Sleep Check (> 30s)
                    if (timeSinceMove > SOFT_SLEEP_TIMEOUT_MS) {
                        if (!isInSoftSleep) {
                            Log.d(TAG, "💤 SOFT SLEEP: Sin movimiento por ${SOFT_SLEEP_TIMEOUT_MS/1000}s. Entrando en modo reposo (Ignorando fixes).")
                            isInSoftSleep = true
                        }
                        // Seguimos procesando para ver si llega un GPS válido que nos despierte,
                        // PERO solo "contamos" confianza, no notificamos a la UI a menos que despierte.
                    } else {
                        if (isInSoftSleep) {
                            Log.d(TAG, "⚡ WAKE UP: Tiempo de movimiento reseteado externamente. Saliendo de Soft Sleep.")
                            isInSoftSleep = false
                        }
                    }
                }

                filterAndProcess(rawLocation)
            }
        }
        fusedLocationClient.requestLocationUpdates(createLocationRequest(), locationCallback!!, Looper.getMainLooper())
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
                sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
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

    fun updateSettings(ctx: Context) {
        useMotionSensorFeature = SettingsManager.isMotionSensorEnabled(ctx)
        // Si lo apagaron, nos aseguramos de despertar todo por si estábamos durmiendo
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

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return

        // --- A. BRÚJULA ---
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            e.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {

            SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            currentCompassBearing = currentCompassBearing * 0.9f + azimuth * 0.1f
        }

        // --- B. DETECCIÓN DE MOVIMIENTO (Sleep Mode) ---
        if (useMotionSensorFeature &&
            (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION || e.sensor.type == Sensor.TYPE_ACCELEROMETER)) {

            val x = e.values[0]
            val y = e.values[1]
            val z = e.values[2]
            val magnitude = sqrt((x*x + y*y + z*z).toDouble()).toFloat()

            if (magnitude > MOTION_THRESHOLD) {
                lastMovementTimestamp = System.currentTimeMillis()

                // Reiniciamos confianza GPS también, porque el sensor ya confirmó movimiento
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

    // --- LÓGICA DE FILTRO Y BEARINGS ---
    private var useCompassMode = true

    private fun filterAndProcess(rawLocation: Location) {
        val originalGpsBearing = rawLocation.bearing

        // Histéresis Brújula vs GPS
        if (useCompassMode && rawLocation.speed > 2.0f) {
            useCompassMode = false
        } else if (!useCompassMode && rawLocation.speed < 1.0f) {
            useCompassMode = true
        }

        if (useCompassMode && hasCompass) {
            rawLocation.bearing = currentCompassBearing
        }

        // --- FILTRO DE RUIDO ---
        val dist = lastValidLocation?.distanceTo(rawLocation) ?: 100f
        val isNoiseSpeed = rawLocation.speed < NOISE_SPEED_THRESHOLD
        val isSignificantDistance = dist >= SIGNIFICANT_DISTANCE

        if (isNoiseSpeed && !isSignificantDistance) {
            // ES RUIDO (o estamos quietos)

            // Bajamos confianza de movimiento GPS (Buffer se vacía de a poco)
            if (gpsMotionConfidence > 0) gpsMotionConfidence--

            ignoredFixesCount++
            if (ignoredFixesCount > MAX_IGNORED_FIXES) {
                // Heartbeat forzado
                if (!isInSoftSleep) {
                    processValidLocation(rawLocation, "⏰ FORZADO", originalGpsBearing)
                }
            } else {
                Log.v(TAG, "🗑️ Ruido ($ignoredFixesCount) | Vel: ${"%.2f".format(rawLocation.speed)} | ConfianzaGPS: $gpsMotionConfidence")
            }
            return
        }

        // ES VÁLIDO (Hay movimiento aparente)

        // --- BUFFER DE CONFIANZA ---
        // Sumamos confianza hasta llegar al tope
        if (gpsMotionConfidence < GPS_MOTION_CONFIDENCE_THRESHOLD) {
            gpsMotionConfidence++
        }

        // Si llenamos el buffer, confirmamos movimiento y reseteamos el timer de sueño
        if (gpsMotionConfidence >= GPS_MOTION_CONFIDENCE_THRESHOLD) {
            if (useMotionSensorFeature) {
                // Actualizamos timestamp para que no se duerma
                val now = System.currentTimeMillis()
                // Logueamos solo si estábamos en peligro de dormirnos o dormidos
                if (isInSoftSleep || (now - lastMovementTimestamp > 5000)) {
                    // Si estábamos dormidos, avisamos fuerte
                    if (isInSoftSleep) {
                        Log.d(TAG, "🛰️ GPS WAKE UP: 3 fixes válidos seguidos. Saliendo de Soft Sleep.")
                    }
                }
                lastMovementTimestamp = now
                isInSoftSleep = false
            }
        }

        // Si estamos en Soft Sleep y el buffer no está lleno, IGNORAMOS el fix.
        // Esto evita que un solo salto de GPS pinte el mapa cuando debería estar quieto.
        if (isInSoftSleep && gpsMotionConfidence < GPS_MOTION_CONFIDENCE_THRESHOLD) {
            Log.v(TAG, "💤 Soft Sleep: Ignorando fix válido aislado (Confianza: $gpsMotionConfidence/3)")
            return
        }

        processValidLocation(rawLocation, "✅ VALID", originalGpsBearing)
    }

    private fun processValidLocation(location: Location, source: String, originalGpsBearing: Float) {
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

        Log.d(TAG, "📍 $source | " +
                "🌍 ${location.latitude}, ${location.longitude} | " +
                "📏 Dist: ${"%.2f".format(dist)}m | " +
                "🧭 GPS: ${"%.1f".format(originalGpsBearing)}° vs Sensor: ${"%.1f".format(currentCompassBearing)}° -> USED: ${"%.1f".format(location.bearing)}° | " +
                "💨 Vel: ${"%.2f".format(location.speed)}m/s | " +
                "🚀 Acc: ${"%.2f".format(accel)}m/s²")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        stopGpsUpdates()
        listener = null
        sensorManager?.unregisterListener(this)
        Log.d(TAG, "🛑 LocationManager detenido (Todo apagado)")
    }

    fun lastKnownLocation(): Location? = lastValidLocation

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}