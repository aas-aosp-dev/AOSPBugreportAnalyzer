package com.bigdotdev.aospbugreportanalyzer.memory

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class FileAgentMemoryStore(
    private val filePath: String,
    private val json: Json
) : AgentMemoryStore {

    override suspend fun loadAll(): List<AgentMemoryEntry> = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            return@withContext emptyList()
        }

        val text = file.readText()
        if (text.isBlank()) {
            return@withContext emptyList()
        }

        return@withContext try {
            json.parseToJsonElement(text)
                .jsonArrayOrNull()
                ?.mapNotNull { element -> element.jsonObjectOrNull()?.toAgentMemoryEntry() }
                ?: emptyList()
        } catch (e: Exception) {
            println("Failed to read agent memory: ${'$'}{e.message}")
            emptyList()
        }
    }

    override suspend fun append(entry: AgentMemoryEntry) = withContext(Dispatchers.IO) {
        val existing = loadAll()
        val updated = existing + entry
        val jsonText = buildJsonArray {
            updated.forEach { add(it.toJsonElement()) }
        }.toString()

        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeText(jsonText)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists()) {
            file.delete()
        }
    }
}

private fun defaultMemoryFilePath(): String {
    val userHome = System.getProperty("user.home") ?: "."
    val dir = File(userHome, ".aosp_bugreport_analyzer")
    dir.mkdirs()
    return File(dir, "agent_memory.json").absolutePath
}

actual fun createAgentMemoryStore(json: Json): AgentMemoryStore {
    return FileAgentMemoryStore(
        filePath = defaultMemoryFilePath(),
        json = json
    )
}

private fun AgentMemoryEntry.toJsonElement(): JsonElement = buildJsonObject {
    put("id", id)
    put("createdAt", createdAt)
    put("conversationId", conversationId)
    put("userMessage", userMessage)
    put("assistantMessage", assistantMessage)
    put(
        "tags",
        buildJsonArray {
            tags.forEach { tag -> add(JsonPrimitive(tag)) }
        }
    )
    meta?.let { meta ->
        put(
            "meta",
            buildJsonObject {
                meta.promptTokens?.let { put("promptTokens", it) }
                meta.completionTokens?.let { put("completionTokens", it) }
                meta.totalTokens?.let { put("totalTokens", it) }
                meta.costUsd?.let { put("costUsd", it) }
                meta.durationMs?.let { put("durationMs", it) }
                put("isSummaryTurn", meta.isSummaryTurn)
            }
        )
    }
}

private fun JsonObject.toAgentMemoryEntry(): AgentMemoryEntry? {
    val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val createdAt = this["createdAt"]?.jsonPrimitive?.contentOrNull ?: return null
    val conversationId = this["conversationId"]?.jsonPrimitive?.contentOrNull ?: return null
    val userMessage = this["userMessage"]?.jsonPrimitive?.contentOrNull ?: return null
    val assistantMessage = this["assistantMessage"]?.jsonPrimitive?.contentOrNull ?: return null
    val tags = this["tags"]
        ?.jsonArrayOrNull()
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: emptyList()
    val meta = (this["meta"] as? JsonObject)?.let { metaObject ->
        MemoryMeta(
            promptTokens = metaObject["promptTokens"]?.jsonPrimitive?.intOrNull,
            completionTokens = metaObject["completionTokens"]?.jsonPrimitive?.intOrNull,
            totalTokens = metaObject["totalTokens"]?.jsonPrimitive?.intOrNull,
            costUsd = metaObject["costUsd"]?.jsonPrimitive?.doubleOrNull,
            durationMs = metaObject["durationMs"]?.jsonPrimitive?.longOrNull,
            isSummaryTurn = metaObject["isSummaryTurn"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    return AgentMemoryEntry(
        id = id,
        createdAt = createdAt,
        conversationId = conversationId,
        userMessage = userMessage,
        assistantMessage = assistantMessage,
        tags = tags,
        meta = meta
    )
}

private fun JsonElement.jsonArrayOrNull(): JsonArray? = try {
    jsonArray
} catch (_: Exception) {
    null
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = try {
    jsonObject
} catch (_: Exception) {
    null
}
