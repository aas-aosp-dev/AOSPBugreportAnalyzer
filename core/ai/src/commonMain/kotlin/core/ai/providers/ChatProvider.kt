package core.ai.providers

import core.ai.agents.AgentConfig
import core.domain.chat.ChatMessage
import core.domain.chat.ChatRole
import core.domain.chat.ProviderUsage

/** Provider result including assistant message text and usage metrics. */
data class ProviderResult(
    val content: String,
    val role: ChatRole = ChatRole.Assistant,
    val usage: ProviderUsage,
)

/** Contract implemented by concrete provider clients. */
fun interface ChatProvider {
    suspend fun execute(agent: AgentConfig, history: List<ChatMessage>): ProviderResult
}
