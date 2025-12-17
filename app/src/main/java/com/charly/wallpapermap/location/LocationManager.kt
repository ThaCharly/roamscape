package com.charly.wallpapermap.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.LinkedList
import kotlin.math.sqrt
import kotlin.math.abs

// --- Estados de la máquina ---
private enum class LocationState { CHILL, ACTIVE_PENDING, ACTIVE, DEEP_CHILL }

object LocationManager {

    private const val TAG = "LocationManager"

    // --- Umbrales ---
    private const val SPEED_THRESHOLD = 1.5f           // m/s - límite de movimiento
    private const val PENDING_FIXES = 3                // lecturas para confirmar ACTIVE
    private const val WINDOW_SIZE = 10                 // tamaño de ventana para promedio
    private const val STILL_THRESHOLD = 0.25f          // m/s² - umbral para considerar quietud
    private const val STILL_TIME = 20_000L             // ms - tiempo para entrar en DEEP_CHILL
    private const val REACTIVATE_DELAY = 10_000L        // ms - mínimo que permanece activo tras moverse

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var listener: ((Pair<Double, Double>) -> Unit)? = null
    private var isStarted = false

    // --- Estado y buffers ---
    private var currentState = LocationState.CHILL
    private var lastLocation: Location? = null
    private val speedHistory = LinkedList<Float>()

    // --- Acelerómetro ---
    private var sensorManager: SensorManager? = null
    private var lastMovementTime = 0L
    private var useAccelerometer = true
    private var accelListener: SensorEventListener? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // --- Requests de ubicación ---
    private fun highPriorityRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(100L)
            .setMaxUpdateDelayMillis(1600L)
            .build()

    private fun lowPriorityRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMaxUpdateDelayMillis(9000L)
            .build()

    fun setUseAccelerometer(enabled: Boolean) {
        useAccelerometer = enabled
        Log.d(TAG, "⚙️ Sensor de movimiento ${if (enabled) "activado" else "desactivado"}")

        if (!enabled) {
            sensorManager?.unregisterListener(accelListener)
            if (currentState == LocationState.DEEP_CHILL) {
                Log.d(TAG, "📡 Reactivando GPS: el sensor fue desactivado manualmente.")
                switchState(LocationState.CHILL)
            }
        } else registerAccelerometer()
    }

    // --- Registro del acelerómetro ---
    private fun registerAccelerometer() {
        if (!useAccelerometer) return
        val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            Log.w(TAG, "⚠️ No hay sensor de acelerómetro disponible.")
            return
        }

        accelListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val magnitude = sqrt(x * x + y * y + z * z)
                val delta = abs(magnitude - SensorManager.GRAVITY_EARTH)
                val now = System.currentTimeMillis()

                if (delta > STILL_THRESHOLD) {
                    lastMovementTime = now
                    if (currentState == LocationState.DEEP_CHILL) {
                        Log.d(TAG, "📱 Movimiento detectado. Reactivando GPS.")
                        switchState(LocationState.CHILL)
                    }
                } else if (now - lastMovementTime > STILL_TIME &&
                    currentState == LocationState.CHILL) {
                    Log.d(TAG, "😴 Inactividad prolongada. Entrando en DEEP_CHILL.")
                    switchState(LocationState.DEEP_CHILL)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "🪫 Acelerómetro registrado correctamente.")
    }

    @SuppressLint("MissingPermission")
    private fun switchState(newState: LocationState) {
        if (!isStarted || newState == currentState) return
        val ctx = context ?: return

        Log.d(TAG, "🔄 Cambiando estado: ${currentState.name} → ${newState.name}")
        currentState = newState

        fusedLocationClient.removeLocationUpdates(locationCallback!!)

        when (currentState) {
            LocationState.ACTIVE, LocationState.ACTIVE_PENDING ->
                fusedLocationClient.requestLocationUpdates(highPriorityRequest(), locationCallback!!, Looper.getMainLooper())

            LocationState.CHILL ->
                fusedLocationClient.requestLocationUpdates(lowPriorityRequest(), locationCallback!!, Looper.getMainLooper())

            LocationState.DEEP_CHILL -> {
                Log.d(TAG, "💤 GPS detenido por inactividad (modo DEEP_CHILL)")
                fusedLocationClient.removeLocationUpdates(locationCallback!!)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onUpdate: (Pair<Double, Double>) -> Unit) {
        if (isStarted) return
        val ctx = context ?: error("Context no inicializado en LocationManager.init()")

        if (!hasLocationPermission(ctx)) {
            Log.w(TAG, "🚫 Permisos de ubicación no concedidos")
            return
        }

        isStarted = true
        listener = onUpdate
        currentState = LocationState.CHILL
        lastLocation = null
        speedHistory.clear()
        LocationPredictor.reset()
        lastMovementTime = System.currentTimeMillis()

        if (useAccelerometer) registerAccelerometer()

        // --- Callback de ubicación ---
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val speed = location.speed

                speedHistory.add(speed)
                if (speedHistory.size > WINDOW_SIZE) speedHistory.removeFirst()

                when (currentState) {
                    LocationState.CHILL -> {
                        if (speed >= SPEED_THRESHOLD) {
                            Log.d(TAG, "👟 Velocidad detectada ($speed m/s). Entrando en ACTIVE_PENDING")
                            switchState(LocationState.ACTIVE_PENDING)
                        }
                    }

                    LocationState.ACTIVE_PENDING -> {
                        val recent = speedHistory.takeLast(PENDING_FIXES)
                        if (recent.all { it > SPEED_THRESHOLD }) {
                            Log.d(TAG, "✅ Movimiento confirmado. Pasando a ACTIVE")
                            switchState(LocationState.ACTIVE)
                        } else if (recent.all { it < SPEED_THRESHOLD }) {
                            Log.d(TAG, "❌ Falsa alarma. Volviendo a CHILL")
                            switchState(LocationState.CHILL)
                        }
                    }

                    LocationState.ACTIVE -> {
                        val avgSpeed = speedHistory.average().toFloat()
                        if (avgSpeed < SPEED_THRESHOLD) {
                            Log.d(TAG, "🛑 Promedio bajo ($avgSpeed m/s). Pasando a CHILL")
                            switchState(LocationState.CHILL)
                        }
                    }

                    else -> {} // DEEP_CHILL no hace nada
                }

                val smoothed = LocationPredictor.update(location.latitude, location.longitude)
                if (lastLocation != null) {
                    val bearing = LocationPredictor.bearingTo(
                        lastLocation!!.latitude, lastLocation!!.longitude,
                        location.latitude, location.longitude
                    )
                    LocationPredictor.pushBearing(bearing)
                }
                lastLocation = location
                listener?.invoke(smoothed)

                Log.d(TAG, "🌍 Ubicación: ${smoothed.first}, ${smoothed.second} (Speed: $speed m/s, State: $currentState)")
            }
        }

        fusedLocationClient.requestLocationUpdates(lowPriorityRequest(), locationCallback!!, Looper.getMainLooper())
        Log.d(TAG, "📡 LocationManager iniciado (Modo: CHILL)")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            Log.d(TAG, "🛑 Actualizaciones de ubicación detenidas")
        }
        accelListener?.let { sensorManager?.unregisterListener(it) }
        locationCallback = null
        listener = null
        Log.d(TAG, "❌ LocationManager detenido")
    }

    fun lastKnownLocation(): Pair<Double, Double>? = LocationPredictor.getLastKnown()

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}
