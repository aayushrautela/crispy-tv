package com.crispy.tv.plugins.bridge

import okhttp3.OkHttpClient
import java.net.Proxy

/**
 * Derives the HTTP client used by all plugin networking (repo manifests, plugin code,
 * plugin-initiated fetches). Mirrors the proven client posture used for addon traffic:
 * IPv4-first DNS and no system proxy, so emulator proxy settings or broken IPv6
 * routes cannot break resolution.
 */
internal object PluginHttpClient {
    fun configure(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .dns(IPv4FirstDns())
            .proxy(Proxy.NO_PROXY)
            .build()
}
