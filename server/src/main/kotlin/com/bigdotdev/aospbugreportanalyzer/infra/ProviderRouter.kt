package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.Agent
import com.bigdotdev.aospbugreportanalyzer.domain.ProviderType

class ProviderRouter(
    private val clients: Map<ProviderType, ProviderClient>,
    private val agents: Map<String, Agent> = emptyMap()
) {
    fun route(providerType: ProviderType): ProviderClient =
        clients[providerType]
            ?: error("No provider client registered for $providerType")

    fun availableAgents(): Collection<Agent> = agents.values
}
