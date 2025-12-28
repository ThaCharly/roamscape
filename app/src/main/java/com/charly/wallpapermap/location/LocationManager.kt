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

object LocationManager {

    private const val TAG = "LocationManager"

    // Configuración del filtro
    private const val NOISE_SPEED_THRESHOLD = 0.5f // m/s
    private const val SIGNIFICANT_DISTANCE = 3.0f  // metros
    private const val MAX_IGNORED_FIXES = 20

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var listener: ((Location) -> Unit)? = null

    private var isStarted = false
    private var lastValidLocation: Location? = null
    private var ignoredFixesCount = 0

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
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

        // 1. Caché Inmediato
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                // Para el caché no calculamos aceleración porque no tenemos previo
                processValidLocation(location, "💾 CACHE")
            }
        }

        Log.d(TAG, "🚀 INICIANDO GPS: Modo Predictivo (Latencia 0)")

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val rawLocation = result.lastLocation ?: return
                filterAndProcess(rawLocation)
            }
        }

        fusedLocationClient.requestLocationUpdates(createLocationRequest(), locationCallback!!, Looper.getMainLooper())
    }

    private fun filterAndProcess(rawLocation: Location) {
        // Cálculos para el Log de descarte
        val dist = lastValidLocation?.distanceTo(rawLocation) ?: 0f

        val isNoiseSpeed = rawLocation.speed < NOISE_SPEED_THRESHOLD
        val isSignificantDistance = dist >= SIGNIFICANT_DISTANCE

        if (isNoiseSpeed && !isSignificantDistance) {
            ignoredFixesCount++
            if (ignoredFixesCount > MAX_IGNORED_FIXES) {
                Log.w(TAG, "⚠️ FORCED UPDATE ($ignoredFixesCount) | Dist: ${"%.2f".format(dist)}m")
                processValidLocation(rawLocation, "⏰ FORZADO")
            } else {
                Log.v(TAG, "🗑️ Ruido ($ignoredFixesCount) | Vel: ${"%.2f".format(rawLocation.speed)}m/s | Dist: ${"%.2f".format(dist)}m")
            }
            return
        }
        processValidLocation(rawLocation, "✅ VALID")
    }

    private fun processValidLocation(location: Location, source: String) {
        // --- CÁLCULO DE FÍSICA PARA LOGS ---
        val prev = lastValidLocation
        var dist = 0f
        var accel = 0.0

        if (prev != null) {
            dist = prev.distanceTo(location)

            // Delta Tiempo en segundos
            val timeDelta = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0

            // Aceleración = (VelFinal - VelInicial) / Tiempo
            if (timeDelta > 0) {
                accel = (location.speed - prev.speed) / timeDelta
            }
        }

        // Actualizamos estado
        ignoredFixesCount = 0
        lastValidLocation = location

        // Notificamos
        listener?.invoke(location)

        // LOG CIENTÍFICO 🧪
        Log.d(TAG, "📍 $source " +
                "| 🌍 ${location.latitude}, ${location.longitude} " +
                "| 📏 Dist: ${"%.2f".format(dist)}m " +
                "| 🧭 Bear: ${location.bearing}° " +
                "| 💨 Vel: ${"%.2f".format(location.speed)}m/s " +
                "| 🚀 Acc: ${"%.2f".format(accel)}m/s²")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        listener = null
        Log.d(TAG, "🛑 LocationManager detenido")
    }

    fun lastKnownLocation(): Location? {
        return lastValidLocation
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}