package com.crispy.tv.plugins.bridge

import okhttp3.OkHttpClient
import java.net.Proxy

/**
 * Derives the HTTP client used by all plugin networking (repo manifests, plugin code,
 * plugin-initiated fetches). Mirrors the proven client posture used for addon traffic:
 * IPv4-first DNS with DNS-over-HTTPS fallback and no system proxy, so emulator proxy
 * settings, broken IPv6 routes, or a flaky system resolver cannot break resolution.
 */
internal object PluginHttpClient {

    val dns: PluginDns = PluginDns()

    fun configure(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .dns(dns)
            .proxy(Proxy.NO_PROXY)
            .build()
}
