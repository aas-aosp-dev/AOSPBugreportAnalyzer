package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage

class GroqClient : ProviderClient {
    override suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): ProviderCompletionResult {
        return ProviderCompletionResult(
            ok = false,
            error = "Groq provider is not yet configured. Requested model=$model jsonMode=$jsonMode",
            raw = null
        )
    }
}
