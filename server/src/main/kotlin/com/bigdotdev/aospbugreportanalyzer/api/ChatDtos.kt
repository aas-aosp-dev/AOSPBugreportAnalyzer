package com.bigdotdev.aospbugreportanalyzer.api

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import com.bigdotdev.aospbugreportanalyzer.domain.ProviderType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatCompleteRequestDto(
    val provider: String,
    val model: String,
    val history: List<ApiChatMessageDto> = emptyList(),
    @SerialName("user_input") val userInput: String,
    @SerialName("strict_json") val strictJson: Boolean = false,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("response_format") val responseFormat: String = "json",
    @SerialName("session_id") val sessionId: String? = null,
    val agent: String? = null
) {
    fun toDomain(): ChatRequest {
        val providerType = ProviderType.entries.firstOrNull { it.name.equals(provider, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported provider: $provider")

        val messages = history.map { ChatMessage(role = it.role, content = it.content) }

        return ChatRequest(
            provider = providerType,
            model = model,
            history = messages,
            userInput = userInput,
            strictJson = strictJson,
            systemPrompt = systemPrompt,
            responseFormat = responseFormat,
            sessionId = sessionId,
            agentId = agent
        )
    }
}

@Serializable
data class ApiChatMessageDto(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompleteResponseDto(
    val ok: Boolean,
    @SerialName("content_type") val contentType: String,
    val data: JsonObject? = null,
    val text: String? = null,
    val error: String? = null
)
