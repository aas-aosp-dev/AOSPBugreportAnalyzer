package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import java.util.concurrent.ConcurrentHashMap

class ConversationStore {
    private val conversations = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    fun append(sessionId: String, message: ChatMessage) {
        conversations.compute(sessionId) { _, existing ->
            val list = existing ?: mutableListOf()
            list += message
            list
        }
    }

    fun get(sessionId: String): List<ChatMessage> = conversations[sessionId]?.toList() ?: emptyList()
}
