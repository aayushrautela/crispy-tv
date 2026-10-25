package com.crispy.tv.network

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

object CrispyOkHttpFactory {
    /**
     * Standard client for interactive backend calls. Kept intentionally tight so a slow or
     * hung endpoint fails fast instead of pinning connections and threads.
     *
     * @param cacheDir the directory this client's HTTP cache lives in. The caller owns
     *   the platform convention -- Android resolves it under `context.cacheDir`, the
     *   desktop under its own cache root -- because that is the only platform-shaped
     *   input the client ever needed.
     */
    fun create(
        cacheDir: File,
        userAgent: String,
        debugLogging: Boolean,
    ): OkHttpClient =
        build(
            cacheDir = cacheDir,
            userAgent = userAgent,
            debugLogging = debugLogging,
            connectTimeoutSec = 15,
            readTimeoutSec = 25,
            writeTimeoutSec = 25,
            callTimeoutSec = 45,
            cacheMaxSizeBytes = 50L * 1024L * 1024L,
        )

    /**
     * Dedicated client for AI features (AI search / AI insights). These calls run a slow,
     * rate-limited LLM server-side and routinely take 30-60s with no intermediate bytes, so the
     * default 25s read timeout aborts them before the server ever responds. This client gives
     * them a generous, bounded budget while leaving every other call on the tight defaults.
     */
    fun createAiClient(
        cacheDir: File,
        userAgent: String,
        debugLogging: Boolean,
    ): OkHttpClient =
        build(
            cacheDir = cacheDir,
            userAgent = userAgent,
            debugLogging = debugLogging,
            connectTimeoutSec = 30,
            readTimeoutSec = 90,
            writeTimeoutSec = 90,
            callTimeoutSec = 120,
            cacheMaxSizeBytes = 5L * 1024L * 1024L,
        )

    private fun build(
        cacheDir: File,
        userAgent: String,
        debugLogging: Boolean,
        connectTimeoutSec: Long,
        readTimeoutSec: Long,
        writeTimeoutSec: Long,
        callTimeoutSec: Long,
        cacheMaxSizeBytes: Long,
    ): OkHttpClient {
        val cache = Cache(cacheDir, cacheMaxSizeBytes)

        val userAgentInterceptor =
            Interceptor { chain ->
                val original = chain.request()
                val existing = original.header("User-Agent").orEmpty().trim()
                val request =
                    if (existing.isNotEmpty()) {
                        original
                    } else {
                        original
                            .newBuilder()
                            .header("User-Agent", userAgent)
                            .build()
                    }
                chain.proceed(request)
            }

        val builder =
            OkHttpClient.Builder()
                .cache(cache)
                .retryOnConnectionFailure(true)
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
                .addInterceptor(userAgentInterceptor)

        if (debugLogging) {
            val logging = HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        return builder.build()
    }
}
