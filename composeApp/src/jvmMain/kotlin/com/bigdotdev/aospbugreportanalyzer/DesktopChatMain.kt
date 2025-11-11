package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private enum class TeamDirection { DEVELOPMENT, DESIGN, ANALYTICS }
private enum class MemberRole { TEAM_LEAD, EMPLOYEE }
private enum class AuthorRole { USER, TEAM_LEAD, EMPLOYEE }

private enum class Screen { MAIN, SETTINGS, TEAMS, MODEL_BENCH }

private data class Member(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: MemberRole,
    val position: String,
    val temperature: Double,
    val prompt: String
)

private data class Team(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val direction: TeamDirection,
    val members: MutableList<Member> = mutableListOf()
)

private sealed class ChatRoomId {
    data object Main : ChatRoomId()
    data class TeamRoom(val teamId: String) : ChatRoomId()
}

private data class ChatMessage(
    val id: String,
    val room: ChatRoomId,
    val author: String,
    val role: AuthorRole,
    val text: String,
    val timestamp: Long
)

private data class ConversationState(
    val main: MutableList<ChatMessage> = mutableListOf(),
    val teamThreads: MutableMap<String, MutableList<ChatMessage>> = mutableMapOf(),
    val activeRoom: ChatRoomId = ChatRoomId.Main
)

private fun Team.deepCopy(): Team = copy(members = members.map { it.copy() }.toMutableList())

private fun TeamDirection.displayName(): String = when (this) {
    TeamDirection.DEVELOPMENT -> "Разработка"
    TeamDirection.DESIGN -> "Дизайн"
    TeamDirection.ANALYTICS -> "Аналитика"
}

private fun MemberRole.displayName(): String = when (this) {
    MemberRole.TEAM_LEAD -> "Тимлид"
    MemberRole.EMPLOYEE -> "Сотрудник"
}

private fun AuthorRole.displayName(): String = when (this) {
    AuthorRole.USER -> "Пользователь"
    AuthorRole.TEAM_LEAD -> "Тимлид"
    AuthorRole.EMPLOYEE -> "Сотрудник"
}

private fun defaultTeams(): List<Team> {
    val development = Team(
        title = "Разработка",
        direction = TeamDirection.DEVELOPMENT,
        members = mutableListOf(
            Member(
                name = "Иван",
                role = MemberRole.TEAM_LEAD,
                position = "Senior Android разработчик с большим опытом. Холодный ум, делает очень качественно.",
                temperature = 0.0,
                prompt = "Act as Android tech lead. Be rigorous and practical."
            ),
            Member(
                name = "Максим",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик. В первую очередь думает об архитектуре.",
                temperature = 0.0,
                prompt = "Architect-first mindset. Suggest clean, maintainable designs."
            ),
            Member(
                name = "Геннадий",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик. Не любит сложные архитектуры, делает быстро, меньше абстракций.",
                temperature = 0.0,
                prompt = "Prefer simple, fast solutions. Avoid over-abstraction."
            ),
            Member(
                name = "Юрия",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик. За безопасность, следит за утечками и рисками.",
                temperature = 0.0,
                prompt = "Security-first. Enforce safe patterns and data protection."
            )
        )
    )

    val design = Team(
        title = "Дизайн",
        direction = TeamDirection.DESIGN,
        members = mutableListOf(
            Member(
                name = "Мария",
                role = MemberRole.TEAM_LEAD,
                position = "Лид-дизайнер. Системное мышление, соответствие гайдам, UX-рисерч.",
                temperature = 0.0,
                prompt = "Lead designer. Ensure guideline alignment and UX clarity."
            ),
            Member(
                name = "Ольга",
                role = MemberRole.EMPLOYEE,
                position = "Senior дизайнер. UX-паттерны, accessibility.",
                temperature = 0.0,
                prompt = "Senior designer. Accessibility and patterns."
            ),
            Member(
                name = "Дмитрий",
                role = MemberRole.EMPLOYEE,
                position = "Middle UI дизайнер. Состояния, микровзаимодействия.",
                temperature = 0.0,
                prompt = "Middle UI. States and micro-interactions."
            )
        )
    )

    val analytics = Team(
        title = "Аналитика",
        direction = TeamDirection.ANALYTICS,
        members = mutableListOf(
            Member(
                name = "Алексей",
                role = MemberRole.TEAM_LEAD,
                position = "Лид-аналитик. Метрики успеха, риски, A/B.",
                temperature = 0.0,
                prompt = "Lead analyst. Define KPIs and evaluate risks."
            ),
            Member(
                name = "Ирина",
                role = MemberRole.EMPLOYEE,
                position = "Junior аналитик. Уточняющие вопросы.",
                temperature = 0.0,
                prompt = "Junior analyst. Ask clarifying questions."
            ),
            Member(
                name = "Сергей",
                role = MemberRole.EMPLOYEE,
                position = "Middle data analyst. План сбора данных, дашборды.",
                temperature = 0.0,
                prompt = "Data collection plan and dashboards."
            )
        )
    )

    return listOf(development, design, analytics)
}

private object OpenRouterConfig {
    const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    val apiKey: String? get() = System.getenv("OPENROUTER_API_KEY")
    const val referer: String = "http://localhost"
    const val title: String = "AOSPBugreportAnalyzer"
}

private data class ORMessage(val role: String, val content: String)

private data class ORRequest(
    val model: String,
    val messages: List<ORMessage>,
    val response_format: Map<String, String>? = null,
    val temperature: Double
)

private val SYSTEM_TEXT = """
You are a concise teammate. Keep answers short, actionable, and plain text without extra formatting.
""".trimIndent()

private val SYSTEM_JSON = """
You are a concise teammate. Keep answers short and actionable.
Return ONLY valid JSON (UTF-8) with keys: version, ok, generated_at, items, error.
""".trimIndent()

data class AppSettings(
    val openRouterApiKey: String = System.getenv("OPENROUTER_API_KEY") ?: "",
    val openRouterModel: String = "openai/gpt-4o-mini",
    val strictJsonEnabled: Boolean = false
)

private fun selectSystemPrompt(forceJson: Boolean): String = if (forceJson) SYSTEM_JSON else SYSTEM_TEXT

private data class Price(val inPerM: Double, val outPerM: Double)

data class BenchModel(val alias: String, val id: String)

val benchModels = listOf(
    BenchModel("Mixtral 8x7B Instruct", "mistralai/mixtral-8x7b-instruct"),
    BenchModel("DeepSeek R1", "deepseek/deepseek-r1"),
    BenchModel("OpenAI GPT-OSS 120B", "openai/gpt-oss-120b"),
    BenchModel("Qwen2.5 7B Instruct", "qwen/qwen-2.5-7b-instruct"),
    BenchModel("Qwen2.5 72B Instruct", "qwen/qwen-2.5-72b-instruct"),
    BenchModel("Gemma 2 9B IT", "google/gemma-2-9b-it"),
    BenchModel("Llama 3.2 3B Instruct", "meta-llama/llama-3.2-3b-instruct"),
    BenchModel("Llama 3.1 8B Instruct", "meta-llama/llama-3.1-8b-instruct"),
    BenchModel("Sao10K L3 Stheno 8B", "sao10k/l3-stheno-8b"),
    BenchModel("MiniMax M2", "minimax/minimax-m2")
)

