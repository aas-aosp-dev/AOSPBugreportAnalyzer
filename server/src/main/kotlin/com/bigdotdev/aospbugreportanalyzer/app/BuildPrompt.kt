package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import com.bigdotdev.aospbugreportanalyzer.domain.SystemPromptPolicy

class BuildPrompt {
    operator fun invoke(request: ChatRequest): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        val systemPrompt = resolveSystemPrompt(request)
        if (systemPrompt != null) {
            messages += ChatMessage(role = "system", content = systemPrompt)
        }

        if (request.history.isNotEmpty()) {
            messages += request.history
        }

        val userContent = request.userInput.ifBlank {
            if (request.strictJson) {
                "Summarize the conversation so far and echo the latest user intent as a summary item."
            } else {
                "Provide a helpful summary of the conversation so far."
            }
        }

        messages += ChatMessage(role = "user", content = userContent)

        return messages
    }

    private fun resolveSystemPrompt(request: ChatRequest): String? {
        val requestedPrompt = request.systemPrompt?.takeIf { it.isNotBlank() }
        if (requestedPrompt != null) {
            return requestedPrompt
        }

        return if (request.strictJson) {
            SystemPromptPolicy.DEFAULT
        } else {
            null
        }
    }
}
