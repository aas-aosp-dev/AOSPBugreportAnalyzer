package com.bigdotdev.aospbugreportanalyzer.domain

data class ChatRequest(
    val provider: ProviderType,
    val model: String,
    val history: List<ChatMessage>,
    val userInput: String,
    val strictJson: Boolean,
    val systemPrompt: String?,
    val responseFormat: String = "json",
    val sessionId: String? = null,
    val agentId: String? = null
)
