package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private enum class Screen { MAIN, SETTINGS, MODEL_BENCH }

private enum class AuthorRole { USER, AGENT }

private fun AuthorRole.displayName(): String = when (this) {
    AuthorRole.USER -> "Пользователь"
    AuthorRole.AGENT -> "Агент"
}

private data class MsgMetrics(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val elapsedMs: Long?,
    val costUsd: Double?
)

private data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val role: AuthorRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metrics: MsgMetrics? = null
)

private data class CallStats(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val elapsedMs: Long?,
    val costUsd: Double?
)

private data class CallResult(
    val content: String,
    val stats: CallStats,
    val success: Boolean
)

private fun CallStats.toMsgMetrics(): MsgMetrics =
    MsgMetrics(promptTokens, completionTokens, totalTokens, elapsedMs, costUsd)

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

private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .build()

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

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(timestamp: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(timestamp))

private fun callOpenRouter(
    model: String,
    messages: List<ORMessage>,
    forceJson: Boolean,
    apiKeyOverride: String?,
    temperature: Double
): CallResult {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return CallResult(
            content = errorResponse(forceJson, "openrouter api key missing"),
            stats = CallStats(null, null, null, null, null),
            success = false
        )
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

    val start = System.nanoTime()
    return try {
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        val elapsed = (System.nanoTime() - start) / 1_000_000
        if (response.statusCode() / 100 != 2) {
            return CallResult(
                content = errorResponse(forceJson, "openrouter http ${'$'}{response.statusCode()}"),
                stats = CallStats(null, null, null, elapsed, null),
                success = false
            )
        }
        val content = extractContentFromOpenAIJson(response.body())
            ?: return CallResult(
                content = errorResponse(forceJson, "openrouter empty content"),
                stats = CallStats(null, null, null, elapsed, null),
                success = false
            )
        val prompt = promptTokensRegex.find(response.body())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val completion = completionTokensRegex.find(response.body())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val total = totalTokensRegex.find(response.body())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (prompt != null && completion != null) prompt + completion else null
        val price = modelBenchPriceMap[model]
        val cost = if (price != null && prompt != null && completion != null) {
            ((prompt * price.inPerM) + (completion * price.outPerM)) / 1_000_000.0
        } else {
            null
        }
        CallResult(
            content = content,
            stats = CallStats(prompt, completion, total, elapsed, cost),
            success = true
        )
    } catch (t: Throwable) {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        CallResult(
            content = errorResponse(forceJson, t.message ?: t::class.simpleName ?: "unknown error"),
            stats = CallStats(null, null, null, elapsed, null),
            success = false
        )
    }
}

private fun buildChatRequestMessages(
    history: List<ChatMessage>,
    forceJson: Boolean
): List<ORMessage> {
    val messages = mutableListOf(ORMessage("system", selectSystemPrompt(forceJson)))
    history.forEach { message ->
        val role = if (message.role == AuthorRole.USER) "user" else "assistant"
        messages += ORMessage(role, message.text)
    }
    return messages
}

