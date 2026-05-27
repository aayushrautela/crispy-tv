package com.crispy.tv.accounts

import android.net.Uri
import com.crispy.tv.BuildConfig

object AppLoginHandoff {
    fun portalUrl(): String = BuildConfig.ACCOUNT_PORTAL_URL.trim().trimEnd('/')

    fun isPortalConfigured(): Boolean = portalUrl().isNotBlank()

    val defaultReturnUri: String get() = "crispy://auth/callback"

    fun buildPortalLoginUrl(returnUri: String): Uri? {
        val base = portalUrl()
        if (base.isBlank()) return null
        return Uri.parse("$base/app-login?return_uri=${Uri.encode(returnUri)}")
    }
}