private val modelBenchPriceMap = mapOf(
    "mistralai/mixtral-8x7b-instruct" to Price(inPerM = 0.20, outPerM = 0.60),
    "deepseek/deepseek-r1" to Price(inPerM = 2.00, outPerM = 2.00),
    "openai/gpt-oss-120b" to Price(inPerM = 1.50, outPerM = 1.50),
    "qwen/qwen-2.5-7b-instruct" to Price(inPerM = 0.18, outPerM = 0.24),
    "qwen/qwen-2.5-72b-instruct" to Price(inPerM = 0.90, outPerM = 1.10),
    "google/gemma-2-9b-it" to Price(inPerM = 0.11, outPerM = 0.11),
    "meta-llama/llama-3.2-3b-instruct" to Price(inPerM = 0.04, outPerM = 0.08),
    "meta-llama/llama-3.1-8b-instruct" to Price(inPerM = 0.05, outPerM = 0.08),
    "sao10k/l3-stheno-8b" to Price(inPerM = 0.32, outPerM = 0.48),
    "minimax/minimax-m2" to Price(inPerM = 0.25, outPerM = 0.35)
)

data class BenchRunResult(
    val modelId: String,
    val ok: Boolean,
    val timeMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costUsd: Double?,
    val text: String?,
    val error: String?
)

private fun buildMessagesForMember(
    member: Member,
    team: Team,
    task: String,
    priorTeamMessages: List<ChatMessage>,
    systemPrompt: String
): List<ORMessage> {
    val sys = ORMessage("system", systemPrompt)
    val persona = ORMessage(
        "system",
        "Your name: ${member.name}. Role: ${member.role.displayName()} (${member.position}). Persona: ${member.prompt}. Team: ${team.title} (${team.direction.displayName()})."
    )
    val contextTail = priorTeamMessages.takeLast(3).map {
        val role = if (it.role == AuthorRole.TEAM_LEAD || it.role == AuthorRole.EMPLOYEE) "user" else "system"
        ORMessage(role, "[${it.author}] ${it.text}")
    }
    val taskMsg = ORMessage("user", "Task: $task")
    return listOf(sys, persona) + contextTail + taskMsg
}

private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .build()

private fun callOpenRouter(
    model: String,
    messages: List<ORMessage>,
    forceJson: Boolean,
    apiKeyOverride: String?,
    temperature: Double
): String {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return errorResponse(forceJson, "openrouter api key missing")
    }

    val request = ORRequest(
        model = model,
        messages = messages,
        response_format = if (forceJson) mapOf("type" to "json_object") else null,
        temperature = temperature.coerceIn(0.0, 2.0)
    )

    val body = buildString {
        append('{')
        append("\"model\":\"").append(encodeJsonString(request.model)).append('\"')
        append(",\"messages\":[")
        request.messages.forEachIndexed { index, msg ->
            if (index > 0) append(',')
            append('{')
            append("\"role\":\"").append(encodeJsonString(msg.role)).append('\"')
            append(",\"content\":\"").append(encodeJsonString(msg.content)).append('\"')
            append('}')
        }
        append(']')
        request.response_format?.let { format ->
            append(",\"response_format\":{\"type\":\"")
            append(encodeJsonString(format["type"] ?: "json_object"))
            append("\"}")
        }
        append(",\"temperature\":")
        append(String.format(Locale.US, "%.2f", request.temperature))
        append('}')
    }

    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(OpenRouterConfig.BASE_URL))
        .header("Authorization", "Bearer $key")
        .header("Content-Type", "application/json")
        .header("HTTP-Referer", OpenRouterConfig.referer)
        .header("X-Title", OpenRouterConfig.title)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    return try {
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            return errorResponse(forceJson, "openrouter http ${response.statusCode()}")
        }
        val content = extractContentFromOpenAIJson(response.body())
        content ?: errorResponse(forceJson, "openrouter empty content")
    } catch (t: Throwable) {
        errorResponse(forceJson, t.message ?: t::class.simpleName ?: "unknown error")
    }
}

