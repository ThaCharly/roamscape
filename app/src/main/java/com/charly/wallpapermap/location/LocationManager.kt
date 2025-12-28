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
import com.google.android.gms.location.*
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.Manifest
import kotlin.math.abs

object LocationManager : SensorEventListener {

    private const val TAG = "LocationManager"

    // Configuración del filtro
    private const val NOISE_SPEED_THRESHOLD = 0.5f
    private const val SIGNIFICANT_DISTANCE = 3.0f
    private const val MAX_IGNORED_FIXES = 5 // debug, luego subir a 30

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sensorManager: SensorManager? = null

    // Callbacks
    private var locationCallback: LocationCallback? = null
    private var listener: ((Location) -> Unit)? = null

    private var isStarted = false
    private var lastValidLocation: Location? = null
    private var ignoredFixesCount = 0

    // --- BRÚJULA HÍBRIDA ---
    private var currentCompassBearing: Float = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var hasCompass = false

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // Configuración "Samsung Killer": Latencia CERO.
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
        listener = onUpdate
        lastValidLocation = null
        ignoredFixesCount = 0

        // 1. RECUPERAR CACHÉ
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                // Guardamos el original antes de tocar nada
                val originalBearing = location.bearing

                // Inyectamos el bearing del sensor si corresponde
                if (location.speed < 1.5f && hasCompass && currentCompassBearing != 0f) {
                    location.bearing = currentCompassBearing
                }

                // Pasamos el original para el log
                processValidLocation(location, "💾 CACHE", originalBearing)
            } else {
                Log.d(TAG, "🤷‍♂️ Caché vacío. Esperando GPS...")
            }
        }

        // 2. Iniciar GPS
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val rawLocation = result.lastLocation ?: return
                filterAndProcess(rawLocation)
            }
        }
        fusedLocationClient.requestLocationUpdates(createLocationRequest(), locationCallback!!, Looper.getMainLooper())

        // 3. Iniciar Brújula
        startSensors()

        Log.d(TAG, "🚀 LocationManager: Híbrido Activo (GPS + Brújula)")
    }

    private fun startSensors() {
        sensorManager?.let { sm ->
            val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

            if (rotationSensor != null) {
                sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                hasCompass = true
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

            // Filtro suave (Lerp)
            currentCompassBearing = currentCompassBearing * 0.9f + azimuth * 0.1f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun filterAndProcess(rawLocation: Location) {
        // Guardamos el bearing ORIGINAL del GPS antes de sobrescribirlo
        val originalGpsBearing = rawLocation.bearing

        // --- INYECCIÓN HÍBRIDA (1.5 m/s) ---
        if (rawLocation.speed < 1.5f && hasCompass) {
            rawLocation.bearing = currentCompassBearing
        }

        // --- FILTRO DE RUIDO ---
        val dist = lastValidLocation?.distanceTo(rawLocation) ?: 100f
        val isNoiseSpeed = rawLocation.speed < NOISE_SPEED_THRESHOLD
        val isSignificantDistance = dist >= SIGNIFICANT_DISTANCE

        if (isNoiseSpeed && !isSignificantDistance) {
            ignoredFixesCount++
            if (ignoredFixesCount > MAX_IGNORED_FIXES) {
                processValidLocation(rawLocation, "⏰ FORZADO", originalGpsBearing)
            } else {
                Log.v(TAG, "🗑️ Ruido ($ignoredFixesCount) | Vel: ${"%.2f".format(rawLocation.speed)}")
            }
            return
        }
        processValidLocation(rawLocation, "✅ VALID", originalGpsBearing)
    }

    // Agregamos originalGpsBearing como parámetro opcional (default al actual si no se pasa)
    private fun processValidLocation(location: Location, source: String, originalGpsBearing: Float) {
        // --- CÁLCULOS PARA LOG ---
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

        // LOG CIENTÍFICO FINAL 🧪
        // Mostramos:
        // 1. GPS Real (Lo que vino del satélite)
        // 2. Sensor (Lo que dice la brújula)
        // 3. USED (Cuál quedó finalmente en el objeto location)
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
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        listener = null
        sensorManager?.unregisterListener(this)
        Log.d(TAG, "🛑 LocationManager detenido")
    }

    fun lastKnownLocation(): Location? = lastValidLocation

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}