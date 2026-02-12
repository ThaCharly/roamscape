package com.charly.wallpapermap.map.decode

object TileDecodeScheduler {

    const val PRIORITY_VISIBLE = 0
    const val PRIORITY_BORDER = 1
    const val PRIORITY_PREFETCH = 2

    // Guardamos el nombre del source actual para pasárselo a los jobs
    var currentTileSourceName: String = "Mapnik"

    fun schedule(tileIndex: Long, priority: Int) {
        // Si ya está en caché, ni nos gastamos
        if (TileBitmapCache.get(tileIndex) != null) return

        TileDecodeQueue.push(
            TileDecodeJob(tileIndex, priority, currentTileSourceName)
        )
    }
}