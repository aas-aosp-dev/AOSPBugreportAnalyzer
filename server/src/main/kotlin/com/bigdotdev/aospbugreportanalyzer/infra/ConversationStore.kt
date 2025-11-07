package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import java.util.concurrent.ConcurrentHashMap

interface ConversationStore {
    fun append(sessionId: String, message: ChatMessage)
    fun get(sessionId: String): List<ChatMessage>
}

class InMemoryConversationStore : ConversationStore {
    private val conversations = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    override fun append(sessionId: String, message: ChatMessage) {
        conversations.compute(sessionId) { _, existing ->
            val list = existing ?: mutableListOf()
            list += message
            list
        }
    }

    override fun get(sessionId: String): List<ChatMessage> = conversations[sessionId]?.toList() ?: emptyList()
}
