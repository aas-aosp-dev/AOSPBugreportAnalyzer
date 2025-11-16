package com.bigdotdev.aospbugreportanalyzer.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class AgentMemoryRepository(
    private val store: AgentMemoryStore
) {
    private val mutex = Mutex()

    suspend fun getAllEntries(): List<AgentMemoryEntry> = mutex.withLock {
        store.loadAll()
    }

    suspend fun rememberTurn(
        conversationId: String,
        userMessage: String,
        assistantMessage: String,
        stats: MessageStats? = null,
        tags: List<String> = emptyList(),
        isSummaryTurn: Boolean = false
    ) {
        val entry = AgentMemoryEntry(
            id = generateId(),
            createdAt = "",
            conversationId = conversationId,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            tags = tags,
            meta = MemoryMeta(
                promptTokens = stats?.promptTokens,
                completionTokens = stats?.completionTokens,
                totalTokens = stats?.totalTokens,
                costUsd = stats?.costUsd,
                durationMs = stats?.durationMs,
                isSummaryTurn = isSummaryTurn
            )
        )
        mutex.withLock {
            store.append(entry)
        }
    }

    suspend fun clearAll() = mutex.withLock {
        store.clear()
    }

    private fun generateId(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString(separator = "") { byte ->
            val value = byte.toInt() and 0xFF
            value.toString(16).padStart(2, '0')
        }
    }
}

data class MessageStats(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null
)
