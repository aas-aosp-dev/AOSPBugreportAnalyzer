package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import com.bigdotdev.aospbugreportanalyzer.domain.ChatResponse
import com.bigdotdev.aospbugreportanalyzer.domain.SystemPromptPolicy
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
        val messages = if (request.history.isEmpty() && persistedHistory.isNotEmpty()) {
            buildPrompt(request.copy(history = persistedHistory))
        } else {
            buildPrompt(request)
        }

        val client = providerRouter.requireClient(request.provider)
        val jsonMode = request.strictJson || request.responseFormat.equals("json", ignoreCase = true)

        val rawContent = client.complete(request.model, messages, jsonMode)

        request.sessionId?.let { sessionId ->
            messages.filter { it.role == "user" }.lastOrNull()?.let { conversationStore.append(sessionId, it) }
        }

        if (!jsonMode) {
            request.sessionId?.let { sessionId ->
                conversationStore.append(sessionId, ChatMessage("assistant", rawContent))
            }
            return ChatResponse(
                ok = true,
                contentType = "text",
                text = rawContent
            )
        }

        val json = ensureJson.ensure(rawContent) { raw ->
            runCatching {
                client.complete(
                    model = request.model,
                    messages = listOf(
                        ChatMessage(role = "system", content = SystemPromptPolicy.DEFAULT),
                        ChatMessage(
                            role = "user",
                            content = "Return ONLY valid JSON. Fix this text to valid JSON without explanations: $raw"
                        )
                    ),
                    jsonMode = true
                )
            }.getOrNull()
        }

        request.sessionId?.let { sessionId ->
            conversationStore.append(sessionId, ChatMessage("assistant", rawContent))
        }

        return ChatResponse(
            ok = true,
            contentType = "json",
            data = json
        )
    }
}
