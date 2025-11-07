package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val OPENROUTER_COMPLETIONS_PATH = "/chat/completions"

class OpenRouterClient(
    private val httpClient: HttpClient,
    private val configuration: ProviderConfiguration,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ProviderClient {

    override suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): String {
        val apiKey = configuration.openRouterApiKey
            ?: throw ProviderNotConfiguredException("OpenRouter provider is not configured")

        val payload = json.encodeToString(
            OpenRouterRequest(
                model = model,
                messages = messages.map { it.toPayload() },
                responseFormat = if (jsonMode) ResponseFormat("json_object") else null,
                temperature = if (jsonMode) 0.0 else null
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${configuration.baseUrl}$OPENROUTER_COMPLETIONS_PATH"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
                configuration.referer?.let { header("HTTP-Referer", it) }
                configuration.title?.let { header("X-Title", it) }
            }
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = runCatching {
            withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
        }.getOrElse { throwable ->
            throw ProviderRequestException("OpenRouter request failed", cause = throwable)
        }

        if (response.statusCode() !in 200..299) {
            throw ProviderRequestException(
                "OpenRouter responded with HTTP ${response.statusCode()}: ${response.body().take(200)}",
                statusCode = response.statusCode()
            )
        }

        val body = response.body()
        return extractContent(body)
            ?: throw ProviderRequestException("OpenRouter response did not contain content")
    }

    private fun extractContent(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val message = first["message"]?.jsonObject ?: return null
        message["content"]?.jsonPrimitive?.content
    }.getOrNull()

    @Serializable
    private data class OpenRouterRequest(
        val model: String,
        val messages: List<ChatMessagePayload>,
        val response_format: ResponseFormat? = null,
        val temperature: Double? = null
    )

    @Serializable
    private data class ChatMessagePayload(
        val role: String,
        val content: String
    )

    @Serializable
    private data class ResponseFormat(val type: String)

    private fun ChatMessage.toPayload(): ChatMessagePayload = ChatMessagePayload(role, content)
}
