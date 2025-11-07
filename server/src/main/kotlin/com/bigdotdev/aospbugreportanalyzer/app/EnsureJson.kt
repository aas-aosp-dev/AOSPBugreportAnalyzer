package com.bigdotdev.aospbugreportanalyzer.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class EnsureJson(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    suspend fun ensure(
        raw: String,
        repairAttempt: suspend (String) -> String?
    ): JsonElement {
        parseJson(raw)?.let { return it }

        val repaired = repairAttempt(raw)
        if (repaired != null) {
            parseJson(repaired)?.let { return it }
        }

        return fallback(raw)
    }

    private fun parseJson(candidate: String): JsonElement? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }

    private fun fallback(raw: String): JsonElement = buildJsonObject {
        put("version", "1.0")
        put("ok", false)
        put("generated_at", Instant.now().toString())
        put("items", buildJsonArray { })
        put("error", "Failed to coerce provider response into JSON. Raw=${raw.take(200)}")
    }
}
