package com.charly.wallpapermap.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import com.charly.wallpapermap.location.LocationPredictor
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max

class BlueDotOverlay(
    private val getLocation: () -> Pair<Double, Double>?
) : Overlay() {

    // OPTIMIZACIÓN OBLIGATORIA: Reutilizar el objeto Point para evitar basura en el GC
    private val screenPoint = Point()

    private val paintInner = Paint().apply {
        color = 0xFF4285F4.toInt() // Azul Material Google
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintStroke = Paint().apply {
        color = 0xFFFFFFFF.toInt() // Borde Blanco
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val paintHalo = Paint().apply {
        color = 0x404285F4.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val baseInnerRadius = 20f
    private val baseHaloRadius = 60f

    var showAccuracyHalo = true

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val loc = getLocation() ?: return
        val geoPoint = GeoPoint(loc.first, loc.second)

        // OPTIMIZACIÓN: Usamos el objeto existente en lugar de crear uno nuevo
        mapView.projection.toPixels(geoPoint, screenPoint)

        var haloRadius = baseHaloRadius
        var haloAlpha = 60

        if (showAccuracyHalo) {
            val accuracyMeters = LocationPredictor.getLastAccuracy()
            if (accuracyMeters > 0) {
                val metersToPixels = mapView.projection.metersToPixels(accuracyMeters)
                haloRadius = max(baseHaloRadius, metersToPixels)

                if (haloRadius > baseHaloRadius) {
                    val scaleFactor = baseHaloRadius / haloRadius
                    haloAlpha = (60 * scaleFactor).toInt().coerceIn(10, 60)
                }
            }
        }

        // OPTIMIZACIÓN OBLIGATORIA: Clipping (Recorte)
        // Si el punto (más su radio máximo) está fuera de la pantalla, no dibujamos nada.
        // Esto ahorra mucho proceso de GPU cuando scrolleás lejos de tu ubicación.
        val maxDrawRadius = if (showAccuracyHalo) haloRadius else baseInnerRadius + 4f
        val width = canvas.width
        val height = canvas.height

        if (screenPoint.x + maxDrawRadius < 0 || screenPoint.x - maxDrawRadius > width ||
            screenPoint.y + maxDrawRadius < 0 || screenPoint.y - maxDrawRadius > height) {
            return
        }

        // Dibujar Halo
        if (showAccuracyHalo) {
            paintHalo.alpha = haloAlpha
            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), haloRadius, paintHalo)
        }

        // Dibujar Punto Central y Borde
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), baseInnerRadius, paintStroke)
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), baseInnerRadius, paintInner)
    }
}