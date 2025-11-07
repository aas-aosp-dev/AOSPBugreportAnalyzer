package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import com.bigdotdev.aospbugreportanalyzer.domain.SystemPromptPolicy

private val GREETING_REGEX = Regex("^(hi|hello|hey|привет|здравствуй|hola|bonjour)([!., ]|$)", RegexOption.IGNORE_CASE)

class BuildPrompt {
    operator fun invoke(request: ChatRequest): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        resolveSystemPrompt(request)?.let { prompt ->
            messages += ChatMessage(role = "system", content = prompt)
        }

        if (request.history.isNotEmpty()) {
            messages += request.history
        }

        val userMessage = ChatMessage(role = "user", content = buildUserContent(request))
        messages += userMessage

        return messages
    }

    private fun resolveSystemPrompt(request: ChatRequest): String? {
        val override = request.systemPrompt?.takeIf { it.isNotBlank() }
        return override ?: request.strictJson.takeIf { it }?.let { SystemPromptPolicy.DEFAULT }
    }

    private fun buildUserContent(request: ChatRequest): String {
        val trimmed = request.userInput.trim()
        if (!request.strictJson) {
            return trimmed.ifEmpty { "Please summarise our discussion so far and continue the conversation." }
        }

        if (trimmed.isEmpty() || GREETING_REGEX.containsMatchIn(trimmed)) {
            return "Summarise the existing conversation and produce at least one item describing the user's greeting. Echo the user input verbatim in the summary item."
        }

        return trimmed
    }
}
