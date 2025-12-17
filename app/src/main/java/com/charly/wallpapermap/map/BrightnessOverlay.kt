package com.charly.wallpapermap.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Overlay semitransparente para aclarar u oscurecer el mapa.
 *
 * @param alpha intensidad del filtro (0 = invisible, 255 = total)
 * @param mode "lighten" para aclarar, "darken" para oscurecer.
 */
class BrightnessOverlay(
    private val alphaa: Int = 60,
    private val mode: Mode = Mode.LIGHTEN
) : Overlay() {

    enum class Mode { LIGHTEN, DARKEN }

    private val paint = Paint().apply {
        color = if (mode == Mode.LIGHTEN) Color.WHITE else Color.BLACK
        this.alpha = alphaa
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (!shadow) {
            canvas.drawRect(
                0f, 0f,
                canvas.width.toFloat(),
                canvas.height.toFloat(),
                paint
            )
        }
    }
}
