package com.charly.wallpapermap.map.decode

import android.graphics.Bitmap
import android.util.LruCache

object TileBitmapCache {

    private const val MAX_CACHE_MB = 64
    private val maxSize = MAX_CACHE_MB * 1024 * 1024

    val cache = object : LruCache<Long, Bitmap>(maxSize) {
        override fun sizeOf(key: Long, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: Long,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            // Reciclamos memoria si fue desalojado por falta de espacio
            if (evicted && !oldValue.isRecycled) {
                // Ojo: En arquitecturas modernas a veces es mejor no reciclar manual
                // si se usa en otros lados, pero para este caso agresivo sirve.
                // oldValue.recycle()
                // (Comentado por seguridad, descomentar si la RAM explota y estás seguro que nadie lo usa)
            }
        }
    }

    fun get(tileIndex: Long): Bitmap? = cache.get(tileIndex)

    fun put(tileIndex: Long, bmp: Bitmap) {
        cache.put(tileIndex, bmp)
    }
}