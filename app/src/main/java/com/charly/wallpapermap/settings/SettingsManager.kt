package com.charly.wallpapermap.settings

import android.content.Context
import androidx.preference.PreferenceManager

object SettingsManager {
    // 🔓 Ahora son públicas para que el Servicio pueda escuchar cambios específicos
    const val KEY_MAP_STYLE = "map_style"
    const val KEY_SHOW_BLUE = "show_blue_dot"
    const val KEY_ZOOM = "map_zoom"

    private const val MIN_ZOOM = 4
    private const val MAX_ZOOM = 21

    fun getMapStyle(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_MAP_STYLE, "MAPNIK") ?: "MAPNIK"
    }

    fun getMapZoom(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getInt(KEY_ZOOM, 13) // default 13
        val zoom = raw + MIN_ZOOM
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun showBlueDot(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(KEY_SHOW_BLUE, true)
    }

    fun ensureDefaults(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        var changed = false

        if (!prefs.contains(KEY_MAP_STYLE)) {
            editor.putString(KEY_MAP_STYLE, "MAPNIK")
            changed = true
        }
        if (!prefs.contains(KEY_SHOW_BLUE)) {
            editor.putBoolean(KEY_SHOW_BLUE, true)
            changed = true
        }
        if (!prefs.contains(KEY_ZOOM)) {
            editor.putInt(KEY_ZOOM, 13)
            changed = true
        }

        if (changed) editor.apply()
    }
}