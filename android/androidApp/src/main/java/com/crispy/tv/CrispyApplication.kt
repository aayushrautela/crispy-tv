package com.crispy.tv

import android.app.ActivityManager
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
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.crispy.tv.app.AndroidAppGraph
import com.crispy.tv.app.AppGraph
import com.crispy.tv.app.AppGraphHost
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.services.AndroidAppServices
import com.crispy.tv.distribution.BuildDistributionComponents
import com.crispy.tv.network.AppHttp
import okio.Path.Companion.toOkioPath

class CrispyApplication : Application(), SingletonImageLoader.Factory, AppGraphHost {
    // One `AppServices` and one `AppGraph` for the process, built here and nowhere else. This is
    // the whole reason `AppServices` exists: `AndroidAppServices` is the only place a `Context`
    // reaches this wiring, and the graph is what everything else holds.
    override val appGraph: AndroidAppGraph by lazy {
        AndroidAppGraph(graph = AppGraph(AndroidAppServices(this)), context = this)
    }

    override fun onCreate() {
        super.onCreate()
        // Must be first. AppDistribution is read by the view-model factories and
        // while the nav graph is built, both of which can run before appGraph is
        // touched. Reading it before this line throws by design rather than
        // defaulting to a variant.
        //
        // BuildDistributionComponents is the only name that resolves to a
        // variant: each of src/store and src/sideload defines its own object
        // under that exact name, so this line is the single place the codebase
        // names a variant and neither definition is visible to the other build.
        // Also activates the components -- AppDistribution.install pushes the
        // variant's torrent resolver into PlaybackDependencies. It is the only
        // place the codebase names a variant.
        AppDistribution.install(BuildDistributionComponents)

        appGraph.userMutationOutbox.start()
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // A low-RAM device gets a loader that trades quality for survival: no hardware
        // bitmaps (they are not GC-reclaimable and live in GPU/system RAM, which is what
        // the lowmemorykiller counts on these tablets), RGB_565 for opaque images (half
        // the bytes per pixel), no crossfade (it holds two bitmaps at once), and a tenth
        // of the heap class for the memory cache instead of a quarter of it.
        val lowRam = activityManager.isLowRamDevice

        return ImageLoader.Builder(appContext)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { AppHttp.okHttp(appContext) },
                    ),
                )
            }
            .crossfade(!lowRam)
            .allowHardware(!lowRam && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .allowRgb565(lowRam)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, if (lowRam) 0.10 else 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes((if (lowRam) 64L else 256L) * 1024L * 1024L)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
