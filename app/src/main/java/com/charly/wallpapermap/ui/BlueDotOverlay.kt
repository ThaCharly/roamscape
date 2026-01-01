package com.charly.wallpapermap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import com.charly.wallpapermap.location.LocationPredictor
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max

// --- PUNTO 3: Escalado Correcto ---
// Agregado 'context' al constructor para acceder a DisplayMetrics
class BlueDotOverlay(
    context: Context,
    private val getLocation: () -> Pair<Double, Double>?
) : Overlay() {

    // Reciclamos objeto Point
    private val screenPoint = Point()

    // Densidad para cálculos de DP
    private val density = context.resources.displayMetrics.density

    // Configuración Visual:
    // Inner Radius: Fijo en DP (se ve igual en todas las pantallas y zooms)
    private val baseInnerRadius = 6f * density // ~6dp
    private val strokeWidth = 2f * density     // ~2dp

    // Halo Mínimo: Un tamaño mínimo en DP para que no desaparezca si el GPS es muy preciso
    private val minHaloRadius = 15f * density

    private val paintInner = Paint().apply {
        color = 0xFF4285F4.toInt() // Azul Material Google
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintStroke = Paint().apply {
        color = 0xFFFFFFFF.toInt() // Borde Blanco
        style = Paint.Style.STROKE
        strokeWidth = this@BlueDotOverlay.strokeWidth
        isAntiAlias = true
    }

    private val paintHalo = Paint().apply {
        color = 0x404285F4.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var showAccuracyHalo = true

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val loc = getLocation() ?: return
        val geoPoint = GeoPoint(loc.first, loc.second)

        mapView.projection.toPixels(geoPoint, screenPoint)

        var haloRadiusPixels = minHaloRadius
        var haloAlpha = 60

        if (showAccuracyHalo) {
            val accuracyMeters = LocationPredictor.getLastAccuracy()
            if (accuracyMeters > 0) {
                // --- PUNTO 3: Halo en METROS ---
                // Convertimos la precisión real (metros) a píxeles en el zoom actual
                val accuracyInPixels = mapView.projection.metersToPixels(accuracyMeters)

                // Usamos el mayor entre la precisión real y el mínimo visual
                haloRadiusPixels = max(minHaloRadius, accuracyInPixels)

                if (haloRadiusPixels > minHaloRadius * 3) {
                    val scaleFactor = minHaloRadius / haloRadiusPixels
                    haloAlpha = (60 * scaleFactor).toInt().coerceIn(10, 60)
                }
            }
        }

        // Clipping: No dibujar si está fuera de pantalla
        val maxDrawRadius = if (showAccuracyHalo) haloRadiusPixels else baseInnerRadius + 4f
        val width = canvas.width
        val height = canvas.height

        if (screenPoint.x + maxDrawRadius < 0 || screenPoint.x - maxDrawRadius > width ||
            screenPoint.y + maxDrawRadius < 0 || screenPoint.y - maxDrawRadius > height) {
            return
        }

        // Dibujar Halo
        if (showAccuracyHalo) {
            paintHalo.alpha = haloAlpha
            canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), haloRadiusPixels, paintHalo)
        }

        // Dibujar Punto Central (Usa el radio fijo en DP)
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), baseInnerRadius, paintStroke)
        canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), baseInnerRadius, paintInner)
    }
}