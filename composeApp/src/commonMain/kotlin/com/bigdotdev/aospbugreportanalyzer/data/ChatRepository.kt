package com.bigdotdev.aospbugreportanalyzer.data

import com.bigdotdev.aospbugreportanalyzer.SERVER_BASE_URL
import com.bigdotdev.aospbugreportanalyzer.chat.ChatCompletionRequest
import com.bigdotdev.aospbugreportanalyzer.chat.ChatCompletionResponse
import com.bigdotdev.aospbugreportanalyzer.chat.ChatMessageRequest
import com.bigdotdev.aospbugreportanalyzer.chat.ChatJson
import com.bigdotdev.aospbugreportanalyzer.network.applyJsonHeaders
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText

class ChatRepository(
    private val client: HttpClient,
    private val baseUrl: String = SERVER_BASE_URL
) {
    suspend fun sendChatCompletion(
        model: String,
        messages: List<ChatMessageRequest>
    ): ChatCompletionResponse {
        val payload = ChatJson.encodeRequest(ChatCompletionRequest(model = model, messages = messages))
        val httpResponse = client.post("$baseUrl/chat/completions") {
            applyJsonHeaders()
            setBody(payload)
        }
        val body = httpResponse.bodyAsText()
        return ChatJson.decodeResponse(body)
    }
}
