package com.bigdotdev.aospbugreportanalyzer.memory

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class FileAgentMemoryStore(
    private val filePath: String,
    private val json: Json
) : AgentMemoryStore {

    override suspend fun loadAll(): List<AgentMemoryEntry> = withContext(Dispatchers.IO) {
        val file = memoryFile()
        if (!file.exists()) {
            println("[AgentMemory] loadAll: file does not exist (${file.absolutePath})")
            return@withContext emptyList()
        }

        val text = file.readText()
        if (text.isBlank()) {
            println("[AgentMemory] loadAll: file is blank (${file.absolutePath})")
            return@withContext emptyList()
        }

        return@withContext try {
            val entries = json.decodeFromString<List<AgentMemoryEntry>>(text)
            println(
                "[AgentMemory] loadAll: loaded ${entries.size} entries from ${file.absolutePath}"
            )
            entries
        } catch (e: Exception) {
            println(
                "[AgentMemory] loadAll: failed to parse file ${file.absolutePath}: ${e.message}"
            )
            emptyList()
        }
    }

    override suspend fun append(entry: AgentMemoryEntry) = withContext(Dispatchers.IO) {
        val existing = loadAll()
        val updated = existing + entry
        val text = json.encodeToString<List<AgentMemoryEntry>>(updated)

        val file = memoryFile()
        file.parentFile?.mkdirs()
        file.writeText(text)
        println("[AgentMemory] append: now ${updated.size} entries in ${file.absolutePath}")
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val file = memoryFile()
        if (file.exists()) {
            file.delete()
            println("[AgentMemory] clear: removed ${file.absolutePath}")
        }
    }

    private fun memoryFile(): File = File(filePath)
}
