package com.bigdotdev.aospbugreportanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Эмбеддинги через локальный Ollama:
 *
 * POST http://localhost:11434/api/embeddings
 *
 * Request:
 * {
 *   "model": "nomic-embed-text",
 *   "prompt": "..."      // один текст
 * }
 *
 * Response:
 * {
 *   "embedding": [ ... ] // один вектор
 * }
 *
 * Мы вызываем Ollama по одному запросу на каждый чанк.
 */
suspend fun callOpenRouterEmbeddings(
    texts: List<String>,
    model: String,
    apiKeyOverride: String? = null, // не используется, но оставлен для совместимости
    onChunkProcessed: ((Int) -> Unit)? = null
): Result<List<List<Double>>> = withContext(Dispatchers.IO) {

    if (texts.isEmpty()) {
        return@withContext Result.success(emptyList())
    }

    // Максимальная длина текста, которую отправляем в Ollama
    // (если текст больше — аккуратно отрезаем начало)
    val maxPromptLength = 2000

    val json = Json {
        ignoreUnknownKeys = true
    }

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    val results = mutableListOf<List<Double>>()

    try {
        for ((idx, text) in texts.withIndex()) {
            val safePrompt =
                if (text.length > maxPromptLength) text.take(maxPromptLength) else text

            val bodyJson = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("prompt", JsonPrimitive(safePrompt))
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/embeddings"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(JsonObject.serializer(), bodyJson)
                    )
                )
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                return@withContext Result.failure<List<List<Double>>>(
                    IllegalStateException(
                        "Ollama embeddings request failed for chunk $idx: " +
                                "status ${response.statusCode()}, body=${response.body()}"
                    )
                )
            }

            val root = json.parseToJsonElement(response.body()).jsonObject
            val embeddingArray = root["embedding"]?.jsonArray
                ?: return@withContext Result.failure<List<List<Double>>>(
                    IllegalStateException("Ollama embeddings response missing 'embedding' field for chunk $idx")
                )

            val embedding: List<Double> = embeddingArray.map { value ->
                value.jsonPrimitive.double
            }

            results.add(embedding)
            onChunkProcessed?.invoke(idx + 1)
        }

        return@withContext Result.success(results.toList())
    } catch (t: Throwable) {
        return@withContext Result.failure<List<List<Double>>>(t)
    }
}