@Composable
private fun DesktopChatApp() {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }

    fun appendMessage(message: ChatMessage) {
        messages = messages + message
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || isSending) return
        val userMessage = ChatMessage(
            author = "USER",
            role = AuthorRole.USER,
            text = text,
            metrics = MsgMetrics(null, null, null, null, null)
        )
        val newHistory = messages + userMessage
        messages = newHistory
        input = ""
        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            appendMessage(
                ChatMessage(
                    author = "Agent",
                    role = AuthorRole.AGENT,
                    text = "OPENROUTER_API_KEY is not set",
                    metrics = MsgMetrics(null, null, null, null, null)
                )
            )
            return
        }
        isSending = true
        val model = settings.openRouterModel
        val strictJson = settings.strictJsonEnabled
        scope.launch(Dispatchers.IO) {
            val requestMessages = buildChatRequestMessages(newHistory, strictJson)
            val result = callOpenRouter(
                model = model,
                messages = requestMessages,
                forceJson = strictJson,
                apiKeyOverride = apiKey,
                temperature = 0.0
            )
            val agentMessage = ChatMessage(
                author = "Agent",
                role = AuthorRole.AGENT,
                text = result.content,
                metrics = result.stats.toMsgMetrics()
            )
            withContext(Dispatchers.Main) {
                appendMessage(agentMessage)
                isSending = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if ((settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey).isNullOrBlank()) {
            appendMessage(
                ChatMessage(
                    author = "Agent",
                    role = AuthorRole.AGENT,
                    text = "Укажите OPENROUTER_API_KEY, чтобы агент мог отвечать.",
                    metrics = MsgMetrics(null, null, null, null, null)
                )
            )
        }
    }

    when (screen) {
        Screen.MAIN -> {
            Scaffold(
                topBar = {
                    Surface(shadowElevation = 4.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AOSP Bugreport Analyzer — Чат",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { screen = Screen.MODEL_BENCH }) { Text("Model Bench") }
                                TextButton(onClick = { screen = Screen.SETTINGS }) { Text("Настройки") }
                            }
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        MessageList(messages)
                        if (isSending) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Введите сообщение") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { sendMessage() },
                            enabled = input.isNotBlank() && !isSending
                        ) {
                            Text("Отправить")
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
                MetricsLine(message.role, message.metrics)
                if (lastCopiedId == message.id) {
                    Text(
                        text = "Скопировано",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Divider(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MetricsLine(role: AuthorRole, metrics: MsgMetrics?) {
    val m = metrics ?: MsgMetrics(null, null, null, null, null)
    val text = buildString {
        if (role == AuthorRole.USER) {
            append("P: ${m.promptTokens ?: "—"}")
            append(" • T: ${m.totalTokens ?: "—"}")
        } else {
            append(
                "P/C/T: ${m.promptTokens ?: "—"}/${m.completionTokens ?: "—"}/${m.totalTokens ?: "—"}"
            )
        }
        append(" • ⏱ ")
        append(
            m.elapsedMs?.let {
                if (it < 1000) "$it ms" else String.format(Locale.US, "%.2f s", it / 1000.0)
            } ?: "—"
        )
        append(" • $")
        append(m.costUsd?.let { String.format(Locale.US, "%.4f", it) } ?: "—")
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Чат") {
        MaterialTheme {
            DesktopChatApp()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
                                statusMessage = "Готово: ${'$'}okCount успешных из ${'$'}{responses.size}."
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
                            val baseDir = java.nio.file.Paths.get("model_bench")
                            try {
                                withContext(Dispatchers.IO) {
                                    java.nio.file.Files.createDirectories(baseDir)
                                    val markdown = buildModelBenchMarkdown(currentPrompt, currentResults)
                                    val json = buildModelBenchJson(currentPrompt, currentResults)
                                    java.nio.file.Files.writeString(
                                        baseDir.resolve("results-$timestamp.md"),
                                        markdown,
                                        java.nio.file.StandardOpenOption.CREATE,
                                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                                        java.nio.file.StandardOpenOption.WRITE
                                    )
                                    java.nio.file.Files.writeString(
                                        baseDir.resolve("results-$timestamp.json"),
                                        json,
                                        java.nio.file.StandardOpenOption.CREATE,
                                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                                        java.nio.file.StandardOpenOption.WRITE
                                    )
                                }
                                statusMessage = "Exported to model_bench/results-$timestamp.*"
                            } catch (t: Throwable) {
                                runError = "Export failed: ${'$'}{t.message ?: t::class.simpleName}".trim()
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
                        statusMessage = "Outputs copied (${'$'}{successful.size})."
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
                        Text("Cost", modifier = Modifier.weight(1f))
                    }
                    results.forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(2.5f)) {
                                Text(
                                    benchModels.firstOrNull { it.id == result.modelId }?.alias ?: result.modelId,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    result.modelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                result.timeMs?.toString() ?: "-",
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                formatTokenTriple(result.promptTokens, result.completionTokens, result.totalTokens),
                                modifier = Modifier.weight(1.4f)
                            )
                            Text(
                                formatCostUsd(result.costUsd),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        result.error?.let { error ->
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            } else {
                Text("Нет результатов. Запустите сравнение.")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
