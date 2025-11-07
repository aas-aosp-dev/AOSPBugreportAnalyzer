package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatResponse

class OrchestrateAgents {
    suspend fun execute(goal: String): ChatResponse {
        throw UnsupportedOperationException("Multi-agent orchestration is not implemented yet: goal=$goal")
    }
}
