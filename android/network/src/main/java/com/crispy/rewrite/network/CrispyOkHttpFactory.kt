package com.crispy.rewrite.network

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

object CrispyOkHttpFactory {
    fun create(
        context: Context,
        userAgent: String,
        debugLogging: Boolean,
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "okhttp")
        val cache = Cache(cacheDir, 50L * 1024L * 1024L)

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
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
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
