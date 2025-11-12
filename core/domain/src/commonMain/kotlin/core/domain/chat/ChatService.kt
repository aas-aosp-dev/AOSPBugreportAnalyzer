package core.domain.chat

import core.ai.agents.AgentProvider
import core.ai.providers.ChatProvider
import core.ai.providers.ProviderResult
import core.data.local.AgentsStore
import core.data.local.ChatStore
import core.data.local.MetricsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.coroutines.CoroutineContext

class ChatService(
    private val agentsStore: AgentsStore,
    private val chatStore: ChatStore,
    private val metricsStore: MetricsStore,
    private val providerResolver: ProviderResolver,
    private val externalScope: CoroutineScope,
    private val callContext: CoroutineContext = Dispatchers.Default,
) {
    fun sendMessage(content: String): Job {
        val agent = agentsStore.activeAgent() ?: return Job()
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Job()
        chatStore.appendUserMessage(agent.id, trimmed)
        val placeholder = chatStore.appendAssistantPlaceholder(agent.id)
        return externalScope.launch(callContext) {
            val provider = providerResolver.resolve(agent.provider)
            val start = Clock.System.now()
            try {
                val history = chatStore.getMessages()
                    .filter { it.agentId == agent.id }
                val result: ProviderResult = provider.execute(agent, history)
                val end = Clock.System.now()
                val latency = end.toEpochMilliseconds() - start.toEpochMilliseconds()
                val usage = result.usage.copy(latencyMs = latency)
                metricsStore.put(placeholder.id, usage)
                chatStore.commitAssistantMessage(
                    id = placeholder.id,
                    content = result.content,
                )
            } catch (t: Throwable) {
                chatStore.markAssistantError(placeholder.id, t.message ?: "Unknown error")
            }
        }
    }
}

fun interface ProviderResolver {
    fun resolve(provider: AgentProvider): ChatProvider
}
