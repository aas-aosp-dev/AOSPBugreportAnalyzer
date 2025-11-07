package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage

class GroqClient(
    private val configuration: ProviderConfiguration
) : ProviderClient {
    override suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): String {
        val apiKey = configuration.groqApiKey
            ?: throw ProviderNotConfiguredException("Groq provider is not configured")

        throw ProviderRequestException(
            "Groq provider integration is not implemented. Requested model=$model jsonMode=$jsonMode"
        )
    }
}
