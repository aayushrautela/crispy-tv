package com.crispy.tv.plugins.bridge

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS for all plugin networking: resolves via the system resolver preferring IPv4
 * (so broken IPv6 routes cannot make a host unresolvable), then falls back to
 * DNS-over-HTTPS JSON endpoints bootstrapped by raw IP. This mirrors why a URL can
 * load in a browser (which uses secure DNS) while a plain OkHttp client reports
 * "unable to resolve host" on the same device.
 */
internal class PluginDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val dohLookup: (String) -> List<InetAddress> = { host -> DohResolver.resolve(host, dohClient) },
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val system = try {
            delegate.lookup(hostname)
        } catch (error: Exception) {
            Log.w(TAG, "System DNS failed for $hostname: ${error.javaClass.simpleName}: ${error.message}", error)
            null
        }
        if (!system.isNullOrEmpty()) return system.sortV4First()

        val fallback = try {
            dohLookup(hostname)
        } catch (error: Exception) {
            Log.w(TAG, "DoH failed for $hostname: ${error.javaClass.simpleName}: ${error.message}", error)
            null
        }.orEmpty()
        if (fallback.isNotEmpty()) {
            Log.i(TAG, "DoH resolved $hostname -> ${fallback.joinToString() { it.hostAddress ?: "?" }}")
            return fallback.sortV4First()
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\": system and secure DNS both failed")
    }

    private fun List<InetAddress>.sortV4First(): List<InetAddress> =
        sortedBy { if (it is Inet4Address) 0 else 1 }

    companion object {
        private const val TAG = "CrispyPlugins"

        private val dohClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .proxy(Proxy.NO_PROXY)
                .build()
        }
    }
}

internal object DohResolver {

    // Endpoints are raw IPs, so resolution cannot recurse into DNS itself.
    private val endpoints = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A" to "application/dns-json",
        "https://1.1.1.1/dns-query?name=%s&type=AAAA" to "application/dns-json",
        "https://8.8.8.8/resolve?name=%s&type=A" to "application/dns-json",
        "https://8.8.8.8/resolve?name=%s&type=AAAA" to "application/dns-json",
    )

    fun resolve(hostname: String, client: OkHttpClient): List<InetAddress> {
        for ((template, accept) in endpoints) {
            val body = runCatching {
                val request = Request.Builder()
                    .url(template.format(hostname))
                    .header("Accept", accept)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            }.getOrNull() ?: continue
            parse(body)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    fun parse(json: String): List<InetAddress>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("Status", 1) != 0) return null
        val answers = root.optJSONArray("Answer") ?: return null
        val addresses = mutableListOf<InetAddress>()
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            when (answer.optInt("type", -1)) {
                TYPE_A, TYPE_AAAA -> runCatching {
                    addresses += InetAddress.getByName(answer.getString("data"))
                }
            }
        }
        return addresses
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
}
