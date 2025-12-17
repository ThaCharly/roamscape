package com.charly.wallpapermap.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.util.Locale
import android.content.Context


object TileSources {
    // claves públicas para usar en preferencias
    const val KEY_MAPNIK = "MAPNIK"
    const val KEY_CARTO_DARK = "CARTO_DARK"
    const val KEY_CARTO_VOYAGER = "CARTO_VOYAGER"
    const val KEY_CARTO_LIGHT = "CARTO_LIGHT"

    val Default = TileSourceFactory.MAPNIK

    val CartoDBDarkMatter = object : OnlineTileSourceBase(
        "CartoDB Dark Matter", 0, 21, 256, ".png",
        arrayOf(
            "https://cartodb-basemaps-a.global.ssl.fastly.net/dark_all/",
            "https://cartodb-basemaps-b.global.ssl.fastly.net/dark_all/",
            "https://cartodb-basemaps-c.global.ssl.fastly.net/dark_all/"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
    }

    val CartoDBVoyager = object : OnlineTileSourceBase(
        "CartoDB Voyager", 0, 21, 256, ".png",
        arrayOf(
            "https://cartodb-basemaps-a.global.ssl.fastly.net/rastertiles/voyager/",
            "https://cartodb-basemaps-b.global.ssl.fastly.net/rastertiles/voyager/",
            "https://cartodb-basemaps-c.global.ssl.fastly.net/rastertiles/voyager/"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
    }

    val CartoDBLightAll = object : OnlineTileSourceBase(
        "CartoDB Light All", 0, 21, 256, ".png",
        arrayOf(
            "https://cartodb-basemaps-a.global.ssl.fastly.net/light_all/",
            "https://cartodb-basemaps-b.global.ssl.fastly.net/light_all/",
            "https://cartodb-basemaps-c.global.ssl.fastly.net/light_all/"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
    }

    // lookup robusto: normaliza a mayúsculas y devuelve un TileSource
    fun fromKey(key: String?): org.osmdroid.tileprovider.tilesource.ITileSource {
        if (key == null) return Default
        return when (key.uppercase(Locale.ROOT)) {
            KEY_CARTO_DARK -> CartoDBDarkMatter
            KEY_CARTO_VOYAGER -> CartoDBVoyager
            KEY_CARTO_LIGHT -> CartoDBLightAll
            KEY_MAPNIK -> Default
            else -> Default
        }
    }

    // helper opcional: lista de pares (display, key) para llenar UI dinámicamente
    fun getDisplayPairs(context: Context): List<Pair<String, String>> {
        return listOf(
            "OSM Default (Mapnik)" to KEY_MAPNIK,
            "CartoDB DarkMatter" to KEY_CARTO_DARK,
            "CartoDB Voyager" to KEY_CARTO_VOYAGER,
            "Carto Light" to KEY_CARTO_LIGHT
        )
    }
}
