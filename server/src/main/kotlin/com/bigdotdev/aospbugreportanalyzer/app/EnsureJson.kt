package com.bigdotdev.aospbugreportanalyzer.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class EnsureJson(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    fun ensure(raw: String): JsonObject {
        parseJson(raw)?.let { return it }

        val repaired = repair(raw)
        parseJson(repaired)?.let { return it }

        return fallback(raw)
    }

    private fun parseJson(candidate: String): JsonObject? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null
        return try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun repair(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1)
        }
        return raw
    }

    private fun fallback(raw: String): JsonObject = buildJsonObject {
        put("version", "1.0")
        put("ok", false)
        put("generated_at", Instant.now().toString())
        put("items", json.parseToJsonElement("[]"))
        put("error", "Failed to coerce provider response into JSON. Raw=${raw.take(200)}")
    }
}
