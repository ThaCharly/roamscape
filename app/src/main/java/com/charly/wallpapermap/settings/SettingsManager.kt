package com.charly.wallpapermap.settings

import android.content.Context
import androidx.preference.PreferenceManager

object SettingsManager {
    const val KEY_MAP_STYLE = "map_style"
    const val KEY_ZOOM = "map_zoom"
    const val KEY_SHOW_BLUE_DOT = "show_blue_dot"
    const val KEY_MOTION_SENSOR = "motion_sensor"
    const val KEY_SHOW_ACCURACY = "show_accuracy_halo"
    const val KEY_TARGET_FPS = "target_fps"
    // NUEVO
    const val KEY_VSYNC = "enable_vsync"
    const val KEY_DEBUG_ANIM = "debug_force_anim"
    const val KEY_SHOW_FPS = "show_fps_counter"

    fun ensureDefaults(context: Context) {
        PreferenceManager.setDefaultValues(context, com.charly.wallpapermap.R.xml.preferences, false)
    }

    fun getMapStyle(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_MAP_STYLE, "carto_dark") ?: "carto_dark"

    fun getMapZoom(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context).getInt(KEY_ZOOM, 15)

    fun showBlueDot(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_SHOW_BLUE_DOT, true)

    fun showAccuracyHalo(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_SHOW_ACCURACY, true)

    fun isMotionSensorEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_MOTION_SENSOR, false)

    fun getTargetFps(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_TARGET_FPS, "48")?.toIntOrNull() ?: 48
    }

    // NUEVO: Getter de VSync
    fun isVsyncEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_VSYNC, false)

    fun isDebugAnimEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_DEBUG_ANIM, false)

    fun showFpsCounter(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_SHOW_FPS, false)
}