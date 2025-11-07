package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage

data class ProviderCompletionResult(
    val ok: Boolean,
    val content: String? = null,
    val error: String? = null,
    val raw: String? = null
)

interface ProviderClient {
    suspend fun complete(model: String, messages: List<ChatMessage>, jsonMode: Boolean): ProviderCompletionResult
}
