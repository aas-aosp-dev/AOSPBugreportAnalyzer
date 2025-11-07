package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class OpenRouterClient(
    private val httpClient: HttpClient,
    private val configuration: ProviderConfiguration,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ProviderClient {
    override suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): ProviderCompletionResult {
        val apiKey = configuration.apiKey
            ?: return ProviderCompletionResult(ok = false, error = "OpenRouter API key is not configured")

        val payload = buildPayload(model, messages, jsonMode)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${configuration.baseUrl}/api/v1/chat/completions"))
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
            return ProviderCompletionResult(ok = false, error = throwable.message ?: "OpenRouter request failed")
        }

        if (response.statusCode() !in 200..299) {
            val bodySnippet = response.body().take(500)
            return ProviderCompletionResult(
                ok = false,
                error = "OpenRouter error ${response.statusCode()}: $bodySnippet",
                raw = response.body()
            )
        }

        val responseBody = response.body()
        val content = extractContent(responseBody)
            ?: return ProviderCompletionResult(
                ok = false,
                error = "OpenRouter response did not include content",
                raw = responseBody
            )

        return ProviderCompletionResult(ok = true, content = content, raw = responseBody)
    }

    private fun buildPayload(model: String, messages: List<ChatMessage>, jsonMode: Boolean): String {
        val messageArray = messages.joinToString(prefix = "[", postfix = "]") { message ->
            "{" + "\"role\":\"${message.role}\",\"content\":${json.encodeToString(String.serializer(), message.content)}" + "}"
        }

        val responseFormat = if (jsonMode) {
            "\"response_format\":{\"type\":\"json_object\"},\"temperature\":0"
        } else {
            ""
        }

        val responsePart = if (responseFormat.isNotEmpty()) ",${responseFormat}" else ""

        return "{" +
            "\"model\":${json.encodeToString(String.serializer(), model)}," +
            "\"messages\":$messageArray" +
            responsePart +
            "}"
    }

    private fun extractContent(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val message = first["message"]?.jsonObject ?: return null
        message["content"]?.jsonPrimitive?.content
    }.getOrNull()
}
