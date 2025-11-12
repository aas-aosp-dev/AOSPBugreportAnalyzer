package app.desktop

import core.ai.agents.AgentConfig
import core.ai.providers.ChatProvider
import core.ai.providers.PricingTable
import core.ai.providers.ProviderResult
import core.domain.chat.ChatMessage
import core.domain.chat.ChatRole
import core.domain.chat.ProviderUsage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenRouterProvider : ChatProvider {
    private val client = HttpClient(CIO) {
        install(Logging) {
            level = LogLevel.NONE
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(agent: AgentConfig, history: List<ChatMessage>): ProviderResult {
        val apiKey = agent.apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenRouter API key is required")

        val messages = buildJsonArray {
            agent.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(prompt))
                })
            }
            history.forEach { message ->
                if (message.isPending) return@forEach
                val role = when (message.role) {
                    ChatRole.User -> "user"
                    ChatRole.Assistant -> "assistant"
                    ChatRole.System -> "system"
                }
                add(buildJsonObject {
                    put("role", JsonPrimitive(role))
                    put("content", JsonPrimitive(message.content))
                })
            }
        }

        val body = buildJsonObject {
            put("model", JsonPrimitive(agent.model))
            put("messages", messages)
            put("temperature", JsonPrimitive(agent.temperature))
            agent.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            agent.topP?.let { put("top_p", JsonPrimitive(it)) }
            agent.seed?.let { put("seed", JsonPrimitive(it)) }
            if (agent.strictJson) {
                put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            }
        }

        val responseText = client.post(ENDPOINT) {
            header("Authorization", "Bearer $apiKey")
            header("HTTP-Referer", "https://github.com/bigdotdev/aospbugreportanalyzer")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyAsText()

        val payload = json.parseToJsonElement(responseText).jsonObject
        val choice = payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("Empty response from OpenRouter")
        val messageContent = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val usage = payload["usage"]?.jsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull
        val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull
        val pricing = PricingTable.find(agent.model)
        val cost = pricing?.let { entry ->
            val inputCost = promptTokens?.let { tokens ->
                entry.inputCostPer1K?.let { price -> price * (tokens / 1000.0) }
            }
            val outputCost = completionTokens?.let { tokens ->
                entry.outputCostPer1K?.let { price -> price * (tokens / 1000.0) }
            }
            listOfNotNull(inputCost, outputCost).takeIf { it.isNotEmpty() }?.sum()
        }

        val providerUsage = ProviderUsage(
            provider = agent.provider.displayName,
            model = agent.model,
            latencyMs = 0,
            inputTokens = promptTokens,
            outputTokens = completionTokens,
            totalTokens = totalTokens,
            costUsd = cost,
            temperature = agent.temperature,
            seed = agent.seed,
        )

        return ProviderResult(
            content = messageContent.trim(),
            usage = providerUsage,
        )
    }

    companion object {
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
