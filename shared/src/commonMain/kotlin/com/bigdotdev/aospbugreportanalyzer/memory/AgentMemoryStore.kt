package com.bigdotdev.aospbugreportanalyzer.memory

import kotlinx.serialization.json.Json

interface AgentMemoryStore {
    suspend fun loadAll(): List<AgentMemoryEntry>
    suspend fun append(entry: AgentMemoryEntry)
    suspend fun clear()
}

class InMemoryAgentMemoryStore : AgentMemoryStore {
    private val entries = mutableListOf<AgentMemoryEntry>()

    override suspend fun loadAll(): List<AgentMemoryEntry> = entries.toList()

    override suspend fun append(entry: AgentMemoryEntry) {
        entries += entry
    }

    override suspend fun clear() {
        entries.clear()
    }
}

expect fun createAgentMemoryStore(json: Json): AgentMemoryStore
