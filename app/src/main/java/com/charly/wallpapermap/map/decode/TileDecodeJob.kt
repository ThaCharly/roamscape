package com.charly.wallpapermap.map.decode

data class TileDecodeJob(
    val tileIndex: Long,
    val priority: Int,
    val tileSourceName: String, // Agregado para saber en qué carpeta buscar
    val timestamp: Long = System.currentTimeMillis()
)