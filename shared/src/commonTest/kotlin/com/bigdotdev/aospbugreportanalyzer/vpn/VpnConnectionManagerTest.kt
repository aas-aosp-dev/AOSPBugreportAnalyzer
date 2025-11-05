package com.bigdotdev.aospbugreportanalyzer.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VpnConnectionManagerTest {
    @Test
    fun `connect stores session`() {
        val manager = VpnConnectionManager()
        val session = manager.connect("vless://uuid@example.com:443?security=reality")

        assertEquals(VpnConnectionStatus.CONNECTED, session.status)
        assertEquals("example.com", session.vlessUri.host)
        assertTrue(session.connectionId.isNotBlank())
        assertNotNull(manager.getSession(session.connectionId))
    }

    @Test
    fun `disconnect updates status`() {
        val manager = VpnConnectionManager()
        val session = manager.connect("vless://uuid@example.com:443")

        val disconnected = manager.disconnect(session.connectionId)

        assertNotNull(disconnected)
        assertEquals(VpnConnectionStatus.DISCONNECTED, disconnected.status)
        assertEquals(disconnected, manager.getSession(session.connectionId))
    }
}