private fun encodeJsonString(value: String): String {
    val sb = StringBuilder()
    value.forEach { c ->
        when (c) {
            '\\' -> sb.append("\\\\")
            '\"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun decodeJsonString(value: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (val next = value[i + 1]) {
                '\\', '\"', '/' -> {
                    sb.append(next)
                    i += 2
                }
                'b' -> {
                    sb.append('\b')
                    i += 2
                }
                'f' -> {
                    sb.append('\u000c')
                    i += 2
                }
                'n' -> {
                    sb.append('\n')
                    i += 2
                }
                'r' -> {
                    sb.append('\r')
                    i += 2
                }
                't' -> {
                    sb.append('\t')
                    i += 2
                }
                'u' -> {
                    if (i + 5 < value.length) {
                        val hex = value.substring(i + 2, i + 6)
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 6
                    } else {
                        i++
                    }
                }
                else -> {
                    sb.append(next)
                    i += 2
                }
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun extractContentFromOpenAIJson(body: String): String? {
    val regex = "\"content\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"".toRegex()
    val match = regex.find(body) ?: return null
    return decodeJsonString(match.groupValues[1]).ifBlank { null }
}

private data class BenchParsed(
    val content: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)

private val promptTokensRegex = "\"prompt_tokens\"\\s*:\\s*(\\d+)".toRegex()
private val completionTokensRegex = "\"completion_tokens\"\\s*:\\s*(\\d+)".toRegex()
private val totalTokensRegex = "\"total_tokens\"\\s*:\\s*(\\d+)".toRegex()

private fun parseBenchResponse(body: String): BenchParsed? {
    val content = extractContentFromOpenAIJson(body) ?: return null
    val prompt = promptTokensRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val completion = completionTokensRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val total = totalTokensRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: if (prompt != null && completion != null) prompt + completion else null
    return BenchParsed(content, prompt, completion, total)
}

private const val MODEL_BENCH_SYSTEM_PROMPT = "Be concise and clear."

private fun formatTokenTriple(prompt: Int?, completion: Int?, total: Int?): String {
    val p = prompt?.toString() ?: "-"
    val c = completion?.toString() ?: "-"
    val t = total?.toString() ?: "-"
    return "$p / $c / $t"
}

private fun formatCostUsd(cost: Double?): String {
    return cost?.let { "${'$'}" + String.format(Locale.US, "%.4f", it) } ?: "-"
}

private data class ModelBenchSummary(
    val speed: String,
    val quality: String,
    val cost: String
)

private fun buildModelBenchSummary(results: List<BenchRunResult>): ModelBenchSummary {
    val okResults = results.filter { it.ok }
    if (okResults.isEmpty()) {
        return ModelBenchSummary(
            speed = "Speed: no data.",
            quality = "Quality: no data.",
            cost = "Cost: no data."
        )
    }
    val mixtral = okResults.firstOrNull { it.modelId == "mistralai/mixtral-8x7b-instruct" }

    val fastest = okResults.filter { it.timeMs != null }.minByOrNull { it.timeMs!! }
    val cheapest = okResults.filter { it.costUsd != null }.minByOrNull { it.costUsd!! }
    val mostDetailed = okResults.maxByOrNull {
        val tokens = it.totalTokens ?: 0
        val textLength = it.text?.length ?: 0
        tokens * 10_000 + textLength
    }
    val mostExpensive = okResults.filter { it.costUsd != null }.maxByOrNull { it.costUsd!! }

    val speedLine = when {
        fastest == null -> "Speed: no timing data."
        fastest.modelId == "meta-llama/llama-3.1-8b-instruct" -> "Speed: Llama 3.1 8B responded the fastest."
        fastest.modelId == "mistralai/mixtral-8x7b-instruct" -> "Speed: Mixtral 8x7B delivered the quickest turnaround."
        fastest.modelId == "qwen/qwen-2.5-72b-instruct" -> "Speed: Qwen 72B led the pack on this run."
        else -> "Speed: mixed timing results."
    }

    val qualityLine = when (mostDetailed?.modelId) {
        "qwen/qwen-2.5-72b-instruct" -> "Quality: Qwen 72B produced the most detailed answer."
        "mistralai/mixtral-8x7b-instruct" -> "Quality: Mixtral 8x7B balanced detail and brevity."
        "meta-llama/llama-3.1-8b-instruct" -> "Quality: Llama 3.1 8B kept the answer concise."
        null -> "Quality: no clear winner."
        else -> "Quality: results were comparable."
    }

    var costLine = when {
        cheapest == null -> "Cost: no pricing data."
        cheapest.modelId == "meta-llama/llama-3.1-8b-instruct" && fastest?.modelId == "meta-llama/llama-3.1-8b-instruct" ->
            "Cost: Llama 3.1 8B — лучшее по скорости/цене."
        cheapest.modelId == "meta-llama/llama-3.1-8b-instruct" ->
            "Cost: Llama 3.1 8B was the most affordable run."
        cheapest.modelId == "mistralai/mixtral-8x7b-instruct" ->
            "Cost: Mixtral 8x7B sat in the mid-range for spend."
        cheapest.modelId == "qwen/qwen-2.5-72b-instruct" ->
            "Cost: Qwen 72B ended up cheapest on this prompt."
        else -> "Cost: pricing varied across models."
    }

    if (mostExpensive?.modelId == "qwen/qwen-2.5-72b-instruct" && cheapest?.modelId != "qwen/qwen-2.5-72b-instruct") {
        costLine += " Qwen 72B дороже и работает дольше на сложных задачах."
    } else if (mixtral != null && cheapest?.modelId != mixtral.modelId && mostDetailed?.modelId == mixtral.modelId) {
        costLine += " Mixtral 8x7B даёт баланс качества и скорости."
    }

    return ModelBenchSummary(speedLine, qualityLine, costLine)
}

private fun buildModelBenchMarkdown(prompt: String, results: List<BenchRunResult>): String {
    val summary = buildModelBenchSummary(results)
    val resultsBlock = buildString {
        results.forEach { result ->
            val alias = benchModels.firstOrNull { it.id == result.modelId }?.alias ?: result.modelId
            append("- ")
            append(alias)
            append(" [")
            append(result.modelId)
            append(']')
            append(" — time: ")
            append(result.timeMs?.toString() ?: "-")
            append(" ms, tokens: ")
            append(formatTokenTriple(result.promptTokens, result.completionTokens, result.totalTokens))
            append(", cost: ")
            append(formatCostUsd(result.costUsd))
            if (!result.ok) {
                append(", status: ")
                append(result.error ?: "⚠️ Unknown error")
            }
            append('\n')
        }
    }
    val linksBlock = buildString {
        results.forEach { result ->
            val alias = benchModels.firstOrNull { it.id == result.modelId }?.alias ?: result.modelId
            append("* ")
            append(alias)
            append(" [")
            append(result.modelId)
            append(']')
            append(": <TODO>\n")
        }
        if (results.isEmpty()) {
            append("* Model 1: <TODO>\n")
            append("* Model 2: <TODO>\n")
            append("* Model 3: <TODO>\n")
        }
    }
    return buildString {
        append("# Model Bench\n\n")
        append("Prompt:\n```\n")
        append(prompt)
        append("\n```\n\n")
        append("Results:\n")
        append(resultsBlock.ifBlank { "- No runs\n" })
        append('\n')
        append("Short takeaway:\n\n")
        append("* ").append(summary.speed).append('\n')
        append("* ").append(summary.quality).append('\n')
        append("* ").append(summary.cost).append('\n')
        append('\n')
        append("Links:\n\n")
        append(linksBlock.ifBlank {
            "* Model 1: <TODO>\n* Model 2: <TODO>\n* Model 3: <TODO>\n"
        })
    }
}

private fun buildModelBenchJson(prompt: String, results: List<BenchRunResult>): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"prompt\":\"").append(encodeJsonString(prompt)).append('\"')
    sb.append(",\"runs\":[")
    results.forEachIndexed { index, result ->
        if (index > 0) sb.append(',')
        sb.append('{')
        sb.append("\"model\":\"").append(encodeJsonString(result.modelId)).append('\"')
        sb.append(",\"alias\":\"")
        sb.append(encodeJsonString(benchModels.firstOrNull { it.id == result.modelId }?.alias ?: result.modelId))
        sb.append('\"')
        sb.append(",\"ok\":").append(if (result.ok) "true" else "false")
        sb.append(",\"ms\":").append(result.timeMs?.toString() ?: "null")
        sb.append(",\"usage\":{")
        sb.append("\"prompt\":").append(result.promptTokens?.toString() ?: "null")
        sb.append(",\"completion\":").append(result.completionTokens?.toString() ?: "null")
        sb.append(",\"total\":").append(result.totalTokens?.toString() ?: "null")
        sb.append('}')
        sb.append(",\"cost\":")
        sb.append(result.costUsd?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
        sb.append(",\"text\":")
        sb.append(result.text?.let { "\"${encodeJsonString(it)}\"" } ?: "null")
        sb.append(",\"error\":")
        sb.append(result.error?.let { "\"${encodeJsonString(it)}\"" } ?: "null")
        sb.append('}')
    }
    sb.append(']')
    sb.append('}')
    return sb.toString()
}

private fun buildModelBenchRequestBody(
    model: String,
    messages: List<ORMessage>,
    strictJson: Boolean
): String {
    return buildString {
        append('{')
        append("\"model\":\"").append(encodeJsonString(model)).append('\"')
        append(",\"messages\":[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) append(',')
            append('{')
            append("\"role\":\"").append(encodeJsonString(msg.role)).append('\"')
            append(",\"content\":\"").append(encodeJsonString(msg.content)).append('\"')
            append('}')
        }
        append(']')
        if (strictJson) {
            append(",\"response_format\":{\"type\":\"json_object\"}")
        }
        append('}')
    }
}

private suspend fun runModelBenchRequest(
    model: String,
    messages: List<ORMessage>,
    strictJson: Boolean,
    apiKey: String
): BenchRunResult {
    val requestBody = buildModelBenchRequestBody(model, messages, strictJson)
    var attempt = 0
    var lastError: String? = null
    while (attempt < 2) {
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(OpenRouterConfig.BASE_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", OpenRouterConfig.referer)
            .header("X-Title", OpenRouterConfig.title)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        val start = System.nanoTime()
        try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (response.statusCode() == 400) {
                return BenchRunResult(
                    modelId = model,
                    ok = false,
                    timeMs = elapsed,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    costUsd = null,
                    text = null,
                    error = "❌ Invalid model id"
                )
            }
            if (response.statusCode() / 100 != 2) {
                val snippet = response.body()
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.take(160)
                    ?.replace('\n', ' ')
                    ?.trim()
                    .orEmpty()
                val message = buildString {
                    append("⚠️ Provider error (")
                    append(response.statusCode())
                    if (snippet.isNotEmpty()) {
                        append(": ")
                        append(snippet)
                    }
                    append(')')
                }
                if (response.statusCode() >= 500 && attempt == 0) {
                    lastError = message
                    delay(600)
                    attempt++
                    continue
                }
                return BenchRunResult(
                    modelId = model,
                    ok = false,
                    timeMs = elapsed,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    costUsd = null,
                    text = null,
                    error = message
                )
            }
            val parsed = try {
                parseBenchResponse(response.body())
            } catch (_: Throwable) {
                null
            }
            if (parsed == null) {
                return BenchRunResult(
                    modelId = model,
                    ok = false,
                    timeMs = elapsed,
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    costUsd = null,
                    text = null,
                    error = "⚠️ Invalid response schema"
                )
            }
            val price = modelBenchPriceMap[model]
            val cost = if (price != null && parsed.promptTokens != null && parsed.completionTokens != null) {
                ((parsed.promptTokens * price.inPerM) + (parsed.completionTokens * price.outPerM)) / 1_000_000.0
            } else {
                null
            }
            return BenchRunResult(
                modelId = model,
                ok = true,
                timeMs = elapsed,
                promptTokens = parsed.promptTokens,
                completionTokens = parsed.completionTokens,
                totalTokens = parsed.totalTokens,
                costUsd = cost,
                text = parsed.content,
                error = null
            )
        } catch (timeout: java.net.http.HttpTimeoutException) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            return BenchRunResult(
                modelId = model,
                ok = false,
                timeMs = elapsed,
                promptTokens = null,
                completionTokens = null,
                totalTokens = null,
                costUsd = null,
                text = null,
                error = "⏱ Timeout"
            )
        } catch (t: Exception) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val message = "⚠️ Provider error (${t.message ?: t::class.simpleName ?: "unknown"})"
            if (attempt == 0) {
                lastError = message
                delay(600)
                attempt++
                continue
            }
            return BenchRunResult(
                modelId = model,
                ok = false,
                timeMs = elapsed,
                promptTokens = null,
                completionTokens = null,
                totalTokens = null,
                costUsd = null,
                text = null,
                error = message
            )
        }
    }
    return BenchRunResult(
        modelId = model,
        ok = false,
        timeMs = null,
        promptTokens = null,
        completionTokens = null,
        totalTokens = null,
        costUsd = null,
        text = null,
        error = lastError ?: "⚠️ Provider error"
    )
}

