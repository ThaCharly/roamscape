package com.charly.wallpapermap.map

import android.content.Context
import com.charly.wallpapermap.settings.SettingsManager
import com.charly.wallpapermap.ui.BlueDotOverlay
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ScaleBarOverlay

class MapRenderer(
    private val context: Context,
    private val getLocation: () -> Pair<Double, Double>?
) {
    val mapView: MapView
    private var brightnessOverlay: BrightnessOverlay? = null
    private var lastGeoPoint: GeoPoint? = null

    init {
        mapView = MapView(context)

        // 🛠️ CORREGIDO: Eliminada la línea 'isTilesScaledToDpi = true'
        // Ahora osmdroid pedirá los tiles exactos (1:1) para el nivel de zoom,
        // manteniendo la nitidez y legibilidad original.
        mapView.setMultiTouchControls(false)

        // 🔹 Estilo inicial
        val styleKey = SettingsManager.getMapStyle(context)
        applyStyle(styleKey)

        // 🔹 Zoom inicial
        val zoom = SettingsManager.getMapZoom(context)
        setZoom(zoom.toFloat())

        // 🔹 Overlays
        if (SettingsManager.showBlueDot(context)) {
            mapView.overlays.add(BlueDotOverlay(getLocation))
        }

        val scaleBarOverlay = ScaleBarOverlay(mapView)
        scaleBarOverlay.setAlignRight(true)
        mapView.overlays.add(scaleBarOverlay)
    }

    // ------------------------------------------------------------
    // 🔹 API de Control
    // ------------------------------------------------------------

    fun setZoom(zoomLevel: Float) {
        // Usamos setZoom del controller que dispara la carga de nuevos tiles
        if (mapView.zoomLevelDouble.toFloat() != zoomLevel) {
            mapView.controller.setZoom(zoomLevel.toDouble())
        }
    }

    fun applyStyle(styleKey: String?) {
        val newTileSource = TileSources.fromKey(styleKey)
        if (mapView.tileProvider.tileSource.name() != newTileSource.name()) {
            mapView.setTileSource(newTileSource)
            updateBrightnessOverlay(styleKey)
        }
    }

    fun centerOn(lat: Double, lon: Double) {
        val newPoint = GeoPoint(lat, lon)
        // Evitamos saltos a coordenadas inválidas (0,0)
        if (lat == 0.0 && lon == 0.0) return

        if (lastGeoPoint == null || newPoint.distanceToAsDouble(lastGeoPoint) > 1.0) {
            mapView.controller.setCenter(newPoint)
            lastGeoPoint = newPoint
        }
    }

    // ------------------------------------------------------------
    // 🔹 Lógica Privada
    // ------------------------------------------------------------
    private fun updateBrightnessOverlay(styleKey: String?) {
        brightnessOverlay?.let { mapView.overlays.remove(it) }

        brightnessOverlay = when (styleKey) {
            TileSources.KEY_CARTO_DARK -> BrightnessOverlay(14, BrightnessOverlay.Mode.LIGHTEN)
            TileSources.KEY_MAPNIK, TileSources.KEY_CARTO_LIGHT -> BrightnessOverlay(77, BrightnessOverlay.Mode.DARKEN)
            else -> null
        }

        brightnessOverlay?.let { mapView.overlays.add(it) }
    }
}