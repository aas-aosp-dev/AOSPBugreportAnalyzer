package core.ui.state

import core.ai.agents.AgentConfig
import core.data.local.AgentsStore
import core.data.local.ChatStore
import core.data.local.MetricsStore
import core.domain.chat.ChatMessage
import core.domain.chat.ChatService
import core.domain.chat.ProviderUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AppViewModel(
    private val agentsStore: AgentsStore,
    private val chatStore: ChatStore,
    private val metricsStore: MetricsStore,
    private val chatService: ChatService,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            combine(
                agentsStore.agents,
                agentsStore.activeAgentId,
                chatStore.messages,
                metricsStore.usage,
            ) { agents, activeId, messages, metrics ->
                QuadState(agents, activeId, messages, metrics)
            }.collect { state ->
                _uiState.value = _uiState.value.copy(
                    agents = state.agents,
                    activeAgentId = state.activeId,
                    chatMessages = state.messages,
                    metrics = state.metrics,
                )
            }
        }
    }

    fun selectAgent(id: String) {
        agentsStore.selectAgent(id)
    }

    fun toggleAgentsManager(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAgentsManager = show)
    }

    fun toggleSpec(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSpec = show)
    }

    fun toggleLabMode() {
        _uiState.value = _uiState.value.copy(labMode = !_uiState.value.labMode)
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(isSending = false)
            return
        }
        val agent = agentsStore.activeAgent()
        if (agent == null) {
            _uiState.value = _uiState.value.copy(
                isSending = false,
                statusMessage = "Select an agent before sending a message.",
            )
            return
        }
        if (agent.apiKey.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                isSending = false,
                statusMessage = "Add an API key for ${agent.name} in Agents to start chatting.",
            )
            return
        }
        _uiState.value = _uiState.value.copy(isSending = true, statusMessage = null)
        scope.launch {
            try {
                chatService.sendMessage(trimmed).join()
            } finally {
                _uiState.value = _uiState.value.copy(isSending = false)
            }
        }
    }

    fun showUsageFor(messageId: String) {
        val metrics = metricsStore.get(messageId) ?: run {
            val message = chatStore.getMessages().find { it.id == messageId }
            val agent = message?.agentId?.let { agentsStore.agentById(it) }
            ProviderUsage(
                provider = agent?.provider?.displayName ?: "Unknown",
                model = agent?.model ?: "—",
                latencyMs = 0,
                inputTokens = null,
                outputTokens = null,
                totalTokens = null,
                costUsd = null,
                temperature = agent?.temperature,
                seed = agent?.seed,
                sessionId = null,
            )
        }
        _uiState.value = _uiState.value.copy(
            usageDetails = UsagePanelState(messageId, metrics)
        )
    }

    fun hideUsage() {
        _uiState.value = _uiState.value.copy(usageDetails = null)
    }

    fun clearStatusMessage() {
        if (_uiState.value.statusMessage != null) {
            _uiState.value = _uiState.value.copy(statusMessage = null)
        }
    }

    fun upsertAgent(agent: AgentConfig) {
        if (agentsStore.agentById(agent.id) == null) {
            agentsStore.create(agent)
        } else {
            agentsStore.update(agent)
        }
    }

    fun deleteAgent(id: String) {
        agentsStore.delete(id)
    }

    fun duplicateAgent(id: String) {
        agentsStore.duplicate(id)
    }

    private data class QuadState(
        val agents: List<AgentConfig>,
        val activeId: String?,
        val messages: List<ChatMessage>,
        val metrics: Map<String, ProviderUsage>,
    )
}
