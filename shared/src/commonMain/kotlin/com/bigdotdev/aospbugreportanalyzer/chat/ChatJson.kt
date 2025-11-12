package com.bigdotdev.aospbugreportanalyzer.chat

import com.bigdotdev.aospbugreportanalyzer.DEFAULT_MODEL_ID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.collections.buildList

object ChatJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeRequest(request: ChatCompletionRequest): String {
        val payload = buildJsonObject {
            put("model", request.model)
            put("messages", request.messages.toJsonArray())
        }
        return payload.toString()
    }

    fun decodeRequest(raw: String): ChatCompletionRequest {
        val root = json.parseToJsonElement(raw).jsonObject
        val model = root["model"]?.jsonPrimitive?.contentOrNull ?: DEFAULT_MODEL_ID
        val messages = root["messages"]?.jsonArray?.toMessageList().orEmpty()
        return ChatCompletionRequest(model = model, messages = messages)
    }

    fun encodeResponse(response: ChatCompletionResponse): String {
        val payload = buildJsonObject {
            put("text", response.text)
            response.model?.let { put("model", it) }
            response.promptTokens?.let { put("promptTokens", it) }
            response.completionTokens?.let { put("completionTokens", it) }
            response.totalTokens?.let { put("totalTokens", it) }
            response.costUsd?.let { put("costUsd", it) }
            response.durationMs?.let { put("durationMs", it) }
            response.error?.let { put("error", it) }
        }
        return payload.toString()
    }

    fun decodeResponse(raw: String): ChatCompletionResponse {
        val root = json.parseToJsonElement(raw).jsonObject
        return ChatCompletionResponse(
            text = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            model = root["model"]?.jsonPrimitive?.contentOrNull,
            promptTokens = root["promptTokens"]?.jsonPrimitive?.intOrNull,
            completionTokens = root["completionTokens"]?.jsonPrimitive?.intOrNull,
            totalTokens = root["totalTokens"]?.jsonPrimitive?.intOrNull,
            costUsd = root["costUsd"]?.jsonPrimitive?.doubleOrNull,
            durationMs = root["durationMs"]?.jsonPrimitive?.longOrNull,
            error = root["error"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun List<ChatMessageRequest>.toJsonArray(): JsonArray = buildJsonArray {
        for (message in this@toJsonArray) {
            add(
                buildJsonObject {
                    put("role", message.role.wireValue)
                    put("content", message.content)
                }
            )
        }
    }

    private fun JsonArray.toMessageList(): List<ChatMessageRequest> = buildList {
        for (element in this@toMessageList) {
            val obj = element.jsonObject
            val role = obj["role"]?.jsonPrimitive?.contentOrNull?.let(ChatRole::fromWire) ?: ChatRole.USER
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
            add(ChatMessageRequest(role = role, content = content))
        }
    }
}
