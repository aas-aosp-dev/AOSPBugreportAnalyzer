package com.bigdotdev.aospbugreportanalyzer.memory

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.parseToJsonElement
import kotlinx.serialization.json.put

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
                element.mapNotNull { it.asMemoryEntryOrNull() }
            }
        } catch (e: Exception) {
            println("Failed to read agent memory: ${'$'}{e.message}")
            emptyList()
        }
    }

    override suspend fun append(entry: AgentMemoryEntry) = withContext(Dispatchers.IO) {
        val existing = loadAll()
        val updated = existing + entry
        val jsonArray = JsonArray(updated.map { it.toJsonElement() })
        val jsonText = jsonArray.toString()

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

private fun JsonElement.asMemoryEntryOrNull(): AgentMemoryEntry? {
    val obj = this as? JsonObject ?: return null
    val id = obj["id"].asStringOrNull() ?: return null
    val createdAt = obj["createdAt"].asStringOrNull() ?: return null
    val conversationId = obj["conversationId"].asStringOrNull() ?: return null
    val userMessage = obj["userMessage"].asStringOrNull() ?: return null
    val assistantMessage = obj["assistantMessage"].asStringOrNull() ?: return null

    val tags = obj["tags"]
        ?.let { element ->
            (element as? JsonArray)
                ?.mapNotNull { it.asStringOrNull() }
        }
        ?: emptyList()

    val meta = (obj["meta"] as? JsonObject)?.let { metaObj ->
        MemoryMeta(
            promptTokens = metaObj["promptTokens"].asIntOrNull(),
            completionTokens = metaObj["completionTokens"].asIntOrNull(),
            totalTokens = metaObj["totalTokens"].asIntOrNull(),
            costUsd = metaObj["costUsd"].asDoubleOrNull(),
            durationMs = metaObj["durationMs"].asLongOrNull(),
            isSummaryTurn = metaObj["isSummaryTurn"].asBooleanOrNull() ?: false
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

private fun AgentMemoryEntry.toJsonElement(): JsonElement = buildJsonObject {
    put("id", id)
    put("createdAt", createdAt)
    put("conversationId", conversationId)
    put("userMessage", userMessage)
    put("assistantMessage", assistantMessage)
    put("tags", buildJsonArray {
        tags.forEach { add(it) }
    })
    meta?.let { metaValue ->
        put("meta", buildJsonObject {
            metaValue.promptTokens?.let { put("promptTokens", it) }
            metaValue.completionTokens?.let { put("completionTokens", it) }
            metaValue.totalTokens?.let { put("totalTokens", it) }
            metaValue.costUsd?.let { put("costUsd", it) }
            metaValue.durationMs?.let { put("durationMs", it) }
            put("isSummaryTurn", metaValue.isSummaryTurn)
        })
    }
}

private fun JsonElement?.asStringOrNull(): String? = this?.jsonPrimitive?.contentOrNull
private fun JsonElement?.asIntOrNull(): Int? = this?.jsonPrimitive?.intOrNull
private fun JsonElement?.asLongOrNull(): Long? = this?.jsonPrimitive?.longOrNull
private fun JsonElement?.asDoubleOrNull(): Double? = this?.jsonPrimitive?.doubleOrNull
private fun JsonElement?.asBooleanOrNull(): Boolean? = this?.jsonPrimitive?.booleanOrNull

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
