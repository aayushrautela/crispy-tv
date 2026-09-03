package com.crispy.tv.plugins.bridge

import okhttp3.Dns

/**
 * Reorders DNS results to prefer IPv4 first. Broken or missing IPv6 routes on
 * some emulators and networks otherwise make hostnames unresolvable.
 */
internal class IPv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> =
        delegate.lookup(hostname).sortedBy { address -> if (address is java.net.Inet4Address) 0 else 1 }
}
