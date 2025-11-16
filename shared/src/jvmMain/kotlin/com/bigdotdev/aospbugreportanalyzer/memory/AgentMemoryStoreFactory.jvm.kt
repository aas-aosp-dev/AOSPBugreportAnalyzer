package com.bigdotdev.aospbugreportanalyzer.memory

import kotlinx.serialization.json.Json
import java.io.File

actual fun createAgentMemoryStore(json: Json): AgentMemoryStore {
    val userHome = System.getProperty("user.home") ?: "."
    val dir = File(userHome, ".aosp_bugreport_analyzer")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val file = File(dir, "agent_memory.json")
    println("[AgentMemory] Using file: ${'$'}{file.absolutePath}")
    return FileAgentMemoryStore(file.absolutePath, json)
}
