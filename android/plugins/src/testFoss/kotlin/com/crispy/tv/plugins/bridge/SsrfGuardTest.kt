package com.crispy.tv.plugins.bridge

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class SsrfGuardTest {

    private fun literalResolver(vararg addresses: String) = HostResolver { _ ->
        addresses.map { InetAddress.getByName(it) }.toTypedArray()
    }

    @Test
    fun `rejects non http schemes`() {
        assertThrows(PluginExecutionBlockedException::class.java) {
            SsrfGuard.validate("file:///etc/passwd")
        }
        assertThrows(PluginExecutionBlockedException::class.java) {
            SsrfGuard.validate("ftp://example.com/file")
        }
    }

    @Test
    fun `rejects localhost hostnames`() {
        listOf(
            "http://localhost:8080/x",
            "http://LOCALHOST/x",
            "http://foo.localhost/x",
            "http://printer.local/x",
            "http://service.internal/x",
        ).forEach { url ->
            assertThrows(PluginExecutionBlockedException::class.java) { SsrfGuard.validate(url) }
        }
    }

    @Test
    fun `rejects loopback and private literals`() {
        listOf(
            "http://127.0.0.1:8080/x",
            "http://[::1]:8080/x",
            "http://10.1.2.3/x",
            "http://192.168.1.1/x",
            "http://172.16.0.9/x",
            "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0/x",
            "http://100.64.0.1/x",
        ).forEach { url ->
            assertThrows(PluginExecutionBlockedException::class.java) { SsrfGuard.validate(url) }
        }
    }

    @Test
    fun `rejects public hostname resolving to private address`() {
        assertThrows(PluginExecutionBlockedException::class.java) {
            SsrfGuard.validate("https://rebind.example.com/x", literalResolver("192.168.0.10"))
        }
    }

    @Test
    fun `allows public addresses`() {
        SsrfGuard.validate("https://example.com/manifest.json", literalResolver("93.184.216.34"))
        SsrfGuard.validate("http://8.8.8.8/dns-query", literalResolver("8.8.8.8"))
    }

    @Test
    fun `rejects unresolvable host`() {
        assertThrows(PluginExecutionBlockedException::class.java) {
            SsrfGuard.validate("https://does-not-exist.invalid/x", literalResolver())
        }
    }

    @Test
    fun `blocked errors carry message`() {
        try {
            SsrfGuard.validate("http://127.0.0.1/x")
            error("expected block")
        } catch (error: PluginExecutionBlockedException) {
            assertTrue(error.message!!.isNotBlank())
        }
    }
}
