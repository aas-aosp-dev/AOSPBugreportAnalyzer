package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.storage.HistoryLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val embeddingsJson = Json

suspend fun callOpenRouterEmbeddings(
    texts: List<String>,
    model: String,
    apiKeyOverride: String? = null
): Result<List<List<Double>>> {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return Result.failure(IllegalStateException("Missing OpenRouter API key"))
    }

    if (texts.isEmpty()) {
        return Result.success(emptyList())
    }

    val payload = buildEmbeddingsRequestBody(texts, model)

    return withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(OpenRouterConfig.EMBEDDINGS_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", OpenRouterConfig.referer)
                .header("X-Title", OpenRouterConfig.title)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val body = response.body()

            HistoryLogger.log("OpenRouter Embeddings: model=$model, texts=${texts.size}, status=$status")

            if (status !in 200..299) {
                val errorMessage = "OpenRouter embeddings failed with status $status"
                HistoryLogger.log("OpenRouter Embeddings error: httpCode=$status, body=${body.take(200)}")
                throw IllegalStateException(errorMessage)
            }

            parseEmbeddingsResponse(body)
        }.onFailure { error ->
            HistoryLogger.log("OpenRouter Embeddings error: model=$model, texts=${texts.size}, message=${error.message}")
        }
    }
}

private fun buildEmbeddingsRequestBody(texts: List<String>, model: String): String {
    val escapedInputs = texts.joinToString(prefix = "[", postfix = "]") { "\"" + encodeJsonString(it) + "\"" }
    return """{""" +
        "\"model\":\"${encodeJsonString(model)}\"," +
        "\"input\":$escapedInputs" +
        "}"""
}

private fun parseEmbeddingsResponse(body: String): List<List<Double>> {
    val root = embeddingsJson.parseToJsonElement(body).jsonObject
    val dataArray = root["data"]?.jsonArray
        ?: throw IllegalStateException("OpenRouter embeddings: missing data field")

    return dataArray.map { item ->
        val embeddingArray = item.jsonObject["embedding"]?.jsonArray
            ?: throw IllegalStateException("OpenRouter embeddings: missing embedding field")

        embeddingArray.map { element ->
            element.jsonPrimitive.doubleOrNull
                ?: element.jsonPrimitive.content.toDoubleOrNull()
                ?: throw IllegalStateException("OpenRouter embeddings: invalid embedding value")
        }
    }
}

private fun encodeJsonString(value: String): String {
    val sb = StringBuilder()
    value.forEach { c ->
        when (c) {
            '\\' -> sb.append("\\\\")
            '\"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
