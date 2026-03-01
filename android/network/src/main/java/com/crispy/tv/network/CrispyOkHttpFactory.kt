package com.crispy.tv.network

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

object CrispyOkHttpFactory {
    private const val TMDB_HOST = "api.themoviedb.org"
    private const val TMDB_MAX_AGE_SECONDS = 6 * 60 * 60

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

        val tmdbResponseCacheInterceptor =
            Interceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)

                if (request.method != "GET") return@Interceptor response
                if (request.url.host != TMDB_HOST) return@Interceptor response
                if (!response.isSuccessful) return@Interceptor response

                val existing = response.header("Cache-Control").orEmpty()
                val shouldOverride =
                    existing.isBlank() ||
                        existing.contains("no-store", ignoreCase = true) ||
                        existing.contains("no-cache", ignoreCase = true) ||
                        existing.contains("max-age=0", ignoreCase = true) ||
                        existing.contains("must-revalidate", ignoreCase = true)

                if (!shouldOverride) return@Interceptor response

                response
                    .newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=$TMDB_MAX_AGE_SECONDS")
                    .build()
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
                .addNetworkInterceptor(tmdbResponseCacheInterceptor)

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
