package com.crispy.tv.plugins.bridge

import java.net.Inet4Address
import java.net.InetAddress

internal class PluginExecutionBlockedException(message: String) : RuntimeException(message)

internal fun interface HostResolver {
    fun resolveAll(host: String): Array<InetAddress>
}

internal object SsrfGuard {

    private val defaultResolver = HostResolver { host -> PluginHttpClient.dns.lookup(host).toTypedArray() }

    fun validate(url: String, resolver: HostResolver = defaultResolver) {
        val trimmed = url.trim()
        val scheme = schemeOf(trimmed)
        if (scheme != "http" && scheme != "https") {
            throw PluginExecutionBlockedException("Only http(s) URLs are allowed: $scheme")
        }

        val host = hostOf(trimmed)
        if (host.isBlank()) {
            throw PluginExecutionBlockedException("URL has no host")
        }
        val lowerHost = host.lowercase()
        if (lowerHost in BLOCKED_HOSTS || LOCAL_SUFFIXES.any { lowerHost.endsWith(it) }) {
            throw PluginExecutionBlockedException("Blocked host: $host")
        }

        val addresses =
            try {
                resolver.resolveAll(host)
            } catch (error: Exception) {
                throw PluginExecutionBlockedException("Could not resolve host: $host")
            }
        if (addresses.isEmpty()) {
            throw PluginExecutionBlockedException("Host did not resolve: $host")
        }
        addresses.forEach { address ->
            if (isBlockedAddress(address)) {
                throw PluginExecutionBlockedException("Blocked address for host: $host")
            }
        }
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        if (address is Inet4Address) {
            val first = address.address[0].toInt() and 0xFF
            val second = address.address[1].toInt() and 0xFF
            if (first == 100 && second in 64..127) {
                return true
            }
        }
        return false
    }

    private fun schemeOf(url: String): String {
        val colonIndex = url.indexOf(':')
        if (colonIndex <= 0) {
            throw PluginExecutionBlockedException("URL has no scheme")
        }
        return url.substring(0, colonIndex).lowercase()
    }

    private fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) {
            throw PluginExecutionBlockedException("URL has no authority")
        }
        val authority = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return authority.substringAfterLast('@').substringBefore(':')
    }

    private val BLOCKED_HOSTS = setOf("localhost", "localhost.localdomain", "metadata.google.internal")

    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".localdomain", ".internal")
}
