package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.chat.ChatCompletionRequest
import com.bigdotdev.aospbugreportanalyzer.chat.ChatCompletionResponse
import com.bigdotdev.aospbugreportanalyzer.chat.ChatJson
import com.bigdotdev.aospbugreportanalyzer.DEFAULT_MODEL_ID
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun Application.module() {
    val client = HttpClient(CIO) {
        expectSuccess = false
    }

    environment.monitor.subscribe(ApplicationStopping) {
        client.close()
    }

    routing {
        post("/chat/completions") {
            val requestBody = call.receiveText()
            val request = ChatJson.decodeRequest(requestBody)
            val start = System.currentTimeMillis()
            val result = runCatching { fetchCompletion(client, request) }
            val duration = System.currentTimeMillis() - start

            result.onSuccess { routerResponse ->
                val response = ChatCompletionResponse(
                    text = routerResponse.text,
                    model = routerResponse.model ?: request.model,
                    promptTokens = routerResponse.promptTokens,
                    completionTokens = routerResponse.completionTokens,
                    totalTokens = routerResponse.totalTokens,
                    costUsd = calculateCostUsd(routerResponse.model ?: request.model, routerResponse.promptTokens, routerResponse.completionTokens),
                    durationMs = duration
                )
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = ChatJson.encodeResponse(response)
                )
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unknown error"
                val response = ChatCompletionResponse(
                    text = "Ошибка: $message",
                    model = request.model,
                    durationMs = duration,
                    error = message
                )
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                    text = ChatJson.encodeResponse(response)
                )
            }
        }
    }
}

private suspend fun fetchCompletion(client: HttpClient, request: ChatCompletionRequest): RouterCompletion {
    val apiKey = System.getenv("OPENROUTER_API_KEY")
        ?: throw IllegalStateException("OPENROUTER_API_KEY is not set")

    val payload = buildOpenRouterPayload(request)
    val httpResponse = try {
        client.post("https://openrouter.ai/api/v1/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            header("HTTP-Referer", "https://github.com/bigdotdev/AOSPBugreportAnalyzer")
            header("X-Title", "AOSP Bugreport Analyzer")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    } catch (exception: ClientRequestException) {
        throw OpenRouterException(parseRouterError(exception.response.bodyAsText()) ?: exception.message.orEmpty(), exception)
    } catch (exception: ServerResponseException) {
        throw OpenRouterException(parseRouterError(exception.response.bodyAsText()) ?: exception.message.orEmpty(), exception)
    }

    val body = httpResponse.bodyAsText()
    if (!httpResponse.status.isSuccess()) {
        val message = parseRouterError(body) ?: "Request failed with status ${httpResponse.status.value}"
        throw OpenRouterException(message)
    }

    val root = json.parseToJsonElement(body).jsonObject
    val usage = root["usage"]?.jsonObject
    val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
    val message = choice?.get("message")?.jsonObject
    val text = message?.extractContentText().orEmpty()

    return RouterCompletion(
        text = text,
        model = root["model"]?.jsonPrimitive?.contentOrNull,
        promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
        completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
        totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull
    )
}

private fun buildOpenRouterPayload(request: ChatCompletionRequest): String {
    val base = json.parseToJsonElement(ChatJson.encodeRequest(request)).jsonObject
    val payload = buildJsonObject {
        base.forEach { (key, value) ->
            put(key, value)
        }
        put(
            "usage",
            buildJsonObject {
                put("include", JsonPrimitive(true))
            }
        )
    }
    return payload.toString()
}

private fun parseRouterError(raw: String): String? {
    if (raw.isBlank()) return null
    val element = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
    val errorObj = element["error"]?.jsonObject
    if (errorObj != null) {
        return errorObj["message"]?.jsonPrimitive?.contentOrNull ?: raw
    }
    return element["message"]?.jsonPrimitive?.contentOrNull ?: raw
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

private data class Price(val inPerM: Double, val outPerM: Double)

private val priceMap = mapOf(
    DEFAULT_MODEL_ID to Price(inPerM = 0.05, outPerM = 0.08)
)

private fun calculateCostUsd(modelId: String?, promptTokens: Int?, completionTokens: Int?): Double? {
    val price = modelId?.let(priceMap::get) ?: return null
    if (promptTokens == null && completionTokens == null) return null

    val inTokens = max(promptTokens ?: 0, 0)
    val outTokens = max(completionTokens ?: 0, 0)

    return (inTokens * price.inPerM + outTokens * price.outPerM) / 1_000_000.0
}

private data class RouterCompletion(
    val text: String,
    val model: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)

private class OpenRouterException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun JsonObject.extractContentText(): String {
    val explicitText = this["text"]?.jsonPrimitive?.contentOrNull
    if (!explicitText.isNullOrBlank()) return explicitText

    val contentElement = this["content"] ?: return ""
    return when (contentElement) {
        is JsonPrimitive -> contentElement.contentOrNull.orEmpty()
        is JsonArray -> contentElement.joinToString(separator = "") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull.orEmpty()
                is JsonObject -> part.extractContentText()
                else -> part.toString()
            }
        }
        is JsonObject -> contentElement.extractContentText()
        else -> contentElement.toString()
    }
}
