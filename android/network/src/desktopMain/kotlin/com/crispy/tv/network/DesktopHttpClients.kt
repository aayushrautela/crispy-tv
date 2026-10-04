package com.crispy.tv.network

import java.io.File
import okhttp3.OkHttpClient

/**
 * The desktop side of [CrispyHttpClient].
 *
 * The Android side ([AppHttp]) reads its cache root, version and debuggability out
 * of a `Context`. A desktop process has none of those, so they are constructor
 * parameters: the composition root knows the user agent and the `debug` flag, and
 * the cache root is a directory under the platform's cache location (in
 * `:android:desktopApp`, `DesktopPaths.cacheDirectory()`).
 *
 * Deliberately a class rather than an `object`: the caches and connections should
 * live as long as the application instance, not the class loader, and an `object`
 * would silently share one cache between two windows or a window and a CLI harness.
 */
class DesktopHttpClients(
    cacheDirectory: File,
    private val userAgent: String,
    private val debugLogging: Boolean,
) {
    private val okHttpClient: OkHttpClient by lazy {
        CrispyOkHttpFactory.create(
            cacheDir = File(cacheDirectory, HTTP_CACHE_DIR),
            userAgent = userAgent,
            debugLogging = debugLogging,
        )
    }

    private val aiOkHttpClient: OkHttpClient by lazy {
        CrispyOkHttpFactory.createAiClient(
            cacheDir = File(cacheDirectory, AI_HTTP_CACHE_DIR),
            userAgent = userAgent,
            debugLogging = debugLogging,
        )
    }

    val client: CrispyHttpClient by lazy { OkHttpCrispyHttpClient(okHttpClient) }

    val aiClient: CrispyHttpClient by lazy { OkHttpCrispyHttpClient(aiOkHttpClient) }

    private companion object {
        const val HTTP_CACHE_DIR = "okhttp"
        const val AI_HTTP_CACHE_DIR = "okhttp_ai"
    }
}
