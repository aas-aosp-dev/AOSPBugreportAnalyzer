package com.bigdotdev.aospbugreportanalyzer.memory

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
            json.decodeFromString(
                deserializer = ListSerializer(AgentMemoryEntry.serializer()),
                string = text
            )
        } catch (e: Exception) {
            println("Failed to read agent memory: ${'$'}{e.message}")
            emptyList()
        }
    }

    override suspend fun append(entry: AgentMemoryEntry) = withContext(Dispatchers.IO) {
        val existing = loadAll()
        val updated = existing + entry
        val jsonText = json.encodeToString(
            serializer = ListSerializer(AgentMemoryEntry.serializer()),
            value = updated
        )

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
