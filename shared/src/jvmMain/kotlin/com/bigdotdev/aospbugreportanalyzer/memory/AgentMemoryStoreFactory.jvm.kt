package com.bigdotdev.aospbugreportanalyzer.memory

import com.bigdotdev.aospbugreportanalyzer.storage.StoragePaths
import kotlinx.serialization.json.Json
import java.io.File

actual fun createAgentMemoryStore(json: Json): AgentMemoryStore {
    val file = StoragePaths.configDir.resolve("agent_memory.json").toFile()
    println("[AgentMemory] Using file: ${file.absolutePath}")
    return FileAgentMemoryStore(file.absolutePath, json)
}
