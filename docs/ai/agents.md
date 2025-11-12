# Agent Configuration

Agents describe how the application talks to LLM providers.

```kotlin
data class AgentConfig(
    val id: String,
    val name: String,
    val provider: AgentProvider,
    val model: String,
    val apiKey: String?,
    val systemPrompt: String,
    val temperature: Double,
    val maxTokens: Int?,
    val topP: Double?,
    val seed: Long?,
    val strictJson: Boolean
)
```

**Recommendations**

- Keep temperatures low (≤ 0.4) for deterministic parsing.
- Enable `strictJson` only for providers that support JSON schema responses.
- Store secrets securely — current build keeps keys in memory only.
- Duplicate agents to experiment without losing base presets.
