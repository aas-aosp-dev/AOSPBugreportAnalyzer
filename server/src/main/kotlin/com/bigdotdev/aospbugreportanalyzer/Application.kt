package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.vpn.ErrorResponse
import com.bigdotdev.aospbugreportanalyzer.vpn.VlessConnectRequest
import com.bigdotdev.aospbugreportanalyzer.vpn.VlessConnectResponse
import com.bigdotdev.aospbugreportanalyzer.vpn.VpnConnectionManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.SerializationException

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val vpnConnectionManager = VpnConnectionManager()

    routing {
        get("/") {
            call.respondText("Ktor: ${Greeting().greet()}")
        }

        post("/vpn/connect") {
            val request = try {
                call.receive<VlessConnectRequest>()
            } catch (_: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                return@post
            }

            val session = try {
                vpnConnectionManager.connect(request.vlessKey)
            } catch (failure: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(failure.message))
                return@post
            }

            call.respond(VlessConnectResponse.fromSession(session))
        }
    }
}