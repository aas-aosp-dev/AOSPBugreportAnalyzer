package com.bigdotdev.aospbugreportanalyzer.api

import com.bigdotdev.aospbugreportanalyzer.app.SendChat
import com.bigdotdev.aospbugreportanalyzer.app.StreamChat
import com.bigdotdev.aospbugreportanalyzer.domain.ChatResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.chatRoutes(sendChat: SendChat, streamChat: StreamChat) {
    val _ = streamChat
    route("/api/v1/chat") {
        post("/complete") {
            val requestDto = runCatching { call.receive<ChatCompleteRequestDto>() }
                .getOrElse { throwable ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to (throwable.message ?: "Invalid request")))
                    return@post
                }

            val response = runCatching { sendChat(requestDto.toDomain()) }
                .getOrElse { throwable ->
                    call.respond(HttpStatusCode.InternalServerError, mapOf("ok" to false, "error" to (throwable.message ?: "Unexpected error")))
                    return@post
                }

            call.respond(HttpStatusCode.OK, response.toDto())
        }

        get("/stream") {
            // Placeholder SSE handler
            call.respond(HttpStatusCode.NotImplemented, mapOf("ok" to false, "error" to "SSE streaming is not implemented yet"))
        }
    }
}

private fun ChatResponse.toDto(): ChatCompleteResponseDto = ChatCompleteResponseDto(
    ok = ok,
    contentType = contentType,
    data = data,
    text = text,
    error = error
)
