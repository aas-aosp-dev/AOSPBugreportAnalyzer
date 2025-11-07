package com.bigdotdev.aospbugreportanalyzer.infra

import com.bigdotdev.aospbugreportanalyzer.domain.Agent
import com.bigdotdev.aospbugreportanalyzer.domain.ProviderType

class ProviderRouter(
    private val clients: Map<ProviderType, ProviderClient>,
    private val agents: Map<String, Agent> = emptyMap()
) {
    fun requireClient(providerType: ProviderType): ProviderClient =
        clients[providerType] ?: throw ProviderNotConfiguredException("Provider $providerType is not registered")

    fun agentById(id: String): Agent? = agents[id]

    fun availableAgents(): Collection<Agent> = agents.values
}
