package com.crispy.tv.network

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import okhttp3.OkHttpClient

object AppHttp {
    private const val HTTP_CACHE_DIR = "okhttp"
    private const val AI_HTTP_CACHE_DIR = "okhttp_ai"

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var httpClient: CrispyHttpClient? = null

    @Volatile
    private var aiOkHttpClient: OkHttpClient? = null

    @Volatile
    private var aiHttpClient: CrispyHttpClient? = null

    fun okHttp(context: Context): OkHttpClient {
        okHttpClient?.let { return it }
        synchronized(this) {
            okHttpClient?.let { return it }
            val appContext = context.applicationContext
            val userAgent = buildUserAgent(appContext)
            val created =
                CrispyOkHttpFactory.create(
                    cacheDir = File(appContext.cacheDir, HTTP_CACHE_DIR),
                    userAgent = userAgent,
                    debugLogging = isDebuggable(context),
                )
            okHttpClient = created
            return created
        }
    }

    fun client(context: Context): CrispyHttpClient {
        httpClient?.let { return it }
        synchronized(this) {
            httpClient?.let { return it }
            val created = OkHttpCrispyHttpClient(okHttp(context))
            httpClient = created
            return created
        }
    }

    /**
     * Long-timeout client scoped to AI endpoints (see [CrispyOkHttpFactory.createAiClient]).
     * Kept separate from [client] so interactive calls stay on tight, fail-fast timeouts.
     */
    fun aiOkHttp(context: Context): OkHttpClient {
        aiOkHttpClient?.let { return it }
        synchronized(this) {
            aiOkHttpClient?.let { return it }
            val appContext = context.applicationContext
            val created =
                CrispyOkHttpFactory.createAiClient(
                    cacheDir = File(appContext.cacheDir, AI_HTTP_CACHE_DIR),
                    userAgent = buildUserAgent(appContext),
                    debugLogging = isDebuggable(context),
                )
            aiOkHttpClient = created
            return created
        }
    }

    fun aiClient(context: Context): CrispyHttpClient {
        aiHttpClient?.let { return it }
        synchronized(this) {
            aiHttpClient?.let { return it }
            val created = OkHttpCrispyHttpClient(aiOkHttp(context))
            aiHttpClient = created
            return created
        }
    }

    private fun buildUserAgent(context: Context): String {
        val versionName =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()

        val resolvedVersion =
            versionName
                .trim()
                .ifBlank { "dev" }

        return "crispytv/$resolvedVersion"
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
