package com.charly.wallpapermap.map.decode

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.util.MapTileIndex
import java.io.File

object TileFileResolver {

    // Cacheamos la ruta base para no preguntar a config a cada rato
    private var baseCachePath: File? = null

    fun resolve(ctx: Context, tileIndex: Long, sourceName: String): File? {
        if (baseCachePath == null) {
            baseCachePath = Configuration.getInstance().osmdroidTileCache
        }

        val zoom = MapTileIndex.getZoom(tileIndex)
        val x = MapTileIndex.getX(tileIndex)
        val y = MapTileIndex.getY(tileIndex)

        // Osmdroid guarda en: /base/SourceName/Z/X/Y.png.tile (o .png)
        // El nombre del source a veces tiene espacios, Osmdroid los maneja tal cual.

        // Intento 1: Estándar .png
        var file = File(baseCachePath, "$sourceName/$zoom/$x/$y.png")
        if (file.exists()) return file

        // Intento 2: Formato .png.tile (común en algunas versiones)
        file = File(baseCachePath, "$sourceName/$zoom/$x/$y.png.tile")
        if (file.exists()) return file

        return null
    }
}