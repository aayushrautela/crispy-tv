package com.crispy.tv

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.crispy.tv.app.AppGraph
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.distribution.BuildDistributionComponents
import com.crispy.tv.network.AppHttp
import okio.Path.Companion.toOkioPath

class CrispyApplication : Application(), SingletonImageLoader.Factory {
    val appGraph: AppGraph by lazy {
        AppGraph(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Must be first. AppDistribution is read by PlaybackDependencies.reset(),
        // by every view-model factory and while the nav graph is built, all of
        // which can run before appGraph is touched. Reading it before this line
        // throws by design rather than defaulting to a variant.
        //
        // BuildDistributionComponents is the only name that resolves to a
        // variant: each of src/store and src/sideload defines its own object
        // under that exact name, so this line is the single place the codebase
        // names a variant and neither definition is visible to the other build.
        AppDistribution.install(BuildDistributionComponents)
        appGraph.userMutationOutbox.start()
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val appContext = context.applicationContext

        return ImageLoader.Builder(appContext)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { AppHttp.okHttp(appContext) },
                    ),
                )
            }
            .crossfade(true)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
