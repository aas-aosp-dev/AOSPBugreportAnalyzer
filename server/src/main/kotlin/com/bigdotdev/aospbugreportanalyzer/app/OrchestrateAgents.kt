package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.Agent
import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatResponse

class OrchestrateAgents {
    suspend fun execute(goal: String, conversation: List<ChatMessage>, agents: List<Agent>): ChatResponse {
        throw UnsupportedOperationException(
            "Multi-agent orchestration is not implemented yet: goal=$goal agents=${agents.map(Agent::id)}"
        )
    }
}
