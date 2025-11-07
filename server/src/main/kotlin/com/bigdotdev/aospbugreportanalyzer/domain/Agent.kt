package com.bigdotdev.aospbugreportanalyzer.domain

data class Agent(
    val id: String,
    val displayName: String,
    val role: AgentRole,
    val provider: ProviderType,
    val defaultModel: String
)
