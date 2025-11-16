package com.bigdotdev.aospbugreportanalyzer.memory

import kotlinx.serialization.json.Json

actual fun createAgentMemoryStore(json: Json): AgentMemoryStore = InMemoryAgentMemoryStore()
