package com.charly.wallpapermap

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewTreeObserver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.charly.wallpapermap.databinding.ActivityMainBinding
import com.charly.wallpapermap.settings.SettingsManager
import com.charly.wallpapermap.wallpaper.MapWallpaperService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var previewRenderer: com.charly.wallpapermap.map.MapRenderer? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) promptBatteryOptimizationDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializamos componentes globales
        com.charly.wallpapermap.location.LocationManager.init(applicationContext)
        SettingsManager.ensureDefaults(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // === Primera ejecución: permisos ===
        val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run", true)
        if (isFirstRun) {
            prefs.edit().putBoolean("first_run", false).apply()
            checkLocationPermission()
        }

        // === MapRenderer para preview ===
        // Usamos LocationPredictor.getLastKnown() para que coincida con el renderer
        previewRenderer = com.charly.wallpapermap.map.MapRenderer(this) {
            com.charly.wallpapermap.location.LocationPredictor.getLastKnown()
        }
        binding.mapPreviewContainer.addView(previewRenderer!!.mapView)

        // Centrar mapa inicial cuando layout esté listo
        previewRenderer!!.mapView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    previewRenderer!!.mapView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                    // OJO: lastKnownLocation ahora devuelve Location?
                    val lastKnown = com.charly.wallpapermap.location.LocationManager.lastKnownLocation()
                    if (lastKnown != null) {
                        previewRenderer!!.centerOn(lastKnown.latitude, lastKnown.longitude)
                    } else {
                        previewRenderer!!.centerOn(-34.8553013, -56.1936854) // Fallback
                    }

                    // Escucha actualizaciones futuras (Ahora recibe objeto Location)
                    com.charly.wallpapermap.location.LocationManager.start { location ->
                        // Actualizamos el predictor también en la preview para que el punto azul se mueva
                        com.charly.wallpapermap.location.LocationPredictor.update(location)
                        previewRenderer!!.centerOn(location.latitude, location.longitude)

                        // Opcional: Mostrar velocidad en el tvInfo que ya tenés
                        if (location.speed > 0) {
                            binding.tvInfo.text = "Velocidad: ${"%.1f".format(location.speed * 3.6)} km/h"
                        }
                    }
                }
            }
        )

        // === Botones ===
        binding.btnSetWallpaper.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                return@setOnClickListener
            }
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, MapWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Permiso de ubicación")
                .setMessage("Para mostrar su posición en el mapa, la aplicación necesita acceso a su ubicación.")
                .setPositiveButton("Mandale compa") { _, _ ->
                    requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Ni a palo") { _, _ ->
                    promptBatteryOptimizationDialog()
                }
                .setCancelable(false)
                .show()
        } else {
            promptBatteryOptimizationDialog()
        }
    }

    private fun promptBatteryOptimizationDialog() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Optimización de batería")
                .setMessage(
                    "Para que el wallpaper funcione fluido, es mejor quitar la restricción de batería.\n" +
                            "Busque RoamScape y seleccione 'No restringida'."
                )
                .setPositiveButton("Dale sabelo") { _, _ ->
                    val intent = Intent().apply {
                        action = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Arranca despacito", null)
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        (binding.mapPreviewContainer.getChildAt(0) as? org.osmdroid.views.MapView)?.onResume()

        previewRenderer?.apply {
            setZoom(SettingsManager.getMapZoom(this@MainActivity).toFloat())
            applyStyle(SettingsManager.getMapStyle(this@MainActivity))
        }

        com.charly.wallpapermap.location.LocationManager.start { location ->
            com.charly.wallpapermap.location.LocationPredictor.update(location)
            previewRenderer?.centerOn(location.latitude, location.longitude)
        }
    }

    override fun onPause() {
        super.onPause()
        (binding.mapPreviewContainer.getChildAt(0) as? org.osmdroid.views.MapView)?.onPause()
        com.charly.wallpapermap.location.LocationManager.stop()
    }
}