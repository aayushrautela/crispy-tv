package com.crispy.tv.accounts

import android.net.Uri
import com.crispy.tv.BuildConfig

object AccountPortalUrls {
    fun portalUrl(): String = BuildConfig.ACCOUNT_PORTAL_URL.trim().trimEnd('/')

    fun isConfigured(): Boolean = portalUrl().isNotBlank()

    fun portalPageUrl(path: String): Uri? {
        val base = portalUrl()
        if (base.isBlank()) return null
        return Uri.parse("$base/${path.trimStart('/')}")
    }
}
