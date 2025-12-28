package com.charly.wallpapermap.settings

import android.content.Context
import androidx.preference.PreferenceManager

object SettingsManager {
    const val KEY_MAP_STYLE = "map_style"
    const val KEY_ZOOM = "map_zoom"
    const val KEY_SHOW_BLUE_DOT = "show_blue_dot"
    // Asumimos que esta es la key en tu preferences.xml
    const val KEY_MOTION_SENSOR = "motion_sensor"

    // ... (tus otros métodos existentes getMapStyle, getMapZoom, etc) ...

    fun isMotionSensorEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Default false para no romper nada si no lo activan
        return prefs.getBoolean(KEY_MOTION_SENSOR, false)
    }

    // ... (asegurate de tener el resto de métodos como ensureDefaults si los usabas)

    // Helper para mantener tu código limpio
    fun ensureDefaults(context: Context) {
        // Tu lógica de defaults
    }

    fun getMapStyle(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_MAP_STYLE, "carto_dark") ?: "carto_dark"

    fun getMapZoom(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context).getInt(KEY_ZOOM, 15)

    fun showBlueDot(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_SHOW_BLUE_DOT, true)
}