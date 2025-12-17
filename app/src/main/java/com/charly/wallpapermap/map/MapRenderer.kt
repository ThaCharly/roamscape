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

    init {
        mapView = MapView(context)

        // 🔹 Estilo inicial
        val styleKey = SettingsManager.getMapStyle(context)
        applyStyle(styleKey)

        // 🔹 Overlays iniciales
        if (SettingsManager.showBlueDot(context)) {
            mapView.overlays.add(BlueDotOverlay(getLocation))
        }

        val scaleBarOverlay = ScaleBarOverlay(mapView)
        scaleBarOverlay.setAlignRight(true)
        mapView.overlays.add(scaleBarOverlay)

        // 🔹 Filtro de brillo inicial
        maybeAddBrightnessOverlay(styleKey)
    }

    // ------------------------------------------------------------
    // 🔹 Cambio de estilo
    // ------------------------------------------------------------
    private fun setTileSourceWithCache(tileSource: ITileSource) {
        mapView.setTileSource(tileSource)
    }

    fun applyStyle(styleKey: String?) {
        val tileSource = TileSources.fromKey(styleKey)
        setTileSourceWithCache(tileSource)
        maybeAddBrightnessOverlay(styleKey)
    }

    // ------------------------------------------------------------
    // 🔹 Filtro de brillo dinámico
    // ------------------------------------------------------------
    private fun maybeAddBrightnessOverlay(styleKey: String?) {
        // Quita el overlay anterior
        brightnessOverlay?.let { mapView.overlays.remove(it) }

        when (styleKey) {
            TileSources.KEY_CARTO_DARK -> {
                // 🔸 Aclarar el Dark Matter (porque es MUY oscuro)
                brightnessOverlay = BrightnessOverlay(
                    alphaa = 14,
                    mode = BrightnessOverlay.Mode.LIGHTEN
                )
                mapView.overlays.add(brightnessOverlay)
            }
            TileSources.KEY_MAPNIK, TileSources.KEY_CARTO_LIGHT -> {
                // 🔸 Oscurecer los claros para la noche
                brightnessOverlay = BrightnessOverlay(
                    alphaa = 77,
                    mode = BrightnessOverlay.Mode.DARKEN
                )
                mapView.overlays.add(brightnessOverlay)
            }
            else -> brightnessOverlay = null
        }
    }

    // ------------------------------------------------------------
    // 🔹 Métodos dinámicos
    // ------------------------------------------------------------
    private var lastGeoPoint: GeoPoint? = null

    fun centerOn(lat: Double, lon: Double) {
        val newPoint = GeoPoint(lat, lon)
        if (lastGeoPoint == null || newPoint.distanceToAsDouble(lastGeoPoint) > 2.0) {
            mapView.controller.setCenter(newPoint)
            lastGeoPoint = newPoint
        }
    }

    fun ensureZoomUpdated() {
        val currentZoom = SettingsManager.getMapZoom(context).toDouble()
        mapView.controller.setZoom(currentZoom)
    }

    fun ensureStyleUpdated() {
        val currentStyle = SettingsManager.getMapStyle(context)
        val newTileSource = TileSources.fromKey(currentStyle)
        if (mapView.tileProvider.tileSource?.name() != newTileSource.name()) {
            applyStyle(currentStyle)
        }
    }
}
