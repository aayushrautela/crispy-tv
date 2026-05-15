package com.crispy.tv

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.crispy.tv.app.AppGraph
import okio.Path.Companion.toOkioPath

class CrispyApplication : Application(), SingletonImageLoader.Factory {
    val appGraph: AppGraph by lazy {
        AppGraph(this)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(200)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
