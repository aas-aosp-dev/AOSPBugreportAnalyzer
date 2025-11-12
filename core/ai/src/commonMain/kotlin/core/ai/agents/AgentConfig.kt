package core.ai.agents

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.nextInt
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Supported LLM providers. */
enum class AgentProvider(val displayName: String) {
    OpenRouter("OpenRouter"),
    GigaChat("GigaChat"),
    Yandex("Yandex")
}

/**
 * Configuration used to call an LLM.
 */
data class AgentConfig(
    val id: String = newAgentId(),
    val name: String,
    val provider: AgentProvider,
    val model: String,
    val apiKey: String?,
    val systemPrompt: String,
    val temperature: Double,
    val maxTokens: Int?,
    val topP: Double?,
    val seed: Long?,
    val strictJson: Boolean,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
)

fun AgentConfig.withUpdatedTimestamp(): AgentConfig = copy(updatedAt = Clock.System.now())

fun AgentConfig.clone(newNameSuffix: String = " Copy"): AgentConfig = copy(
    id = newAgentId(),
    name = name + newNameSuffix,
    apiKey = null,
    createdAt = Clock.System.now(),
    updatedAt = Clock.System.now(),
)

/** Default preset used on first launch. */
val DefaultOpenRouterAgent = AgentConfig(
    name = "OpenRouter Default",
    provider = AgentProvider.OpenRouter,
    model = "mistralai/mixtral-8x7b-instruct",
    apiKey = null,
    systemPrompt = "You are an assistant helping with Android bugreport triage.",
    temperature = 0.2,
    maxTokens = null,
    topP = null,
    seed = null,
    strictJson = false,
)

private const val ID_PREFIX = "agent-"

private fun newAgentId(): String {
    val random = Random.nextInt(0x100000, 0xFFFFFF)
    return ID_PREFIX + random.toString(16)
}

fun validateTemperature(value: Double): Double = value.coerceIn(0.0, 2.0)

fun Double.withUiPrecision(): Double = (this * 100).roundToInt() / 100.0