private fun jsonError(message: String): String {
    val escaped = encodeJsonString(message)
    return "{\"version\":\"1.0\",\"ok\":false,\"generated_at\":\"\",\"items\":[],\"error\":\"$escaped\"}"
}

private fun errorResponse(forceJson: Boolean, message: String): String =
    if (forceJson) jsonError(message) else "Error: $message"

private fun ConversationState.clone(activeRoomOverride: ChatRoomId = activeRoom): ConversationState {
    val mainCopy = main.toMutableList()
    val threadsCopy = mutableMapOf<String, MutableList<ChatMessage>>()
    for ((teamId, messages) in teamThreads) {
        threadsCopy[teamId] = messages.toMutableList()
    }
    return ConversationState(mainCopy, threadsCopy, activeRoomOverride)
}

private fun ConversationState.addMainMessage(message: ChatMessage): ConversationState {
    val copy = clone()
    copy.main.add(message)
    return copy
}

private fun ConversationState.addTeamMessage(teamId: String, message: ChatMessage): ConversationState {
    val copy = clone()
    val list = copy.teamThreads.getOrPut(teamId) { mutableListOf() }
    list.add(message)
    return copy
}

private fun ConversationState.withActiveRoom(room: ChatRoomId): ConversationState = clone(room)

private fun ConversationState.syncWithTeams(teams: List<Team>): ConversationState {
    val teamIds = teams.map { it.id }.toSet()
    val active = when (val room = activeRoom) {
        is ChatRoomId.TeamRoom -> if (room.teamId in teamIds) room else ChatRoomId.Main
        ChatRoomId.Main -> room
    }
    val copy = clone(active)
    for (teamId in teamIds) {
        if (!copy.teamThreads.containsKey(teamId)) {
            copy.teamThreads[teamId] = mutableListOf()
        }
    }
    val iterator = copy.teamThreads.keys.iterator()
    while (iterator.hasNext()) {
        val id = iterator.next()
        if (id !in teamIds) {
            iterator.remove()
        }
    }
    return copy
}

