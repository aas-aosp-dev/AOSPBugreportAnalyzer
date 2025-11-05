package com.bigdotdev.aospbugreportanalyzer.vpn

import kotlinx.serialization.Serializable

@Serializable
data class VlessConnectRequest(
    val vlessKey: String,
)

@Serializable
data class VlessConnectResponse(
    val connectionId: String,
    val status: VpnConnectionStatus,
    val host: String,
    val port: Int,
    val userId: String,
    val parameters: Map<String, String>,
    val displayName: String? = null,
) {
    companion object {
        fun fromSession(session: VpnSession): VlessConnectResponse = VlessConnectResponse(
            connectionId = session.connectionId,
            status = session.status,
            host = session.vlessUri.host,
            port = session.vlessUri.port,
            userId = session.vlessUri.userId,
            parameters = session.vlessUri.parameters,
            displayName = session.vlessUri.displayName,
        )
    }
}

@Serializable
data class ErrorResponse(val message: String?)
