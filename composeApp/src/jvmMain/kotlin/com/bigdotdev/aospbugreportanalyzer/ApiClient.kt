package com.bigdotdev.aospbugreportanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val DEFAULT_BFF_URL = "http://localhost:8080"
private val CLIENT_JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }

@Serializable
data class ChatBffRequest(
    val provider: String,
    val model: String,
    val history: List<ChatHistoryItem>,
    @SerialName("user_input") val userInput: String,
    @SerialName("strict_json") val strictJson: Boolean,
    @SerialName("system_prompt") val systemPrompt: String?,
    @SerialName("response_format") val responseFormat: String = "json",
    @SerialName("session_id") val sessionId: String? = null,
    val agent: String? = null
)

@Serializable
data class ChatHistoryItem(
    val role: String,
    val content: String
)

@Serializable
private data class ChatBffResponsePayload(
    val ok: Boolean,
    @SerialName("content_type") val contentType: String,
    val data: JsonElement? = null,
    val text: String? = null,
    val error: String? = null,
    val retryAfterMs: Long? = null
)

data class ChatBffResponse(
    val ok: Boolean,
    val contentType: String,
    val data: JsonElement? = null,
    val text: String? = null,
    val error: String? = null,
    val retryAfterMs: Long? = null
)

object ApiClient {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    suspend fun chatComplete(request: ChatBffRequest, baseUrl: String = DEFAULT_BFF_URL): ChatBffResponse {
        val body = CLIENT_JSON.encodeToString(request)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/chat/complete"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = runCatching {
            withContext(Dispatchers.IO) {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            }
        }.getOrElse { throwable ->
            return ChatBffResponse(
                ok = false,
                contentType = "text",
                error = throwable.message ?: "Не удалось подключиться к серверу"
            )
        }

        val responseBody = response.body()
        val payload = runCatching { CLIENT_JSON.decodeFromString(ChatBffResponsePayload.serializer(), responseBody) }
            .getOrElse { throwable ->
                return ChatBffResponse(
                    ok = response.statusCode() in 200..299,
                    contentType = "text",
                    text = responseBody,
                    error = throwable.message ?: "Не удалось прочитать ответ сервера"
                )
            }

        if (response.statusCode() !in 200..299) {
            return ChatBffResponse(
                ok = false,
                contentType = payload.contentType,
                data = payload.data,
                text = payload.text,
                error = payload.error ?: "Сервер вернул ошибку ${response.statusCode()}",
                retryAfterMs = payload.retryAfterMs
            )
        }

        return ChatBffResponse(
            ok = payload.ok,
            contentType = payload.contentType,
            data = payload.data,
            text = payload.text,
            error = payload.error,
            retryAfterMs = payload.retryAfterMs
        )
    }
}
