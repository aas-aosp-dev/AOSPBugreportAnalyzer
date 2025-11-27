package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.BUGREPORT_EMBEDDING_CHUNK_LIMIT_CHARS
import com.bigdotdev.aospbugreportanalyzer.BUGREPORT_EMBEDDING_MAX_SPLIT_DEPTH
import com.bigdotdev.aospbugreportanalyzer.BUGREPORT_EMBEDDING_MIN_CHUNK_CHARS
import com.bigdotdev.aospbugreportanalyzer.storage.HistoryLogger
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
    apiKeyOverride: String? = null // не используется, но оставлен для совместимости
): Result<List<List<Double>>> = withContext(Dispatchers.IO) {

    if (texts.isEmpty()) {
        return@withContext Result.success(emptyList())
    }

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
                if (text.length > BUGREPORT_EMBEDDING_CHUNK_LIMIT_CHARS) text.take(BUGREPORT_EMBEDDING_CHUNK_LIMIT_CHARS) else text

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
        }

        return@withContext Result.success(results.toList())
    } catch (t: Throwable) {
        return@withContext Result.failure<List<List<Double>>>(t)
    }
}

private suspend fun tryEmbedChunk(
    text: String,
    model: String,
    apiKey: String?
): List<Double>? {
    return try {
        val result = callOpenRouterEmbeddings(listOf(text), model, apiKeyOverride = apiKey)
        result.getOrNull()?.firstOrNull()
    } catch (e: Throwable) {
        HistoryLogger.log(
            "Embedding chunk failed: ${e::class.simpleName}: ${e.message?.take(200)}"
        )
        null
    }
}

suspend fun embedChunkRobust(
    chunkText: String,
    model: String,
    apiKey: String?,
    depth: Int = 0
): List<List<Double>> {
    if (chunkText.isBlank()) return emptyList()

    if (depth >= BUGREPORT_EMBEDDING_MAX_SPLIT_DEPTH ||
        chunkText.length <= BUGREPORT_EMBEDDING_MIN_CHUNK_CHARS
    ) {
        val truncated = chunkText.take(BUGREPORT_EMBEDDING_CHUNK_LIMIT_CHARS)
        val embedding = tryEmbedChunk(truncated, model, apiKey)
        if (embedding == null) {
            HistoryLogger.log(
                "Embedding dropped for too problematic chunk at depth=$depth, length=${chunkText.length}"
            )
            return emptyList()
        }
        return listOf(embedding)
    }

    val truncated = chunkText.take(BUGREPORT_EMBEDDING_CHUNK_LIMIT_CHARS)
    val embedding = tryEmbedChunk(truncated, model, apiKey)
    if (embedding != null) {
        return listOf(embedding)
    }

    val mid = chunkText.length / 2
    val left = chunkText.substring(0, mid)
    val right = chunkText.substring(mid)

    val leftEmbeddings = embedChunkRobust(left, model, apiKey, depth + 1)
    val rightEmbeddings = embedChunkRobust(right, model, apiKey, depth + 1)
    return leftEmbeddings + rightEmbeddings
}
