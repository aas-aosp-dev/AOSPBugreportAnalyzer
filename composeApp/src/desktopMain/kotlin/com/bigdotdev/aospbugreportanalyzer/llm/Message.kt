package com.bigdotdev.aospbugreportanalyzer.llm

import com.bigdotdev.aospbugreportanalyzer.settings.AppSettings

/** Represents a single message in a chat completion style conversation. */
data class Message(val role: String, val content: String)

object MessageBuilder {
    fun build(
        userPrompt: String,
        settings: AppSettings,
        previousMessages: List<Message> = emptyList()
    ): List<Message> {
        val messages = mutableListOf<Message>()
        if (settings.strictJsonEnabled) {
            val prompt = settings.systemPromptText.ifBlank { AppSettings.DEFAULT_SYSTEM_PROMPT }
            messages += Message(role = "system", content = prompt)
        }
        messages += previousMessages
        messages += Message(role = "user", content = userPrompt)
        return messages
    }
}
