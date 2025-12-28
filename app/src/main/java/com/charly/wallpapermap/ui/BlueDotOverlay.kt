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
        color = 0x404285F4.toInt() // Azul transparente base (se modificará el alpha dinámicamente)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Configuración visual
    private val baseInnerRadius = 20f
    private val baseHaloRadius = 60f // Tamaño mínimo del halo (cuando precisión es perfecta o desconocida)

    // Estado (seteado desde MapRenderer)
    var showAccuracyHalo = true

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val loc = getLocation() ?: return
        val geoPoint = GeoPoint(loc.first, loc.second)
        val pt = Point()
        mapView.projection.toPixels(geoPoint, pt)

        // 1. Calcular Radio del Halo según Precisión
        var haloRadius = baseHaloRadius
        var haloAlpha = 60 // Alpha base (aprox 25%)

        if (showAccuracyHalo) {
            val accuracyMeters = LocationPredictor.getLastAccuracy()
            if (accuracyMeters > 0) {
                // Convertimos metros de precisión a píxeles en el mapa actual
                val metersToPixels = mapView.projection.metersToPixels(accuracyMeters)

                // El halo nunca es menor que el baseHaloRadius, pero puede crecer
                haloRadius = max(baseHaloRadius, metersToPixels)

                // 2. Lógica de Transparencia
                // Si el radio crece mucho (mala señal o mucho zoom), lo hacemos más transparente
                // para que no sea una mancha azul gigante que tape el mapa.
                // Fórmula: A mayor radio, menor alpha.
                if (haloRadius > baseHaloRadius) {
                    val scaleFactor = baseHaloRadius / haloRadius // 1.0 -> 0.0
                    haloAlpha = (60 * scaleFactor).toInt().coerceIn(10, 60)
                }
            }
        }

        // Dibujar Halo (Solo si está activado)
        if (showAccuracyHalo) {
            paintHalo.alpha = haloAlpha
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), haloRadius, paintHalo)
        }

        // Dibujar Punto Central (Fijo) y Borde
        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), baseInnerRadius, paintStroke) // Borde blanco
        canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), baseInnerRadius, paintInner)  // Punto azul
    }
}