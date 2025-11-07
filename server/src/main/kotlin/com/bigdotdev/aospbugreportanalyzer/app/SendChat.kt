package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import com.bigdotdev.aospbugreportanalyzer.domain.ChatResponse
import com.bigdotdev.aospbugreportanalyzer.infra.ConversationStore
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderRouter

class SendChat(
    private val providerRouter: ProviderRouter,
    private val buildPrompt: BuildPrompt,
    private val ensureJson: EnsureJson,
    private val conversationStore: ConversationStore
) {
    suspend operator fun invoke(request: ChatRequest): ChatResponse {
        val persistedHistory = request.sessionId?.let(conversationStore::get).orEmpty()
        val effectiveRequest = if (request.history.isEmpty() && persistedHistory.isNotEmpty()) {
            request.copy(history = persistedHistory)
        } else {
            request
        }

        val messages = buildPrompt(effectiveRequest)

        val client = providerRouter.route(request.provider)
        val result = client.complete(
            model = request.model,
            messages = messages,
            jsonMode = request.strictJson || request.responseFormat.equals("json", ignoreCase = true)
        )

        if (!result.ok) {
            return ChatResponse(
                ok = false,
                contentType = "text",
                text = result.error,
                raw = result.raw
            )
        }

        val rawContent = result.content.orEmpty()

        request.sessionId?.let { sessionId ->
            val userMessage = messages.lastOrNull { it.role == "user" }
            if (userMessage != null) {
                conversationStore.append(sessionId, userMessage)
            }
        }

        return if (request.strictJson || request.responseFormat.equals("json", ignoreCase = true)) {
            val json = ensureJson.ensure(rawContent)
            request.sessionId?.let { sessionId ->
                conversationStore.append(sessionId, ChatMessage("assistant", rawContent))
            }
            ChatResponse(
                ok = true,
                contentType = "json",
                data = json,
                raw = rawContent
            )
        } else {
            request.sessionId?.let { sessionId ->
                conversationStore.append(sessionId, ChatMessage("assistant", rawContent))
            }
            ChatResponse(
                ok = true,
                contentType = "text",
                text = rawContent,
                raw = rawContent
            )
        }
    }
}
