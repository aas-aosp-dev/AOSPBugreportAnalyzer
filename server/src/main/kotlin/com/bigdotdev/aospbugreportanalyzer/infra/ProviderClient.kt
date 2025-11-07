package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage

class ProviderNotConfiguredException(message: String) : IllegalStateException(message)
class ProviderRequestException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

interface ProviderClient {
    suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): String
}
