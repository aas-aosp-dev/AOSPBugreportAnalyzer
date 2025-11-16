package com.bigdotdev.aospbugreportanalyzer.memory

import kotlinx.serialization.Serializable

@Serializable
data class AgentMemoryEntry(
    val id: String,
    val createdAt: String,
    val conversationId: String,
    val userMessage: String,
    val assistantMessage: String,
    val tags: List<String> = emptyList(),
    val meta: MemoryMeta? = null
)

@Serializable
data class MemoryMeta(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val isSummaryTurn: Boolean = false
)
