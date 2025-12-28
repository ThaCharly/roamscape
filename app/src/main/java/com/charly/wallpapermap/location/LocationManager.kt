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
import kotlin.math.abs

object LocationManager {

    private const val TAG = "LocationManager"

    // --- CONFIGURACIÓN DEL FILTRO ---
    private const val NOISE_SPEED_THRESHOLD = 0.5f // m/s
    private const val SIGNIFICANT_DISTANCE = 3.0f  // metros
    private const val MAX_IGNORED_FIXES = 20       // "Heartbeat" forzado

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var listener: ((Pair<Double, Double>) -> Unit)? = null
    private var isStarted = false

    // Estado interno
    private var lastValidLocation: Location? = null
    private var ignoredFixesCount = 0

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
    }

    // Request ÚNICA y CONSTANTE (1s)
    // Sin modos raros. Le pedimos al GPS ritmo constante.
    private fun createLocationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(0L) // Aceptamos data cada medio segundo si pinta
            .setMaxUpdateDelayMillis(0L)       // Entregalo YA
            .setWaitForAccurateLocation(false)
            .build()

    fun setUseAccelerometer(enabled: Boolean) { }

    @SuppressLint("MissingPermission")
    fun start(onUpdate: (Pair<Double, Double>) -> Unit) {
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

        // 1. CACHÉ INMEDIATO (Para tapar el arranque en frío)
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                Log.d(TAG, "💾 CACHÉ RECUPERADO: ${location.latitude}, ${location.longitude}")
                processValidLocation(location, "💾 CACHE")
            } else {
                Log.d(TAG, "🤷‍♂️ Caché vacío. Esperando al GPS...")
            }
        }

        // 2. INICIO DE UPDATES (Ritmo constante)
        Log.d(TAG, "🚀 INICIANDO GPS: 1s constante. Filtrado activo.")

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val rawLocation = result.lastLocation ?: return
                // Siempre aplicamos el filtro, desde el primer segundo.
                filterAndProcess(rawLocation)
            }
        }

        fusedLocationClient.requestLocationUpdates(createLocationRequest(), locationCallback!!, Looper.getMainLooper())
    }

    private fun filterAndProcess(rawLocation: Location) {
        val isNoiseSpeed = rawLocation.speed < NOISE_SPEED_THRESHOLD
        val dist = lastValidLocation?.distanceTo(rawLocation) ?: 100f
        val isSignificantDistance = dist >= SIGNIFICANT_DISTANCE

        // Si es ruido (lento Y cerca)
        if (isNoiseSpeed && !isSignificantDistance) {
            ignoredFixesCount++

            // Check de paciencia (Heartbeat)
            if (ignoredFixesCount > MAX_IGNORED_FIXES) {
                Log.w(TAG, "⚠️ FORCED UPDATE (${ignoredFixesCount})")
                processValidLocation(rawLocation, "⏰ FORZADO")
            } else {
                Log.v(TAG, "🗑️ Ruido descartado ($ignoredFixesCount/$MAX_IGNORED_FIXES) - Vel: ${rawLocation.speed}, Dist: $dist")
            }
            return
        }

        // Si pasó el filtro
        processValidLocation(rawLocation, "✅ VALID")
    }

    private fun processValidLocation(location: Location, source: String) {
        ignoredFixesCount = 0
        lastValidLocation = location

        val smoothed = LocationPredictor.update(location.latitude, location.longitude)

        listener?.invoke(smoothed)
        Log.d(TAG, "📍 $source: ${smoothed.first}, ${smoothed.second} (Vel: ${location.speed})")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        listener = null
        Log.d(TAG, "🛑 LocationManager detenido")
    }

    fun lastKnownLocation(): Pair<Double, Double>? = LocationPredictor.getLastKnown()

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}