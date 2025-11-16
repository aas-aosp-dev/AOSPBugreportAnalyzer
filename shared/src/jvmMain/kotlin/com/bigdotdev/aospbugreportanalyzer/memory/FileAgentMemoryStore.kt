package com.bigdotdev.aospbugreportanalyzer.memory

import java.io.File
import kotlin.collections.buildMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.parseToJsonElement

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
            val element = json.parseToJsonElement(text)
            if (element !is JsonArray) {
                emptyList()
            } else {
                element.mapNotNull { it.toAgentMemoryEntryOrNull() }
            }
        } catch (e: Exception) {
            println("Failed to read agent memory: ${'$'}{e.message}")
            emptyList()
        }
    }

    override suspend fun append(entry: AgentMemoryEntry) = withContext(Dispatchers.IO) {
        val existing = loadAll()
        val updated = existing + entry
        val jsonArray = JsonArray(updated.map { it.toJsonObject() })
        val jsonText = json.encodeToString(JsonElement.serializer(), jsonArray)

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

private fun JsonElement.toAgentMemoryEntryOrNull(): AgentMemoryEntry? {
    val obj = this as? JsonObject ?: return null
    val id = obj.stringOrNull("id") ?: return null
    val createdAt = obj.stringOrNull("createdAt") ?: return null
    val conversationId = obj.stringOrNull("conversationId") ?: return null
    val userMessage = obj.stringOrNull("userMessage") ?: return null
    val assistantMessage = obj.stringOrNull("assistantMessage") ?: return null
    val tags = obj.jsonArrayOrEmpty("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val meta = obj["meta"]?.jsonObjectOrNull()?.toMemoryMeta()

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

private fun JsonObject.toMemoryMeta(): MemoryMeta {
    return MemoryMeta(
        promptTokens = intOrNull("promptTokens"),
        completionTokens = intOrNull("completionTokens"),
        totalTokens = intOrNull("totalTokens"),
        costUsd = doubleOrNull("costUsd"),
        durationMs = longOrNull("durationMs"),
        isSummaryTurn = booleanOrNull("isSummaryTurn") ?: false
    )
}

private fun AgentMemoryEntry.toJsonObject(): JsonObject = JsonObject(
    buildMap {
        put("id", JsonPrimitive(id))
        put("createdAt", JsonPrimitive(createdAt))
        put("conversationId", JsonPrimitive(conversationId))
        put("userMessage", JsonPrimitive(userMessage))
        put("assistantMessage", JsonPrimitive(assistantMessage))
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        meta?.let { put("meta", it.toJsonObject()) } ?: put("meta", JsonNull)
    }
)

private fun MemoryMeta.toJsonObject(): JsonObject = JsonObject(
    buildMap {
        promptTokens?.let { put("promptTokens", JsonPrimitive(it)) }
        completionTokens?.let { put("completionTokens", JsonPrimitive(it)) }
        totalTokens?.let { put("totalTokens", JsonPrimitive(it)) }
        costUsd?.let { put("costUsd", JsonPrimitive(it)) }
        durationMs?.let { put("durationMs", JsonPrimitive(it)) }
        put("isSummaryTurn", JsonPrimitive(isSummaryTurn))
    }
)

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.jsonArrayOrEmpty(key: String): List<JsonElement> =
    (this[key] as? JsonArray)?.toList() ?: emptyList()

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

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
