package com.crispy.tv.plugins.bridge

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class PluginDnsTest {

    private fun dns(vararg addresses: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            addresses.map { InetAddress.getByName(it) }
    }

    private fun failingDns() = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException(hostname)
    }

    @Test
    fun `prefers ipv4 over ipv6 from system resolver`() {
        val resolved = PluginDns(dns("2606:4700:4700::1111", "1.1.1.1")).lookup("example.com")

        assertEquals(2, resolved.size)
        assertTrue(resolved[0] is Inet4Address)
        assertTrue(resolved[1] is Inet6Address)
    }

    @Test
    fun `falls back to doh lookup when system resolver fails`() {
        val fallback = PluginDns(failingDns()) { listOf(InetAddress.getByName("1.0.0.1")) }
        val resolved = fallback.lookup("example.com")

        assertEquals(listOf(InetAddress.getByName("1.0.0.1")), resolved)
    }

    @Test
    fun `sorts doh fallback ipv4 first`() {
        val fallback = PluginDns(failingDns()) {
            listOf(
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("1.1.1.1"),
            )
        }
        val resolved = fallback.lookup("example.com")

        assertTrue(resolved[0] is Inet4Address)
        assertTrue(resolved[1] is Inet6Address)
    }

    @Test
    fun `throws when system and doh both fail`() {
        val failing = PluginDns(failingDns()) { emptyList() }

        val error = assertThrows(UnknownHostException::class.java) {
            failing.lookup("does-not-exist.invalid")
        }
        assertTrue(error.message!!.contains("both failed"))
    }

    @Test
    fun `parses doh a answers`() {
        val parsed = DohResolver.parse(
            """
            {"Status":0,"TC":false,"RD":true,"Answer":[
              {"name":"example.com","type":1,"TTL":300,"data":"93.184.216.34"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf(InetAddress.getByName("93.184.216.34")), parsed)
    }

    @Test
    fun `parses doh aaaa answers and skips other record types`() {
        val parsed = DohResolver.parse(
            """
            {"Status":0,"Answer":[
              {"name":"example.com","type":5,"TTL":300,"data":"cname.example.com"},
              {"name":"example.com","type":28,"TTL":300,"data":"2606:2800:220:1:248:1893:25c8:1946"}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            listOf(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")),
            parsed,
        )
    }

    @Test
    fun `rejects doh error statuses and malformed bodies`() {
        assertNull(DohResolver.parse("""{"Status":3,"Answer":[]}"""))
        assertNull(DohResolver.parse("""{"Status":0}"""))
        assertNull(DohResolver.parse("not json"))
    }

    @Test
    fun `skips malformed answer data`() {
        val parsed = DohResolver.parse(
            """
            {"Status":0,"Answer":[
              {"name":"example.com","type":1,"TTL":300,"data":"not-an-ip"},
              {"name":"example.com","type":1,"TTL":300,"data":"8.8.8.8"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), parsed)
    }

    @Test
    fun `doh resolver returns empty when endpoint unreachable`() {
        val unreachable = OkHttpClient.Builder()
            .connectTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        assertTrue(DohResolver.resolve("example.invalid", unreachable).isEmpty())
    }
}
