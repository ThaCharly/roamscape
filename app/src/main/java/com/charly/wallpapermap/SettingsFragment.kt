package com.charly.wallpapermap

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import android.widget.Toast
import androidx.preference.SeekBarPreference
import androidx.preference.Preference
import android.app.WallpaperManager
import android.service.wallpaper.WallpaperService
import java.io.File
class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("clear_tile_cache")?.setOnPreferenceClickListener {
            // 1️⃣ Borrar caché en disco
            val cacheDir = File(requireContext().getExternalFilesDir(null), "osmdroid/tiles")

            fun deleteRecursively(file: File) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { deleteRecursively(it) }
                }
                file.delete()
            }

            if (cacheDir.exists()) {
                deleteRecursively(cacheDir)
                cacheDir.mkdirs() // recrear carpeta vacía
            }

            // 2️⃣ Borrar caché en RAM
            val mapViews = mutableListOf<org.osmdroid.views.MapView>()

            // Buscar todos los MapView que tengas en la app (por ejemplo en MainActivity o WallpaperService)
            // Acá sólo demo: si tenés referencia global de MapRenderer, podés pasarla
            (activity as? SettingsActivity)?.let { activity ->
                val root = activity.window.decorView.rootView
                fun findMapViews(view: android.view.View) {
                    if (view is org.osmdroid.views.MapView) mapViews.add(view)
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) {
                            findMapViews(view.getChildAt(i))
                        }
                    }
                }
                findMapViews(root)
            }



            // Limpiar cada MapView
            mapViews.forEach { mapView ->
                mapView.tileProvider.clearTileCache()   // limpia tiles en RAM
                mapView.invalidate()                     // fuerza redraw
            }

            Toast.makeText(requireContext(), "Caché de tiles borrada (disco y RAM)", Toast.LENGTH_SHORT).show()
            true
        }



        findPreference<Preference>("stop_wallpaper")?.setOnPreferenceClickListener {
            // 1. limpiar ubicación
            com.charly.wallpapermap.location.LocationManager.stop()

            // 2. limpiar wallpaper actual
            val wm = WallpaperManager.getInstance(requireContext())
            wm.clear(WallpaperManager.FLAG_LOCK) // vuelve al fondo por defecto del sistema, sólo del lock

            Toast.makeText(requireContext(), "Wallpaper de bloqueo detenido", Toast.LENGTH_SHORT).show()
            true
        }


        val tilePref = findPreference<ListPreference>("map_style")
        tilePref?.entries = resources.getStringArray(R.array.map_styles)
        tilePref?.entryValues = resources.getStringArray(R.array.map_style_values)
    }
}
