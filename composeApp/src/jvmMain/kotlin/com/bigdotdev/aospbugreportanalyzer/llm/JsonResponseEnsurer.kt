package com.bigdotdev.aospbugreportanalyzer.llm

import com.bigdotdev.aospbugreportanalyzer.settings.AppSettings

fun ensureJsonOrFix(
    send: (List<Message>) -> String,
    userPrompt: String,
    settings: AppSettings,
    history: List<Message> = emptyList()
): String {
    val initialMessages = MessageBuilder.build(
        userPrompt = userPrompt,
        settings = settings,
        previousMessages = history
    )
    val initialResponse = send(initialMessages).trim()
    if (initialResponse.isLikelyJson()) {
        return initialResponse
    }

    val fixPrompt = buildString {
        append("Верни ТОЛЬКО JSON. Исправь:\n")
        append(initialResponse)
    }

    val extendedHistory = history + Message(role = "user", content = userPrompt) +
        Message(role = "assistant", content = initialResponse)

    val retryMessages = MessageBuilder.build(
        userPrompt = fixPrompt,
        settings = settings,
        previousMessages = extendedHistory
    )

    return send(retryMessages).trim()
}

private fun String.isLikelyJson(): Boolean {
    for (ch in this) {
        if (!ch.isWhitespace()) {
            return ch == '{' || ch == '['
        }
    }
    return false
}
