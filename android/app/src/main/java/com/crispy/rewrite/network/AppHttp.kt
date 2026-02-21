package com.crispy.rewrite.network

import android.content.Context
import android.os.Build
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.network.CrispyHttpClient
import com.crispy.rewrite.network.CrispyOkHttpFactory
import okhttp3.OkHttpClient

object AppHttp {
    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var httpClient: CrispyHttpClient? = null

    fun okHttp(context: Context): OkHttpClient {
        okHttpClient?.let { return it }
        synchronized(this) {
            okHttpClient?.let { return it }
            val appContext = context.applicationContext
            val userAgent = buildUserAgent(appContext)
            val created =
                CrispyOkHttpFactory.create(
                    context = appContext,
                    userAgent = userAgent,
                    debugLogging = BuildConfig.DEBUG,
                )
            okHttpClient = created
            return created
        }
    }

    fun client(context: Context): CrispyHttpClient {
        httpClient?.let { return it }
        synchronized(this) {
            httpClient?.let { return it }
            val created = CrispyHttpClient(okHttp(context))
            httpClient = created
            return created
        }
    }

    private fun buildUserAgent(context: Context): String {
        val versionName =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        val appPart = if (versionName.isNotBlank()) "Crispy/$versionName" else "Crispy"
        return "$appPart (Android ${Build.VERSION.SDK_INT})"
    }
}
