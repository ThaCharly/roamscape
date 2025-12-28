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

    // Guardamos referencia al overlay para manipularlo
    private var blueDotOverlay: BlueDotOverlay? = null

    init {
        mapView = MapView(context)
        mapView.setMultiTouchControls(false)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false

        applyStyle(SettingsManager.getMapStyle(context))
        setZoom(SettingsManager.getMapZoom(context).toFloat())

        // Inicializar configuración del punto azul
        updateBlueDot()

        val scaleBarOverlay = ScaleBarOverlay(mapView)
        scaleBarOverlay.setAlignRight(true)
        mapView.overlays.add(scaleBarOverlay)
    }

    fun setZoom(zoomLevel: Float) {
        if (mapView.zoomLevelDouble.toFloat() != zoomLevel) {
            mapView.controller.setZoom(zoomLevel.toDouble())
        }
    }

    fun setRotation(bearingDegrees: Float) {
        mapView.mapOrientation = -bearingDegrees
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
        mapView.controller.setCenter(newPoint)
    }

    /**
     * Actualiza el estado del Punto Azul en caliente.
     * Agrega/Quita el overlay y actualiza su configuración de halo.
     */
    fun updateBlueDot() {
        val showDot = SettingsManager.showBlueDot(context)
        val showHalo = SettingsManager.showAccuracyHalo(context)

        if (showDot) {
            // Si no existe, lo creamos y agregamos
            if (blueDotOverlay == null) {
                blueDotOverlay = BlueDotOverlay(getLocation)
                // Lo agregamos con índice para controlar el orden (encima del mapa, debajo de controles)
                // Simplemente add lo pone al final (arriba de todo), que está bien.
                mapView.overlays.add(blueDotOverlay)
            }
            // Actualizamos propiedades
            blueDotOverlay?.showAccuracyHalo = showHalo
        } else {
            // Si existe, lo sacamos
            if (blueDotOverlay != null) {
                mapView.overlays.remove(blueDotOverlay)
                blueDotOverlay = null
            }
        }
        // Forzamos repintado por si cambió algo
        mapView.invalidate()
    }

    private fun updateBrightnessOverlay(styleKey: String?) {
        brightnessOverlay?.let { mapView.overlays.remove(it) }
        brightnessOverlay = when (styleKey) {
            TileSources.KEY_CARTO_DARK -> BrightnessOverlay(14, BrightnessOverlay.Mode.LIGHTEN)
            TileSources.KEY_MAPNIK, TileSources.KEY_CARTO_LIGHT -> BrightnessOverlay(77, BrightnessOverlay.Mode.DARKEN)
            else -> null
        }
        brightnessOverlay?.let { mapView.overlays.add(0, it) } // Brightness al fondo (index 0) sobre los tiles
    }
}