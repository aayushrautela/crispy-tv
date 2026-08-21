package com.crispy.tv.network

import android.content.Context
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
     */
    fun create(
        context: Context,
        userAgent: String,
        debugLogging: Boolean,
    ): OkHttpClient =
        build(
            context = context,
            userAgent = userAgent,
            debugLogging = debugLogging,
            connectTimeoutSec = 15,
            readTimeoutSec = 25,
            writeTimeoutSec = 25,
            callTimeoutSec = 45,
            cacheDirName = "okhttp",
            cacheMaxSizeBytes = 50L * 1024L * 1024L,
        )

    /**
     * Dedicated client for AI features (AI search / AI insights). These calls run a slow,
     * rate-limited LLM server-side and routinely take 30-60s with no intermediate bytes, so the
     * default 25s read timeout aborts them before the server ever responds. This client gives
     * them a generous, bounded budget while leaving every other call on the tight defaults.
     */
    fun createAiClient(
        context: Context,
        userAgent: String,
        debugLogging: Boolean,
    ): OkHttpClient =
        build(
            context = context,
            userAgent = userAgent,
            debugLogging = debugLogging,
            connectTimeoutSec = 30,
            readTimeoutSec = 90,
            writeTimeoutSec = 90,
            callTimeoutSec = 120,
            cacheDirName = "okhttp_ai",
            cacheMaxSizeBytes = 5L * 1024L * 1024L,
        )

    private fun build(
        context: Context,
        userAgent: String,
        debugLogging: Boolean,
        connectTimeoutSec: Long,
        readTimeoutSec: Long,
        writeTimeoutSec: Long,
        callTimeoutSec: Long,
        cacheDirName: String,
        cacheMaxSizeBytes: Long,
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, cacheDirName)
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
