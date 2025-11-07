package com.bigdotdev.aospbugreportanalyzer.api

import com.bigdotdev.aospbugreportanalyzer.app.SendChat
import com.bigdotdev.aospbugreportanalyzer.app.StreamChat
import com.bigdotdev.aospbugreportanalyzer.domain.ChatResponse
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderNotConfiguredException
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.chatRoutes(sendChat: SendChat, streamChat: StreamChat) {
    route("/api/v1/chat") {
        post("/complete") {
            val dto = runCatching { call.receive<ChatCompleteRequest>() }
                .getOrElse { throwable ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ChatCompleteResponse(ok = false, contentType = "text", error = throwable.message ?: "Invalid request")
                    )
                    return@post
                }

            val domainRequest = runCatching { dto.toDomain() }
                .getOrElse { throwable ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ChatCompleteResponse(ok = false, contentType = "text", error = throwable.message ?: "Invalid request")
                    )
                    return@post
                }

            val result = try {
                sendChat(domainRequest)
            } catch (notConfigured: ProviderNotConfiguredException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ChatCompleteResponse(ok = false, contentType = "text", error = notConfigured.message ?: "Provider not configured")
                )
                return@post
            } catch (upstream: ProviderRequestException) {
                val status = when (upstream.statusCode) {
                    429 -> HttpStatusCode.TooManyRequests
                    504 -> HttpStatusCode.GatewayTimeout
                    else -> HttpStatusCode.BadGateway
                }
                val message = when (status) {
                    HttpStatusCode.TooManyRequests -> "rate limit"
                    HttpStatusCode.GatewayTimeout -> "upstream timeout"
                    else -> upstream.message ?: "Upstream provider failed"
                }
                call.respond(
                    status,
                    ChatCompleteResponse(
                        ok = false,
                        contentType = "text",
                        error = message,
                        retryAfterMs = if (status == HttpStatusCode.TooManyRequests) 1_000 else null
                    )
                )
                return@post
            } catch (throwable: Throwable) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ChatCompleteResponse(ok = false, contentType = "text", error = throwable.message ?: "Unexpected error")
                )
                return@post
            }

            call.respond(HttpStatusCode.OK, result.toDto())
        }

        get("/stream") {
            call.respond(
                HttpStatusCode.NotImplemented,
                ChatCompleteResponse(ok = false, contentType = "text", error = "SSE not implemented yet")
            )
        }
    }
}

private fun ChatResponse.toDto(): ChatCompleteResponse = ChatCompleteResponse(
    ok = ok,
    contentType = contentType,
    data = data,
    text = text,
    error = error,
    retryAfterMs = retryAfterMs
)
