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

    // Eliminamos lastGeoPoint porque ya no filtramos aquí.

    init {
        mapView = MapView(context)
        mapView.setMultiTouchControls(false)

        val styleKey = SettingsManager.getMapStyle(context)
        applyStyle(styleKey)

        val zoom = SettingsManager.getMapZoom(context)
        setZoom(zoom.toFloat())

        if (SettingsManager.showBlueDot(context)) {
            mapView.overlays.add(BlueDotOverlay(getLocation))
        }

        val scaleBarOverlay = ScaleBarOverlay(mapView)
        scaleBarOverlay.setAlignRight(true)
        mapView.overlays.add(scaleBarOverlay)
    }

    fun setZoom(zoomLevel: Float) {
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
        if (lat == 0.0 && lon == 0.0) return

        // ⚠️ CAMBIO CRÍTICO: Eliminamos el check de distancia mínima.
        // Queremos que el mapa se mueva aunque sea 1 milímetro.
        mapView.controller.setCenter(newPoint)
    }

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