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

private const val DEFAULT_BFF_URL = "http://localhost:8080"

@Serializable
data class ChatBffRequest(
    val provider: String,
    val model: String,
    val history: List<ChatHistoryItem>,
    @SerialName("user_input") val userInput: String,
    @SerialName("strict_json") val strictJson: Boolean,
    @SerialName("system_prompt") val systemPrompt: String?,
    @SerialName("response_format") val responseFormat: String = "json"
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
    val error: String? = null
)

data class ChatBffResponse(
    val ok: Boolean,
    val contentType: String,
    val data: String? = null,
    val text: String? = null,
    val error: String? = null
)

object ApiClient {
    private val httpClient: HttpClient = HttpClient.newBuilder().build()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun chatComplete(request: ChatBffRequest, baseUrl: String = DEFAULT_BFF_URL): ChatBffResponse {
        val body = json.encodeToString(request)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v1/chat/complete"))
            .header("Content-Type", "application/json")
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
                text = null,
                error = throwable.message ?: "Failed to call BFF"
            )
        }

        val responseBody = response.body()
        if (response.statusCode() !in 200..299) {
            return ChatBffResponse(
                ok = false,
                contentType = "text",
                text = responseBody,
                error = "BFF returned ${response.statusCode()}"
            )
        }

        val payload = runCatching { json.decodeFromString<ChatBffResponsePayload>(responseBody) }
            .getOrElse { throwable ->
                return ChatBffResponse(
                    ok = false,
                    contentType = "text",
                    text = responseBody,
                    error = throwable.message ?: "Failed to decode BFF response"
                )
            }

        return ChatBffResponse(
            ok = payload.ok,
            contentType = payload.contentType,
            data = payload.data?.let { json.encodeToString(JsonElement.serializer(), it) },
            text = payload.text,
            error = payload.error
        )
    }
}
