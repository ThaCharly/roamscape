package com.charly.wallpapermap

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File
import com.charly.wallpapermap.settings.SettingsManager

class WallpaperMapApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val ctx = applicationContext
        val cfg = Configuration.getInstance()

        cfg.userAgentValue = ctx.packageName
        cfg.tileFileSystemCacheMaxBytes = 400L * 1024L * 1024L
        cfg.tileFileSystemCacheTrimBytes = 300L * 1024L * 1024L

        // 📁 Cache visible y controlada
        val basePath = File(ctx.getExternalFilesDir(null), "osmdroid")
        val tileCache = File(basePath, "tiles")
        if (!tileCache.exists()) tileCache.mkdirs()

        cfg.osmdroidBasePath = basePath
        cfg.osmdroidTileCache = tileCache

        // 🔒 Guardamos config de osmdroid
        val osmPrefs = ctx.getSharedPreferences("osmdroid", MODE_PRIVATE)
        cfg.save(ctx, osmPrefs)
    }
}
