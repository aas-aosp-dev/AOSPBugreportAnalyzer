package com.bigdotdev.aospbugreportanalyzer.domain

interface Agent {
    val id: String
    val displayName: String
    val role: AgentRole
    val provider: ProviderType
    val defaultModel: String

    suspend fun act(input: AgentInput): AgentOutput
}

data class AgentInput(
    val conversation: List<ChatMessage>,
    val goal: String
)

data class AgentOutput(
    val response: ChatResponse
)

data class StaticAgent(
    override val id: String,
    override val displayName: String,
    override val role: AgentRole,
    override val provider: ProviderType,
    override val defaultModel: String
) : Agent {
    override suspend fun act(input: AgentInput): AgentOutput {
        throw UnsupportedOperationException("Agent orchestration is not implemented yet")
    }
}
