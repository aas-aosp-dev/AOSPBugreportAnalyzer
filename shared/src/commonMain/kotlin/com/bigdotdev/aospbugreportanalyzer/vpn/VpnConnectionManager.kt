package com.bigdotdev.aospbugreportanalyzer.vpn

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Keeps track of active VPN sessions that were created from VLESS keys.
 */
class VpnConnectionManager {
    private val sessions = mutableMapOf<String, VpnSession>()

    /**
     * Parses the provided [vlessKey], allocates a new [VpnSession] and stores it in memory.
     */
    fun connect(vlessKey: String): VpnSession {
        val parsed = VlessUri.parse(vlessKey)
        val sessionId = generateSessionId()
        val session = VpnSession(
            connectionId = sessionId,
            vlessUri = parsed,
            connectedAtMillis = Clock.System.now().toEpochMilliseconds(),
            status = VpnConnectionStatus.CONNECTED,
        )
        sessions[sessionId] = session
        return session
    }

    fun disconnect(connectionId: String): VpnSession? {
        val existing = sessions[connectionId] ?: return null
        val disconnected = existing.copy(status = VpnConnectionStatus.DISCONNECTED)
        sessions[connectionId] = disconnected
        return disconnected
    }

    fun getSession(connectionId: String): VpnSession? = sessions[connectionId]

    fun listSessions(): List<VpnSession> = sessions.values.sortedBy { it.connectedAtMillis }

    private fun generateSessionId(): String {
        val bytes = Random.nextBytes(16)
        val builder = StringBuilder(32)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            builder.append(HEX_CHARS[value ushr 4])
            builder.append(HEX_CHARS[value and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private const val HEX_CHARS = "0123456789abcdef"
    }
}

@Serializable
data class VpnSession(
    val connectionId: String,
    val vlessUri: VlessUri,
    val connectedAtMillis: Long,
    val status: VpnConnectionStatus,
)

@Serializable
enum class VpnConnectionStatus {
    CONNECTED,
    DISCONNECTED,
}
