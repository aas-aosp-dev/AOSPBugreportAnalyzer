package core.data.local

import core.domain.chat.ChatMessage
import core.domain.chat.ChatRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

class ChatStore {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun getMessages(): List<ChatMessage> = _messages.value

    fun clear() {
        _messages.value = emptyList()
    }

    fun appendUserMessage(agentId: String, content: String): ChatMessage {
        val message = ChatMessage(
            id = newMessageId(),
            agentId = agentId,
            role = ChatRole.User,
            content = content,
        )
        _messages.update { it + message }
        return message
    }

    fun appendAssistantPlaceholder(agentId: String): ChatMessage {
        val message = ChatMessage(
            id = newMessageId(),
            agentId = agentId,
            role = ChatRole.Assistant,
            content = "",
            isPending = true,
        )
        _messages.update { it + message }
        return message
    }

    fun commitAssistantMessage(id: String, content: String) {
        _messages.update { list ->
            list.map { msg -> if (msg.id == id) msg.copy(content = content, isPending = false, error = null) else msg }
        }
    }

    fun markAssistantError(id: String, error: String) {
        _messages.update { list ->
            list.map { msg -> if (msg.id == id) msg.copy(error = error, isPending = false) else msg }
        }
    }

    companion object {
        private const val PREFIX = "msg-"
        private fun newMessageId(): String = PREFIX + Random.nextInt(0x100000, 0xFFFFFF).toString(16)
    }
}
