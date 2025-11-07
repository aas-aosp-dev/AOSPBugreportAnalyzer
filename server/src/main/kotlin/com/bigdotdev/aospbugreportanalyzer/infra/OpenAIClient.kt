package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage

class OpenAIClient(
    private val configuration: ProviderConfiguration
) : ProviderClient {
    override suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): String {
        val apiKey = configuration.openAiApiKey
            ?: throw ProviderNotConfiguredException("OpenAI provider is not configured")

        throw ProviderRequestException(
            "OpenAI provider integration is not implemented. Requested model=$model jsonMode=$jsonMode"
        )
    }
}
