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
import java.util.LinkedList
import kotlin.math.abs

object LocationManager {

    private const val TAG = "LocationManager"

    // CONFIGURACIÓN DEL FILTRO
    private const val NOISE_SPEED_THRESHOLD = 0.7f // m/s (aprox 1.8 km/h). Menos que esto es ruido.
    private const val SIGNIFICANT_DISTANCE = 3.0f  // metros. Si te moviste menos de 2m, ni me gasto.

    private var context: Context? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var listener: ((Pair<Double, Double>) -> Unit)? = null
    private var isStarted = false

    // Guardamos la última ubicación VALIDADA para comparar
    private var lastValidLocation: Location? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(ctx)
    }

    // Pedimos updates a 1 segundo siempre.
    // El ahorro de batería lo hacemos descartando datos, no apagando la radio.
    private fun activeRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
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
        LocationPredictor.reset()
        lastValidLocation = null

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val rawLocation = result.lastLocation ?: return

                // --- 🗑️ EL FILTRO DE BASURA 🗑️ ---

                // 1. Si la velocidad es insignificante (ruido estático)
                // Y TAMBIÉN la distancia recorrida desde la última vez es ridícula...
                if (rawLocation.speed < NOISE_SPEED_THRESHOLD) {
                    val dist = lastValidLocation?.distanceTo(rawLocation) ?: 100f
                    if (dist < SIGNIFICANT_DISTANCE) {
                        // Es ruido. Lo tiramos.
                        // No actualizamos lastValidLocation.
                        // No llamamos al listener.
                        // La UI sigue durmiendo.
                        Log.v(TAG, "🗑️ Ruido descartado (Vel: ${rawLocation.speed}, Dist: $dist)")
                        return
                    }
                }

                // --- SI PASA EL FILTRO ---
                lastValidLocation = rawLocation

                // Pasamos al predictor (que tiene ALPHA 0.7 para reaccionar rápido)
                val smoothed = LocationPredictor.update(rawLocation.latitude, rawLocation.longitude)

                // Despertamos a la UI
                listener?.invoke(smoothed)
                Log.d(TAG, "📍 FIX VÁLIDO: ${smoothed.first}, ${smoothed.second} (Vel: ${rawLocation.speed})")
            }
        }

        fusedLocationClient.requestLocationUpdates(activeRequest(), locationCallback!!, Looper.getMainLooper())
        Log.d(TAG, "🚀 LocationManager: Filtrando ruido (< ${NOISE_SPEED_THRESHOLD} m/s)")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        listener = null
    }

    fun lastKnownLocation(): Pair<Double, Double>? = LocationPredictor.getLastKnown()

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}