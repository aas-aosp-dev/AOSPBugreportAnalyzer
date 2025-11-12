package core.data.local

import core.ai.agents.AgentConfig
import core.ai.agents.DefaultOpenRouterAgent
import core.ai.agents.clone
import core.ai.agents.withUpdatedTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AgentsStore {
    private val _agents = MutableStateFlow<List<AgentConfig>>(listOf(DefaultOpenRouterAgent))
    val agents: StateFlow<List<AgentConfig>> = _agents.asStateFlow()

    private val _activeAgentId = MutableStateFlow(DefaultOpenRouterAgent.id)
    val activeAgentId: StateFlow<String?> = _activeAgentId.asStateFlow()

    init {
        selectAgent(DefaultOpenRouterAgent.id)
    }

    fun create(config: AgentConfig) {
        val sanitized = config.copy(apiKey = config.apiKey?.takeIf { it.isNotBlank() })
        _agents.update { it + sanitized }
        _activeAgentId.value = sanitized.id
    }

    fun update(config: AgentConfig) {
        val sanitized = config.copy(apiKey = config.apiKey?.takeIf { it.isNotBlank() })
        _agents.update { list ->
            list.map { existing -> if (existing.id == sanitized.id) sanitized.withUpdatedTimestamp() else existing }
        }
    }

    fun delete(id: String) {
        _agents.update { list ->
            val newList = list.filterNot { it.id == id }
            if (newList.isEmpty()) listOf(DefaultOpenRouterAgent) else newList
        }
        if (_activeAgentId.value == id) {
            _activeAgentId.value = _agents.value.first().id
        }
    }

    fun duplicate(id: String) {
        val existing = _agents.value.find { it.id == id } ?: return
        val clone = existing.clone()
        create(clone)
    }

    fun selectAgent(id: String) {
        if (_agents.value.none { it.id == id }) return
        _activeAgentId.value = id
    }

    fun agentById(id: String): AgentConfig? = _agents.value.find { it.id == id }

    fun activeAgent(): AgentConfig? {
        val id = _activeAgentId.value ?: return null
        return agentById(id)
    }
}
