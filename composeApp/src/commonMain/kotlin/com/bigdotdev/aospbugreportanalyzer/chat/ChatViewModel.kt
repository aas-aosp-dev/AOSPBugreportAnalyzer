package com.bigdotdev.aospbugreportanalyzer.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bigdotdev.aospbugreportanalyzer.data.ChatRepository
import com.bigdotdev.aospbugreportanalyzer.chat.ChatRole.ASSISTANT
import com.bigdotdev.aospbugreportanalyzer.chat.ChatRole.SYSTEM
import com.bigdotdev.aospbugreportanalyzer.chat.ChatRole.USER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val repository: ChatRepository,
    private val agent: ExpertAgent,
    private val scope: CoroutineScope
) {
    val messages = mutableStateListOf<ChatMessage>()
    var isSending by mutableStateOf(false)
        private set
    private var activeJob: Job? = null

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isSending) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = USER,
            text = trimmed,
            createdAt = currentTime()
        )
        messages += userMessage
        isSending = true

        val historySnapshot = messages.toList()
        activeJob?.cancel()
        activeJob = scope.launch {
            val start = currentTime()
            val result = runCatching {
                val requestMessages = buildList {
                    agent.systemPrompt.takeIf { it.isNotBlank() }?.let { prompt ->
                        add(ChatMessageRequest(role = SYSTEM, content = prompt))
                    }
                    historySnapshot
                        .filter { it.role != SYSTEM }
                        .forEach { message ->
                            add(ChatMessageRequest(role = message.role, content = message.text))
                        }
                }
                repository.sendChatCompletion(agent.defaultModel, requestMessages)
            }

            val duration = currentTime() - start
            result.onSuccess { response ->
                val stats = MessageStats(
                    modelId = response.model,
                    promptTokens = response.promptTokens,
                    completionTokens = response.completionTokens,
                    totalTokens = response.totalTokens,
                    costUsd = response.costUsd,
                    durationMs = response.durationMs ?: duration,
                    error = response.error
                )
                applyStatsToLastUserMessage(userMessage.id, stats)
                val assistantText = response.text.ifBlank {
                    response.error?.let { "Ошибка: $it" } ?: ""
                }
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ASSISTANT,
                    text = assistantText,
                    createdAt = currentTime(),
                    stats = stats
                )
                messages += assistantMessage
            }.onFailure { throwable ->
                val stats = MessageStats(
                    modelId = agent.defaultModel,
                    durationMs = duration,
                    error = throwable.message ?: throwable::class.simpleName
                )
                applyStatsToLastUserMessage(userMessage.id, stats)
                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ASSISTANT,
                    text = "Ошибка: ${stats.error}",
                    createdAt = currentTime(),
                    stats = stats
                )
                messages += assistantMessage
            }
            isSending = false
            activeJob = null
        }
    }

    fun cancelOngoingRequest() {
        activeJob?.cancel()
        activeJob = null
        isSending = false
    }

    private fun applyStatsToLastUserMessage(id: String, stats: MessageStats) {
        val index = messages.indexOfLast { it.id == id }
        if (index >= 0) {
            messages[index] = messages[index].copy(stats = stats)
        }
    }

    private fun currentTime(): Long = System.currentTimeMillis()
}
