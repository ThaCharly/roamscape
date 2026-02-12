package com.charly.wallpapermap.map.decode

import android.util.Log

object TileDecodeScheduler {

    const val PRIORITY_VISIBLE = 0
    const val PRIORITY_BORDER = 1
    const val PRIORITY_PREFETCH = 2

    fun schedule(
        tileIndex: Long,
        priority: Int,
        tileSourceName: String
    ) {
        if (TileBitmapCache.get(tileIndex) != null) {
            Log.v("TileDecode", "💾 SKIP CACHE | Tile: $tileIndex")
            return
        }

        TileDecodeQueue.push(
            TileDecodeJob(tileIndex, priority, tileSourceName)
        )

        Log.d(
            "TileDecode",
            "📦 SCHEDULE | Tile: $tileIndex | Priority: $priority"
        )
    }
    }
