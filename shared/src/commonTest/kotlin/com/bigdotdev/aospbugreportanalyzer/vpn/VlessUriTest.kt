package com.bigdotdev.aospbugreportanalyzer.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VlessUriTest {
    @Test
    fun `parse valid vless uri`() {
        val uri = VlessUri.parse(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?encryption=none&security=reality&type=grpc#My%20VPN"
        )

        assertEquals("123e4567-e89b-12d3-a456-426614174000", uri.userId)
        assertEquals("example.com", uri.host)
        assertEquals(443, uri.port)
        assertEquals("none", uri.parameters["encryption"])
        assertEquals("reality", uri.security)
        assertEquals("grpc", uri.transport)
        assertEquals("My VPN", uri.displayName)
    }

    @Test
    fun `parse handles ipv6`() {
        val uri = VlessUri.parse("vless://userid@[2001:db8::1]:8443")

        assertEquals("userid", uri.userId)
        assertEquals("2001:db8::1", uri.host)
        assertEquals(8443, uri.port)
        assertEquals(emptyMap(), uri.parameters)
        assertNull(uri.displayName)
    }

    @Test
    fun `rejects invalid scheme`() {
        assertFailsWith<IllegalArgumentException> {
            VlessUri.parse("vmess://uuid@example.com:443")
        }
    }

    @Test
    fun `rejects missing port`() {
        assertFailsWith<IllegalArgumentException> {
            VlessUri.parse("vless://uuid@example.com")
        }
    }
}
