package com.bigdotdev.aospbugreportanalyzer.chat

import com.bigdotdev.aospbugreportanalyzer.DEFAULT_MODEL_ID

enum class ChatRole(val wireValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(value: String): ChatRole = entries.firstOrNull { it.wireValue == value } ?: USER
    }
}

data class ChatMessageRequest(
    val role: ChatRole,
    val content: String
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageRequest>
)

data class ChatCompletionResponse(
    val text: String,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val error: String? = null
)

data class MessageStats(
    val modelId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val error: String? = null
)

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Long,
    val stats: MessageStats? = null
)

data class ExpertAgent(
    val id: String = "expert",
    val displayName: String = "Эксперт",
    val description: String = "Опытный помощник по анализу багрепортов и Android.",
    val systemPrompt: String = "You are an experienced Android and bug report analysis expert. Provide concise and practical guidance.",
    val defaultModel: String = DEFAULT_MODEL_ID
)
