package core.domain.chat

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class ChatRole { User, Assistant, System }

data class ChatMessage(
    val id: String,
    val agentId: String,
    val role: ChatRole,
    val content: String,
    val timestamp: Instant = Clock.System.now(),
    val isPending: Boolean = false,
    val error: String? = null,
)

data class ProviderUsage(
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
    val temperature: Double?,
    val seed: Long?,
    val timestamp: Instant = Clock.System.now(),
    val sessionId: String? = null,
)
