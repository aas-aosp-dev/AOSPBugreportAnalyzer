package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.vpn.VlessConnectResponse
import com.bigdotdev.aospbugreportanalyzer.vpn.VlessDisconnectResponse
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VpnRouteTest {
    @Test
    fun `connect endpoint accepts vless key`() = testApplication {
        application { module() }

        val response = client.post("/vpn/connect") {
            contentType(ContentType.Application.Json)
            setBody("""{"vlessKey":"vless://uuid@example.com:443?security=reality"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload: VlessConnectResponse = response.body()
        assertEquals("example.com", payload.host)
        assertEquals(443, payload.port)
        assertEquals("uuid", payload.userId)
        assertNotNull(payload.connectionId)
    }

    @Test
    fun `connect endpoint validates key`() = testApplication {
        application { module() }

        val response = client.post("/vpn/connect") {
            contentType(ContentType.Application.Json)
            setBody("""{"vlessKey":"vmess://uuid@example.com:443"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `disconnect endpoint updates session`() = testApplication {
        application { module() }

        val connectResponse = client.post("/vpn/connect") {
            contentType(ContentType.Application.Json)
            setBody("""{"vlessKey":"vless://uuid@example.com:443"}""")
        }

        val payload: VlessConnectResponse = connectResponse.body()

        val disconnectResponse = client.post("/vpn/disconnect") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${payload.connectionId}"}""")
        }

        assertEquals(HttpStatusCode.OK, disconnectResponse.status)
        val disconnectPayload: VlessDisconnectResponse = disconnectResponse.body()
        assertEquals(payload.connectionId, disconnectPayload.connectionId)
        assertEquals("DISCONNECTED", disconnectPayload.status.name)
    }

    @Test
    fun `disconnect endpoint returns 404 for unknown session`() = testApplication {
        application { module() }

        val response = client.post("/vpn/disconnect") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"missing"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
