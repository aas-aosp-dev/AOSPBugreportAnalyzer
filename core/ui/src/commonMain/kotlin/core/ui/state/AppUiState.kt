package core.ui.state

import core.ai.agents.AgentConfig
import core.domain.chat.ChatMessage
import core.domain.chat.ProviderUsage

data class AppUiState(
    val agents: List<AgentConfig> = emptyList(),
    val activeAgentId: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
    val metrics: Map<String, ProviderUsage> = emptyMap(),
    val isSending: Boolean = false,
    val labMode: Boolean = false,
    val showAgentsManager: Boolean = false,
    val showSpec: Boolean = false,
    val usageDetails: UsagePanelState? = null,
)

data class UsagePanelState(
    val messageId: String,
    val usage: ProviderUsage,
)