private class TeamLLMOrchestrator(
    private val model: String,
    private val forceJson: Boolean,
    private val getTeams: () -> List<Team>,
    private val getState: () -> ConversationState,
    private val setState: (ConversationState) -> Unit,
    private val apiKeyProvider: () -> String?
) {
    private val maxTeamMsgs = 10
    private val maxMainDebateMsgs = 10
    private val mutex = Mutex()
    private val systemPrompt = selectSystemPrompt(forceJson)

    suspend fun runTeamRound(teamId: String, task: String) {
        val team = getTeams().firstOrNull { it.id == teamId } ?: return
        val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: return
        val employees = team.members.filter { it.role == MemberRole.EMPLOYEE }

        suspend fun addFrom(member: Member) {
            val context = mutex.withLock {
                val thread = getState().teamThreads[teamId]
                if (thread != null && thread.size >= maxTeamMsgs) return
                thread?.toList().orEmpty()
            }
            val msgs = buildMessagesForMember(member, team, task, context, systemPrompt)
            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), member.temperature)
            val newState = mutex.withLock {
                val snapshot = getState()
                val thread = snapshot.teamThreads[teamId]
                if (thread != null && thread.size >= maxTeamMsgs) return@withLock null
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.TeamRoom(teamId),
                    author = member.name,
                    role = if (member.role == MemberRole.TEAM_LEAD) AuthorRole.TEAM_LEAD else AuthorRole.EMPLOYEE,
                    text = content,
                    timestamp = System.currentTimeMillis()
                )
                snapshot.addTeamMessage(teamId, message)
            }
            if (newState != null) {
                withContext(Dispatchers.Main) {
                    setState(newState)
                }
            }
        }

        addFrom(lead)
        for (employee in employees) {
            if (reachedTeamLimit(teamId)) break
            addFrom(employee)
        }
        if (!reachedTeamLimit(teamId)) {
            addFrom(lead)
        }
    }

    suspend fun postTeamSummariesToMain(task: String) {
        for (team in getTeams()) {
            val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: continue
            val msgs = listOf(
                ORMessage("system", systemPrompt),
                ORMessage(
                    "system",
                    "You are ${lead.name}, ${lead.position}. Team: ${team.title} (${team.direction.displayName()})."
                ),
                ORMessage("user", "Summarize your team's solution for the main room in 2–3 sentences. Task: $task")
            )
            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), lead.temperature)
            val newState = mutex.withLock {
                val snapshot = getState()
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = lead.name,
                    role = AuthorRole.TEAM_LEAD,
                    text = content,
                    timestamp = System.currentTimeMillis()
                )
                snapshot.addMainMessage(message)
            }
            withContext(Dispatchers.Main) {
                setState(newState)
            }
        }
    }

    suspend fun runLeadsDebate(task: String) {
        val teamsSnapshot = getTeams()
        suspend fun leaderReply(team: Team, cue: String) {
            val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: return
            val context = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == AuthorRole.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return
                snapshot.main.takeLast(4)
            }
            val msgs = mutableListOf(
                ORMessage("system", systemPrompt),
                ORMessage(
                    "system",
                    "You are ${lead.name}, ${lead.position}. Debate as ${team.direction.displayName()} lead."
                )
            )
            msgs += context.map { ORMessage("user", "[${it.author}] ${it.text}") }
            msgs += ORMessage("user", "Task: $task. Respond: $cue")

            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), lead.temperature)
            val newState = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == AuthorRole.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return@withLock null
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = lead.name,
                    role = AuthorRole.TEAM_LEAD,
                    text = content,
                    timestamp = System.currentTimeMillis()
                )
                snapshot.addMainMessage(message)
            }
            if (newState != null) {
                withContext(Dispatchers.Main) {
                    setState(newState)
                }
            }
        }

        val cues = mapOf(
            TeamDirection.DEVELOPMENT to "Argue for the technical solution briefly.",
            TeamDirection.DESIGN to "Highlight design constraints and UX considerations briefly.",
            TeamDirection.ANALYTICS to "Add analytics KPIs and risk checks briefly."
        )
        for (team in teamsSnapshot) {
            if (reachedMainLimit()) break
            val cue = cues[team.direction] ?: "Add your team's perspective briefly."
            leaderReply(team, cue)
        }
    }

    suspend fun sendLeadMessage(teamId: String, task: String) {
        val team = getTeams().firstOrNull { it.id == teamId } ?: return
        val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: return
        val context = mutex.withLock { getState().teamThreads[teamId]?.toList().orEmpty() }
        val msgs = buildMessagesForMember(lead, team, task, context, systemPrompt)
        val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), lead.temperature)
        val newState = mutex.withLock {
            val snapshot = getState()
            val thread = snapshot.teamThreads[teamId]
            if (thread != null && thread.size >= maxTeamMsgs) return@withLock null
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                room = ChatRoomId.TeamRoom(teamId),
                author = lead.name,
                role = AuthorRole.TEAM_LEAD,
                text = content,
                timestamp = System.currentTimeMillis()
            )
            snapshot.addTeamMessage(teamId, message)
        }
        if (newState != null) {
            withContext(Dispatchers.Main) {
                setState(newState)
            }
        }
    }

    fun reachedTeamLimit(teamId: String): Boolean =
        getState().teamThreads[teamId]?.size ?: 0 >= maxTeamMsgs

    private fun reachedMainLimit(): Boolean =
        getState().main.count { it.role == AuthorRole.TEAM_LEAD } >= maxMainDebateMsgs
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(timestamp: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(timestamp))

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Команды") {
        MaterialTheme {
            DesktopChatApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopChatApp() {
    val scope = rememberCoroutineScope()
    val initialTeams = remember { defaultTeams() }
    var teams by remember { mutableStateOf(initialTeams) }
    var state by remember { mutableStateOf(ConversationState().syncWithTeams(initialTeams)) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var taskInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var lastTask by remember { mutableStateOf("") }
    var selectedTeamId by remember { mutableStateOf(initialTeams.firstOrNull()?.id) }
    var memberEditorTeamId by remember { mutableStateOf<String?>(null) }
    var editingMember by remember { mutableStateOf<Member?>(null) }
    var deleteTeamId by remember { mutableStateOf<String?>(null) }
    var teamsUiMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val apiKeyState by rememberUpdatedState(settings.openRouterApiKey)

    fun setTeamsMessage(text: String, isError: Boolean) {
        teamsUiMessage = text to isError
    }

    fun updateTeams(transform: (List<Team>) -> List<Team>) {
        val updated = transform(teams)
        val sorted = updated
            .map { it.deepCopy() }
            .sortedWith(compareBy<Team>({ it.direction.ordinal }, { it.title }))
        teams = sorted
        state = state.syncWithTeams(sorted)
        if (selectedTeamId != null && sorted.none { it.id == selectedTeamId }) {
            selectedTeamId = sorted.firstOrNull()?.id
        }
    }

    val orchestrator = remember(settings.openRouterModel, settings.strictJsonEnabled) {
        TeamLLMOrchestrator(
            model = settings.openRouterModel,
            forceJson = settings.strictJsonEnabled,
            getTeams = { teams },
            getState = { state },
            setState = { newState -> state = newState },
            apiKeyProvider = { apiKeyState.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey }
        )
    }

    fun appendUserMessage(text: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            room = ChatRoomId.Main,
            author = "USER",
            role = AuthorRole.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        state = state.addMainMessage(message).withActiveRoom(ChatRoomId.Main)
    }

    fun handleSaveMember(teamId: String, updated: Member, previous: Member?): Boolean {
        val team = teams.firstOrNull { it.id == teamId } ?: return false
        val members = team.members.toMutableList()
        val existingIndex = members.indexOfFirst { it.id == updated.id }
        if (previous != null && previous.role == MemberRole.TEAM_LEAD && updated.role != MemberRole.TEAM_LEAD) {
            if (members.count { it.id != updated.id && it.role == MemberRole.TEAM_LEAD } < 1) {
                setTeamsMessage("В команде должен оставаться тимлид.", true)
                return false
            }
        }
        if (existingIndex >= 0) {
            members[existingIndex] = updated
        } else {
            members.add(updated)
        }
        if (updated.role == MemberRole.TEAM_LEAD) {
            for (i in members.indices) {
                val member = members[i]
                if (member.id != updated.id && member.role == MemberRole.TEAM_LEAD) {
                    members[i] = member.copy(role = MemberRole.EMPLOYEE)
                }
            }
        }
        if (members.count { it.role == MemberRole.TEAM_LEAD } != 1) {
            setTeamsMessage("В команде должен быть ровно один тимлид.", true)
            return false
        }
        val newTeam = team.copy(members = members.map { it.copy() }.toMutableList())
        updateTeams { current -> current.map { if (it.id == teamId) newTeam else it } }
        setTeamsMessage("Сотрудник сохранён", false)
        return true
    }

    fun handleDeleteMember(teamId: String, memberId: String): Boolean {
        val team = teams.firstOrNull { it.id == teamId } ?: return false
        val members = team.members.toMutableList()
        val index = members.indexOfFirst { it.id == memberId }
        if (index < 0) return false
        if (members[index].role == MemberRole.TEAM_LEAD) {
            setTeamsMessage("Нельзя удалить единственного тимлида.", true)
            return false
        }
        members.removeAt(index)
        if (members.count { it.role == MemberRole.TEAM_LEAD } != 1) {
            setTeamsMessage("В команде должен оставаться тимлид.", true)
            return false
        }
        val newTeam = team.copy(members = members.map { it.copy() }.toMutableList())
        updateTeams { current -> current.map { if (it.id == teamId) newTeam else it } }
        setTeamsMessage("Сотрудник удалён", false)
        return true
    }

    fun createTeam() {
        val newLead = Member(
            name = "Новый тимлид",
            role = MemberRole.TEAM_LEAD,
            position = "Опишите роль тимлида",
            temperature = 0.0,
            prompt = "Describe leadership style and responsibilities."
        )
        val newTeam = Team(
            title = "Новая команда",
            direction = TeamDirection.DEVELOPMENT,
            members = mutableListOf(newLead)
        )
        updateTeams { it + newTeam }
        selectedTeamId = newTeam.id
        setTeamsMessage("Команда создана", false)
    }

    fun deleteTeam(teamId: String) {
        updateTeams { current -> current.filterNot { it.id == teamId } }
        setTeamsMessage("Команда удалена", false)
    }

    fun sendTask() {
        val task = taskInput.trim()
        if (task.isEmpty() || isProcessing) return
        val resolvedKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (resolvedKey.isNullOrBlank()) {
            appendUserMessage("OPENROUTER_API_KEY is not set")
            taskInput = ""
            return
        }
        appendUserMessage(task)
        taskInput = ""
        lastTask = task
        isProcessing = true
        val teamIds = teams.map { it.id }
        val jobs = teamIds.map { teamId ->
            scope.launch(Dispatchers.IO) {
                orchestrator.runTeamRound(teamId, task)
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                jobs.joinAll()
                orchestrator.postTeamSummariesToMain(task)
                orchestrator.runLeadsDebate(task)
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if ((settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey).isNullOrBlank()) {
            appendUserMessage("Задайте OPENROUTER_API_KEY, чтобы команды могли отвечать.")
        }
    }

    LaunchedEffect(teamsUiMessage) {
        if (teamsUiMessage != null) {
            delay(2500)
            teamsUiMessage = null
        }
    }

    when (screen) {
        Screen.MAIN -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Командный чат — OpenRouter") },
                        actions = {
                            TextButton(onClick = { screen = Screen.MODEL_BENCH }) {
                                Text("Model Bench")
                            }
                            TextButton(onClick = { screen = Screen.TEAMS }) {
                                Text("Команды")
                            }
                            TextButton(onClick = { screen = Screen.SETTINGS }) {
                                Text("Настройки")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RoomNavigation(
                        teams = teams,
                        activeRoom = state.activeRoom,
                        onSelect = { room -> state = state.withActiveRoom(room) }
                    )

                    val messages = when (val room = state.activeRoom) {
                        ChatRoomId.Main -> state.main.toList()
                        is ChatRoomId.TeamRoom -> state.teamThreads[room.teamId]?.toList().orEmpty()
                    }

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (state.activeRoom is ChatRoomId.TeamRoom) {
                            val teamId = (state.activeRoom as ChatRoomId.TeamRoom).teamId
                            val team = teams.firstOrNull { it.id == teamId }
                            if (team == null || team.members.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Нет сотрудников")
                                }
                            } else {
                                MessageList(messages)
                            }
                        } else {
                            MessageList(messages)
                        }
                        if (isProcessing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    when (val room = state.activeRoom) {
                        ChatRoomId.Main -> {
                            OutlinedTextField(
                                value = taskInput,
                                onValueChange = { taskInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Задача для команд") }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { sendTask() },
                                    enabled = taskInput.isNotBlank() && !isProcessing
                                ) {
                                    Text("Отправить в команды")
                                }
                                if (lastTask.isNotBlank()) {
                                    Text("Текущая задача: $lastTask")
                                }
                            }
                        }
                        is ChatRoomId.TeamRoom -> {
                            val team = teams.firstOrNull { it.id == room.teamId }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Внутренний чат команды \"${team?.title ?: ""}\"")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val count = state.teamThreads[room.teamId]?.size ?: 0
                                    Text("Лимит: $count / 10")
                                    TextButton(
                                        onClick = {
                                            if (lastTask.isNotBlank() && !isProcessing && team != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    orchestrator.sendLeadMessage(team.id, lastTask)
                                                }
                                            }
                                        },
                                        enabled = lastTask.isNotBlank() && !isProcessing && team != null && !orchestrator.reachedTeamLimit(team.id)
                                    ) {
                                        Text("Отправить (тимлид)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Screen.MODEL_BENCH -> {
            ModelBenchScreen(
                userApiKey = settings.openRouterApiKey,
                onBack = { screen = Screen.MAIN }
            )
        }
        Screen.SETTINGS -> {
            SettingsScreen(
                settings = settings,
                onChange = { settings = it },
                onClose = { screen = Screen.MAIN }
            )
        }
        Screen.TEAMS -> {
            TeamsScreen(
                teams = teams,
                selectedTeamId = selectedTeamId,
                message = teamsUiMessage,
                onSelectTeam = { selectedTeamId = it },
                onCreateTeam = { createTeam() },
                onDeleteTeam = { teamId -> deleteTeamId = teamId },
                onUpdateTeamTitle = { teamId, title ->
                    updateTeams { list ->
                        list.map { team -> if (team.id == teamId) team.copy(title = title) else team }
                    }
                },
                onUpdateTeamDirection = { teamId, direction ->
                    updateTeams { list ->
                        list.map { team -> if (team.id == teamId) team.copy(direction = direction) else team }
                    }
                },
                onAddMember = { teamId ->
                    memberEditorTeamId = teamId
                    editingMember = null
                },
                onEditMember = { teamId, member ->
                    memberEditorTeamId = teamId
                    editingMember = member
                },
                onRemoveMember = { teamId, member ->
                    handleDeleteMember(teamId, member.id)
                },
                onBack = { screen = Screen.MAIN }
            )
        }
    }

    if (memberEditorTeamId != null) {
        val teamId = memberEditorTeamId
        MemberEditorDialog(
            member = editingMember,
            onDismiss = {
                memberEditorTeamId = null
                editingMember = null
            },
            onSave = { member ->
                if (teamId != null) {
                    val success = handleSaveMember(teamId, member, editingMember)
                    if (success) {
                        memberEditorTeamId = null
                        editingMember = null
                    }
                }
            },
            onDelete = if (editingMember != null) {
                {
                    if (teamId != null) {
                        val success = handleDeleteMember(teamId, editingMember!!.id)
                        if (success) {
                            memberEditorTeamId = null
                            editingMember = null
                        }
                    }
                }
            } else null
        )
    }

    if (deleteTeamId != null) {
        val team = teams.firstOrNull { it.id == deleteTeamId }
        if (team != null) {
            AlertDialog(
                onDismissRequest = { deleteTeamId = null },
                title = { Text("Удалить команду") },
                text = { Text("Вы уверены, что хотите удалить команду \"${team.title}\"?") },
                confirmButton = {
                    Button(onClick = {
                        deleteTeam(team.id)
                        deleteTeamId = null
                    }) {
                        Text("Удалить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTeamId = null }) {
                        Text("Отмена")
                    }
                }
            )
        } else {
            deleteTeamId = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelBenchScreen(
    userApiKey: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    val availableModels = remember { benchModels }
    val modelSelections = remember {
        mutableStateMapOf<String, Boolean>().apply {
            availableModels.forEach { put(it.id, true) }
        }
    }

    var prompt by remember { mutableStateOf("") }
    var strictJson by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BenchRunResult>>(emptyList()) }
    var runError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val resolvedKey = userApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Bench") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Назад") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                label = { Text("Prompt") },
                singleLine = false,
                maxLines = 12
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Models")
                availableModels.forEach { model ->
                    val checked = modelSelections[model.id] == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { checkedValue -> modelSelections[model.id] = checkedValue },
                            enabled = !isRunning
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(model.alias)
                            Text(
                                model.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(checked = strictJson, onCheckedChange = { strictJson = it })
                Text("Strict JSON (response_format)")
            }

            val selectedModels = availableModels.filter { modelSelections[it.id] == true }
            val runEnabled = !isRunning && prompt.isNotBlank() && selectedModels.isNotEmpty()
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            runError = null
                            statusMessage = null
                            val trimmed = prompt.trim()
                            if (trimmed.isEmpty()) {
                                runError = "Введите prompt."
                                return@launch
                            }
                            if (selectedModels.isEmpty()) {
                                runError = "Выберите хотя бы одну модель."
                                return@launch
                            }
                            val key = resolvedKey
                            if (key.isNullOrBlank()) {
                                runError = "OPENROUTER_API_KEY is not set."
                                return@launch
                            }
                            isRunning = true
                            results = emptyList()
                            try {
                                val messages = listOf(
                                    ORMessage("system", MODEL_BENCH_SYSTEM_PROMPT),
                                    ORMessage("user", trimmed)
                                )
                                val responses = selectedModels.map { model ->
                                    async(Dispatchers.IO) {
                                        runModelBenchRequest(model.id, messages, strictJson, key)
                                    }
                                }.awaitAll()
                                results = responses
                                val okCount = responses.count { it.ok }
                                statusMessage = "Готово: ${okCount} успешных из ${responses.size}."
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    enabled = runEnabled
                ) {
                    Text(if (isRunning) "Running…" else "Run")
                }
                Button(
                    onClick = {
                        val currentResults = results
                        if (currentResults.isEmpty()) return@Button
                        val currentPrompt = prompt
                        scope.launch {
                            runError = null
                            statusMessage = null
                            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.now())
                            val baseDir = Paths.get("model_bench")
                            try {
                                withContext(Dispatchers.IO) {
                                    Files.createDirectories(baseDir)
                                    val markdown = buildModelBenchMarkdown(currentPrompt, currentResults)
                                    val json = buildModelBenchJson(currentPrompt, currentResults)
                                    Files.writeString(
                                        baseDir.resolve("results-$timestamp.md"),
                                        markdown,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE
                                    )
                                    Files.writeString(
                                        baseDir.resolve("results-$timestamp.json"),
                                        json,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE
                                    )
                                }
                                statusMessage = "Exported to model_bench/results-$timestamp.*"
                            } catch (t: Throwable) {
                                runError = "Export failed: ${t.message ?: t::class.simpleName}".trim()
                            }
                        }
                    },
                    enabled = results.isNotEmpty()
                ) {
                    Text("Export")
                }
                TextButton(
                    onClick = {
                        val currentResults = results
                        if (currentResults.isEmpty()) return@TextButton
                        runError = null
                        val successful = currentResults.filter { it.ok && !it.text.isNullOrBlank() }
                        if (successful.isEmpty()) {
                            statusMessage = "Нет успешных ответов для копирования."
                            return@TextButton
                        }
                        val buffer = buildString {
                            successful.forEach { result ->
                                val alias = benchModels.firstOrNull { it.id == result.modelId }?.alias ?: result.modelId
                                append("=== ")
                                append(alias)
                                append(" [")
                                append(result.modelId)
                                append("] (time ")
                                append(result.timeMs?.toString() ?: "-")
                                append(" ms, ")
                                append(formatTokenTriple(result.promptTokens, result.completionTokens, result.totalTokens))
                                append(", ")
                                append(formatCostUsd(result.costUsd))
                                append(") ===\n")
                                append(result.text)
                                append("\n\n")
                            }
                        }.trimEnd()
                        clipboardManager.setText(AnnotatedString(buffer))
                        statusMessage = "Outputs copied (${successful.size})."
                    },
                    enabled = results.any { it.ok && !it.text.isNullOrBlank() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy"
                        )
                        Text(" Copy all outputs", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            if (resolvedKey.isNullOrBlank()) {
                Text(
                    "OPENROUTER_API_KEY не задан. Укажите ключ в настройках.",
                    color = MaterialTheme.colorScheme.error
                )
            }

            runError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            if (results.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Модель", modifier = Modifier.weight(2.5f))
                        Text("Время, ms", modifier = Modifier.weight(1f))
                        Text("Tokens P/C/T", modifier = Modifier.weight(1.4f))
                        Text("Стоимость", modifier = Modifier.weight(1.2f))
                        Text("Статус", modifier = Modifier.weight(1.2f))
                        Text("Ответ", modifier = Modifier.weight(0.8f))
                    }
                    Divider()
                    results.forEach { result ->
                        key(result.modelId, result.timeMs, result.text, result.error) {
                            var expanded by remember { mutableStateOf(false) }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val alias = benchModels.firstOrNull { it.id == result.modelId }?.alias
                                        ?: result.modelId
                                    Column(modifier = Modifier.weight(2.5f)) {
                                        Text(alias)
                                        Text(
                                            result.modelId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(result.timeMs?.toString() ?: "-", modifier = Modifier.weight(1f))
                                    Text(
                                        formatTokenTriple(result.promptTokens, result.completionTokens, result.totalTokens),
                                        modifier = Modifier.weight(1.4f)
                                    )
                                    Text(
                                        formatCostUsd(result.costUsd),
                                        modifier = Modifier.weight(1.2f)
                                    )
                                    val statusText = if (result.ok) "✅ OK" else result.error ?: "⚠️ Unknown"
                                    Text(statusText, modifier = Modifier.weight(1.2f))
                                    TextButton(
                                        onClick = { expanded = !expanded },
                                        enabled = result.ok && !result.text.isNullOrBlank(),
                                        modifier = Modifier.weight(0.8f)
                                    ) {
                                        Text(if (expanded) "Скрыть" else "Показать")
                                    }
                                }
                                if (expanded && result.ok && !result.text.isNullOrBlank()) {
                                    Text(
                                        result.text,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 12.dp)
                                    )
                                }
                                Divider()
                            }
                        }
                    }
                }
            } else {
                Text("Нет результатов. Запустите сравнение.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onClose: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Настройки OpenRouter") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = draft.openRouterApiKey,
                onValueChange = { draft = draft.copy(openRouterApiKey = it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showApiKey) "Скрыть ключ" else "Показать ключ"
                        )
                    }
                }
            )
            OutlinedTextField(
                value = draft.openRouterModel,
                onValueChange = { draft = draft.copy(openRouterModel = it) },
                label = { Text("Модель") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = draft.strictJsonEnabled,
                    onCheckedChange = { draft = draft.copy(strictJsonEnabled = it) }
                )
                Text("Строгий JSON (response_format)")
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    onChange(draft)
                    onClose()
                }) {
                    Text("Сохранить")
                }
                TextButton(onClick = onClose) {
                    Text("Назад")
                }
            }
        }
    }
}

@Composable
private fun RoomNavigation(
    teams: List<Team>,
    activeRoom: ChatRoomId,
    onSelect: (ChatRoomId) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onSelect(ChatRoomId.Main) },
            enabled = activeRoom !is ChatRoomId.Main
        ) { Text("Главный чат") }
        teams.forEach { team ->
            val room = ChatRoomId.TeamRoom(team.id)
            Button(
                onClick = { onSelect(room) },
                enabled = activeRoom != room
            ) { Text(team.title) }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Сообщений пока нет")
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var lastCopiedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastCopiedId) {
        if (lastCopiedId != null) {
            delay(2000)
            lastCopiedId = null
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${formatTimestamp(message.timestamp)} · ${message.author} (${message.role.displayName()})",
                    style = MaterialTheme.typography.labelSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        lastCopiedId = message.id
                    }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Скопировать")
                    }
                }
                if (lastCopiedId == message.id) {
                    Text(
                        text = "Скопировано",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamsScreen(
    teams: List<Team>,
    selectedTeamId: String?,
    message: Pair<String, Boolean>?,
    onSelectTeam: (String?) -> Unit,
    onCreateTeam: () -> Unit,
    onDeleteTeam: (String) -> Unit,
    onUpdateTeamTitle: (String, String) -> Unit,
    onUpdateTeamDirection: (String, TeamDirection) -> Unit,
    onAddMember: (String) -> Unit,
    onEditMember: (String, Member) -> Unit,
    onRemoveMember: (String, Member) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Управление командами") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Назад")
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onCreateTeam) { Text("Создать команду") }
                    TextButton(
                        onClick = {
                            selectedTeamId?.let(onDeleteTeam)
                        },
                        enabled = selectedTeamId != null
                    ) {
                        Text("Удалить команду")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(teams, key = { it.id }) { team ->
                        val isSelected = team.id == selectedTeamId
                        Button(
                            onClick = { onSelectTeam(team.id) },
                            enabled = !isSelected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(team.title)
                                Text(
                                    text = team.direction.displayName(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            val team = teams.firstOrNull { it.id == selectedTeamId }
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (message != null) {
                    val color = if (message.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Text(message.first, color = color)
                }
                if (team == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Выберите команду слева")
                    }
                } else {
                    OutlinedTextField(
                        value = team.title,
                        onValueChange = { onUpdateTeamTitle(team.id, it) },
                        label = { Text("Название команды") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    var directionExpanded by remember(team.id) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = directionExpanded,
                        onExpandedChange = { directionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = team.direction.displayName(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Направление") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = directionExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = directionExpanded,
                            onDismissRequest = { directionExpanded = false }
                        ) {
                            TeamDirection.values().forEach { direction ->
                                DropdownMenuItem(
                                    text = { Text(direction.displayName()) },
                                    onClick = {
                                        onUpdateTeamDirection(team.id, direction)
                                        directionExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    val leadCount = team.members.count { it.role == MemberRole.TEAM_LEAD }
                    if (leadCount != 1) {
                        Text(
                            text = "Внимание: в команде должен быть ровно один тимлид.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Имя", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Text("Роль", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
                            Text("Должность", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
                            Text("T", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                            Text("Prompt", modifier = Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.width(96.dp))
                        }
                        team.members.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(member.name, modifier = Modifier.weight(1f))
                                Text(member.role.displayName(), modifier = Modifier.weight(0.6f))
                                Text(member.position, modifier = Modifier.weight(1.2f))
                                Text(
                                    text = String.format(Locale.US, "%.1f", member.temperature),
                                    modifier = Modifier.width(48.dp)
                                )
                                Text(member.prompt, modifier = Modifier.weight(1.6f))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onEditMember(team.id, member) }) {
                                        Text("Ред.")
                                    }
                                    TextButton(onClick = { onRemoveMember(team.id, member) }) {
                                        Text("Удалить")
                                    }
                                }
                            }
                        }
                        if (team.members.isEmpty()) {
                            Text("В команде пока нет сотрудников")
                        }
                        Button(onClick = { onAddMember(team.id) }) {
                            Text("Добавить сотрудника")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberEditorDialog(
    member: Member?,
    onDismiss: () -> Unit,
    onSave: (Member) -> Unit,
    onDelete: (() -> Unit)?
) {
    val isEditing = member != null
    var name by remember(member) { mutableStateOf(member?.name ?: "") }
    var role by remember(member) { mutableStateOf(member?.role ?: MemberRole.EMPLOYEE) }
    var position by remember(member) { mutableStateOf(member?.position ?: "") }
    var prompt by remember(member) { mutableStateOf(member?.prompt ?: "") }
    var temperature by remember(member) { mutableStateOf(member?.temperature ?: 0.0) }
    var temperatureText by remember(member) { mutableStateOf(String.format(Locale.US, "%.1f", temperature)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Редактирование сотрудника" else "Новый сотрудник") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemberRole.values().forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            RadioButton(
                                selected = role == option,
                                onClick = { role = option }
                            )
                            Text(option.displayName())
                        }
                    }
                }
                OutlinedTextField(
                    value = position,
                    onValueChange = { position = it },
                    label = { Text("Должность") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = {
                        temperatureText = it
                        it.toDoubleOrNull()?.let { value ->
                            val sanitized = value.coerceIn(0.0, 2.0)
                            temperature = kotlin.math.round(sanitized * 10) / 10.0
                        }
                    },
                    label = { Text("Температура (0.0–2.0)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Slider(
                    value = temperature.toFloat(),
                    onValueChange = { value ->
                        val snapped = (kotlin.math.round(value * 10f) / 10f).coerceIn(0f, 2f)
                        temperature = snapped.toDouble()
                        temperatureText = String.format(Locale.US, "%.1f", temperature)
                    },
                    valueRange = 0f..2f,
                    steps = 19
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val trimmedName = name.trim()
                if (trimmedName.isEmpty()) {
                    error = "Имя не может быть пустым"
                    return@Button
                }
                val parsedTemperature = temperatureText.toDoubleOrNull()?.coerceIn(0.0, 2.0)
                    ?: temperature.coerceIn(0.0, 2.0)
                val finalMember = if (member != null) {
                    member.copy(
                        name = trimmedName,
                        role = role,
                        position = position.trim(),
                        temperature = kotlin.math.round(parsedTemperature * 10) / 10.0,
                        prompt = prompt.trim()
                    )
                } else {
                    Member(
                        name = trimmedName,
                        role = role,
                        position = position.trim(),
                        temperature = kotlin.math.round(parsedTemperature * 10) / 10.0,
                        prompt = prompt.trim()
                    )
                }
                onSave(finalMember)
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
                if (isEditing && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    )
}

