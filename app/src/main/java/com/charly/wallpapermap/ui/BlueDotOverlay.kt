package com.charly.wallpapermap.ui

import android.graphics.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class BlueDotOverlay(
    private val getLocation: () -> Pair<Double, Double>?
) : Overlay() {

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        val loc = getLocation() ?: return
        val projection = mapView?.projection ?: return

        val gp = GeoPoint(loc.first, loc.second)
        val pt = android.graphics.Point()
        projection.toPixels(gp, pt)

        val innerRadius = 10f     // px
        val borderWidth = 1.0f      // px
        val outerRadius = innerRadius + borderWidth

        val paintWhite = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val paintBlue = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.parseColor("#FF3366FF")
        }

        canvas?.drawCircle(pt.x.toFloat(), pt.y.toFloat(), outerRadius, paintWhite)
        canvas?.drawCircle(pt.x.toFloat(), pt.y.toFloat(), innerRadius, paintBlue)
    }
}
