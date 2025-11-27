@file:OptIn(ExperimentalMaterial3Api::class)

package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.bigdotdev.aospbugreportanalyzer.bugreport.BugreportFileReader
import com.bigdotdev.aospbugreportanalyzer.bugreport.BugreportReadException
import com.bigdotdev.aospbugreportanalyzer.mcp.GithubPullRequest
import com.bigdotdev.aospbugreportanalyzer.mcp.McpGithubClientException
import com.bigdotdev.aospbugreportanalyzer.mcp.McpPullRequest
import com.bigdotdev.aospbugreportanalyzer.mcp.adbGetBugreport
import com.bigdotdev.aospbugreportanalyzer.mcp.adbListDevices
import com.bigdotdev.aospbugreportanalyzer.mcp.listOpenPullRequestsViaMcpGithub
import com.bigdotdev.aospbugreportanalyzer.mcp.withMcpAdbClient
import com.bigdotdev.aospbugreportanalyzer.mcp.withMcpGithubApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipFile
import java.util.UUID
import com.bigdotdev.aospbugreportanalyzer.storage.HistoryLogger
import com.bigdotdev.aospbugreportanalyzer.storage.StoragePaths
import com.bigdotdev.aospbugreportanalyzer.memory.AgentMemoryEntry
import com.bigdotdev.aospbugreportanalyzer.memory.AgentMemoryRepository
import com.bigdotdev.aospbugreportanalyzer.memory.MessageStats
import com.bigdotdev.aospbugreportanalyzer.memory.MemoryMeta
import com.bigdotdev.aospbugreportanalyzer.memory.createAgentMemoryStore
import com.bigdotdev.aospbugreportanalyzer.rag.RagMode
import com.bigdotdev.aospbugreportanalyzer.rag.RagServiceLocator
import com.bigdotdev.aospbugreportanalyzer.rag.ScoredChunk
import com.bigdotdev.aospbugreportanalyzer.rag.askLlmPlain
import com.bigdotdev.aospbugreportanalyzer.rag.askLlmWithRag
import com.bigdotdev.aospbugreportanalyzer.rag.compareRagAndPlain
import com.bigdotdev.aospbugreportanalyzer.rag.toEmbeddingIndex

private enum class Screen { MAIN, SETTINGS }

private enum class AuthorRole { USER, EXPERT, SUMMARY }

private fun AuthorRole.displayName(): String = when (this) {
    AuthorRole.USER -> "Пользователь"
    AuthorRole.EXPERT -> "Эксперт"
    AuthorRole.SUMMARY -> "Сводка"
}

sealed class PipelineMode {
    object AllOpenPrs : PipelineMode()
    data class SinglePr(val number: Int) : PipelineMode()
}

private sealed class BugreportPipelineMode {
    data class SingleDevice(val serial: String) : BugreportPipelineMode()
}

private const val MESSAGES_PER_SUMMARY = 10
private const val MAX_RECENT_MESSAGES = 8
private const val BUGREPORT_SUMMARY_PRIMARY_LIMIT = 80_000
private const val BUGREPORT_SUMMARY_FALLBACK_LIMIT = 40_000
private const val BUGREPORT_SUMMARY_MAX_TOKENS = 3_000

private data class MsgMetrics(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val elapsedMs: Long?,
    val costUsd: Double?,
    val error: String? = null
)

private data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val role: AuthorRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metrics: MsgMetrics? = null,
    val isSummary: Boolean = false,
    val summaryGroupId: Int? = null
)

private data class SummaryResult(
    val text: String,
    val promptTokens: Int?,
    val completionTokens: Int?
)

private data class BugreportTextResult(
    val text: String,
    val source: String,
    val originalLength: Int,
    val usedLength: Int
)

data class CompressionStats(
    val summaryGroupId: Int,
    val messagesCount: Int,
    val charsBefore: Int,
    val charsAfter: Int,
    val reductionPercent: Int,
    val summaryPromptTokens: Int? = null,
    val summaryCompletionTokens: Int? = null
)

data class IndexingState(
    val isRunning: Boolean,
    val currentStep: String,
    val totalChunks: Int?,
    val processedChunks: Int,
    val startedAtMillis: Long
)

private data class CallStats(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val elapsedMs: Long?,
    val costUsd: Double?
)

private data class OpenRouterError(
    val httpCode: Int,
    val errorCode: Int? = null,
    val message: String? = null,
    val rawBody: String? = null
)

private sealed class OpenRouterResult {
    data class Success(
        val content: String,
        val stats: CallStats
    ) : OpenRouterResult()

    data class Failure(
        val error: OpenRouterError
    ) : OpenRouterResult()
}

private fun CallStats.toMsgMetrics(): MsgMetrics =
    MsgMetrics(promptTokens, completionTokens, totalTokens, elapsedMs, costUsd, error = null)

private fun CallStats.toMessageStats(): MessageStats =
    MessageStats(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        costUsd = costUsd,
        durationMs = elapsedMs
    )

private class BugreportExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

object OpenRouterConfig {
    const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    const val EMBEDDINGS_URL = "https://openrouter.ai/api/v1/embeddings"
    val apiKey: String? get() = System.getenv("OPENROUTER_API_KEY")
    const val referer: String = "http://localhost"
    const val title: String = "AOSPBugreportAnalyzer"
}

private data class ORMessage(val role: String, val content: String)

private data class ORRequest(
    val model: String,
    val messages: List<ORMessage>,
    val response_format: Map<String, String>? = null,
    val temperature: Double,
    val max_tokens: Int? = null,
    val top_p: Double? = null
)

private val SYSTEM_TEXT = """
You are an AOSP bugreport Expert. Keep answers short, actionable, and plain text without extra formatting.
""".trimIndent()

private val SYSTEM_JSON = """
You are an AOSP bugreport Expert. Keep answers short and actionable.
Return ONLY valid JSON (UTF-8) with keys: version, ok, generated_at, items, error.
""".trimIndent()

private val BUGREPORT_SYSTEM_PROMPT = """
Ты ассистент, который анализирует Android bugreport и делает техническое summary для разработчика.
Формат ответа — Markdown.
Обязательно выделяй:
- ключевые проблемы и ошибки;
- возможные проблемы с памятью, ANR, перформансом;
- рекомендации по дальнейшей диагностике.
""".trimIndent()

private fun trimBugreportForSummary(
    fullText: String,
    limit: Int
): String {
    if (fullText.length <= limit) return fullText

    return fullText.take(limit)
}

private fun buildBugreportSummaryPrompt(rawBugreport: String): String {
    val header = """
        You are an assistant that analyzes Android bugreports and produces concise, structured summaries for engineers.

        Below is the raw text of an Android bugreport. It may contain long logs and sections like
        "SUMMARY", "DUMP OF SERVICE", "------ SYSTEM LOG ------" and similar. Treat them as plain text,
        do NOT try to execute or expand them, just analyze the content.

        Your task:
        1. Identify the main issues, crashes, ANRs or error patterns.
        2. Highlight the most important findings for debugging.
        3. If there is too much noise or not enough information, say that explicitly.

        Respond in short, structured Markdown:
        - High-level summary
        - Key findings
        - Suspected root causes (if any)
        - Suggested next steps

        --- BUGREPORT START ---
    """.trimIndent()

    val footer = "\n--- BUGREPORT END ---"

    return header + "\n" + rawBugreport + footer
}

private fun OpenRouterError.isStackOverflowLike(): Boolean {
    return message?.contains("StackOverflowError", ignoreCase = true) == true ||
        rawBody?.contains("StackOverflowError", ignoreCase = true) == true
}

private val SUMMARY_SYSTEM_PROMPT = """
Ты помощник, который сжимает диалоги.
На основе сообщений пользователя и эксперта составь краткую сводку из 3–6 предложений на русском языке.
Отражай только факты и выводы без лишних рассуждений.
""".trimIndent()

data class AppSettings(
    val openRouterApiKey: String = System.getenv("OPENROUTER_API_KEY") ?: "",
    val openRouterModel: String = "x-ai/grok-4.1-fast:free",
    val strictJsonEnabled: Boolean = false,
    val useCompression: Boolean = false,
    val indexingTimeoutMinutes: Int = 15,
    val ragEnabled: Boolean = true
)

private fun selectSystemPrompt(forceJson: Boolean): String = if (forceJson) SYSTEM_JSON else SYSTEM_TEXT

private data class Price(val inPerM: Double, val outPerM: Double)

private val modelPriceMap = mapOf(
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

private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .build()

private val openRouterJson = Json { ignoreUnknownKeys = true }

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

private val promptTokensRegex = "\"prompt_tokens\"\\s*:\\s*(\\d+)".toRegex()
private val completionTokensRegex = "\"completion_tokens\"\\s*:\\s*(\\d+)".toRegex()
private val totalTokensRegex = "\"total_tokens\"\\s*:\\s*(\\d+)".toRegex()

private fun jsonError(message: String): String {
    val escaped = encodeJsonString(message)
    return "{\"version\":\"1.0\",\"ok\":false,\"generated_at\":\"\",\"items\":[],\"error\":\"$escaped\"}"
}

private fun errorResponse(forceJson: Boolean, message: String): String =
    if (forceJson) jsonError(message) else "Error: $message"

private fun parseOpenRouterError(statusCode: Int, body: String?): OpenRouterError {
    val safeBody = body ?: ""
    return try {
        val parsed = openRouterJson.decodeFromString<JsonElement>(safeBody)
        val errorObject = parsed.jsonObject["error"]?.jsonObject
        val code = errorObject?.get("code")?.jsonPrimitive?.intOrNull
        val message = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
        OpenRouterError(
            httpCode = statusCode,
            errorCode = code,
            message = message,
            rawBody = safeBody.take(500).ifBlank { null }
        )
    } catch (_: Throwable) {
        OpenRouterError(
            httpCode = statusCode,
            errorCode = null,
            message = null,
            rawBody = safeBody.take(500).ifBlank { null }
        )
    }
}

private fun OpenRouterError.toUserMessage(): String {
    val base = message?.takeIf { it.isNotBlank() }
    val prefix = when (httpCode) {
        0 -> base ?: "Не настроен OpenRouter API key. Откройте экран настроек и укажите ключ."
        400 -> "Некорректный запрос к OpenRouter (400)."
        401 -> "OpenRouter: неверный или отключённый API-ключ (401)."
        402 -> "OpenRouter: недостаточно кредитов или баланс слишком мал (402)."
        403 -> "OpenRouter: доступ к запросу/модели запрещён (403)."
        408 -> "OpenRouter: тайм-аут запроса (408)."
        429 -> "OpenRouter: превышен лимит запросов (429)."
        500, 502, 503 -> "Проблемы на стороне провайдера/модели (код $httpCode)."
        else -> "Ошибка OpenRouter (код $httpCode)."
    }

    return when {
        httpCode == 0 && base != null -> base
        base != null -> "$prefix Детали: $base"
        !rawBody.isNullOrBlank() -> "$prefix Ответ: ${rawBody.take(200)}"
        else -> prefix
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(timestamp: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(timestamp))

private fun callOpenRouter(
    model: String,
    messages: List<ORMessage>,
    forceJson: Boolean,
    apiKeyOverride: String?,
    temperature: Double,
    maxTokens: Int? = null,
    topP: Double? = null
): OpenRouterResult {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return OpenRouterResult.Failure(
            OpenRouterError(
                httpCode = 0,
                message = "Не настроен OpenRouter API key. Откройте экран настроек и укажите ключ.",
                rawBody = null
            )
        )
    }

    val request = ORRequest(
        model = model,
        messages = messages,
        response_format = if (forceJson) mapOf("type" to "json_object") else null,
        temperature = temperature.coerceIn(0.0, 2.0),
        max_tokens = maxTokens,
        top_p = topP?.coerceIn(0.0, 1.0)
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
        request.max_tokens?.let { maxTokensValue ->
            append(",\"max_tokens\":")
            append(maxTokensValue)
        }
        request.top_p?.let { topPValue ->
            append(",\"top_p\":")
            append(String.format(Locale.US, "%.2f", topPValue))
        }
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
        val body = response.body()
        if (response.statusCode() !in 200..299) {
            return OpenRouterResult.Failure(parseOpenRouterError(response.statusCode(), body))
        }
        val content = extractContentFromOpenAIJson(body)
            ?: return OpenRouterResult.Failure(
                OpenRouterError(
                    httpCode = response.statusCode(),
                    message = "OpenRouter вернул пустой контент.",
                    rawBody = body.take(500)
                )
            )
        val prompt = promptTokensRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val completion = completionTokensRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val total = totalTokensRegex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: if (prompt != null && completion != null) prompt + completion else null
        val price = modelPriceMap[model]
        val cost = if (price != null && prompt != null && completion != null) {
            ((prompt * price.inPerM) + (completion * price.outPerM)) / 1_000_000.0
        } else {
            null
        }
        OpenRouterResult.Success(
            content = content,
            stats = CallStats(prompt, completion, total, elapsed, cost)
        )
    } catch (t: Throwable) {
        OpenRouterResult.Failure(
            OpenRouterError(
                httpCode = -1,
                message = t.message ?: t::class.simpleName ?: "unknown error",
                rawBody = null
            )
        )
    }
}

private fun buildChatRequestMessages(
    history: List<ChatMessage>,
    forceJson: Boolean
): List<ORMessage> {
    val messages = mutableListOf(ORMessage("system", selectSystemPrompt(forceJson)))
    history.forEach { message ->
        val role = when (message.role) {
            AuthorRole.USER -> "user"
            AuthorRole.EXPERT -> "assistant"
            AuthorRole.SUMMARY -> "system"
        }
        messages += ORMessage(role, message.text)
    }
    return messages
}

private fun buildSummaryRequestMessages(messages: List<ChatMessage>): List<ORMessage> {
    val content = buildString {
        appendLine("Вот фрагмент диалога между пользователем и экспертом. Сожми его в краткую сводку (3–6 предложений, по-русски). Не добавляй лишних рассуждений, только факты и важные выводы.")
        appendLine()
        messages.forEach { msg ->
            val roleLabel = when (msg.role) {
                AuthorRole.USER -> "Пользователь"
                AuthorRole.EXPERT -> "Эксперт"
                AuthorRole.SUMMARY -> "Система"
            }
            appendLine("$roleLabel: ${msg.text}")
        }
    }
    return listOf(
        ORMessage("system", SUMMARY_SYSTEM_PROMPT),
        ORMessage("user", content)
    )
}

private fun summarizeMessages(
    messages: List<ChatMessage>,
    model: String,
    apiKeyOverride: String?
): SummaryResult? {
    val requestMessages = buildSummaryRequestMessages(messages)
    return when (val result = callOpenRouter(
        model = model,
        messages = requestMessages,
        forceJson = false,
        apiKeyOverride = apiKeyOverride,
        temperature = 0.0
    )) {
        is OpenRouterResult.Success -> {
            val text = result.content.trim()
            if (text.isBlank()) {
                null
            } else {
                SummaryResult(
                    text = text,
                    promptTokens = result.stats.promptTokens,
                    completionTokens = result.stats.completionTokens
                )
            }
        }

        is OpenRouterResult.Failure -> null
    }
}

private fun trimRecentMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val nonSummary = messages.filter { !it.isSummary }
    if (nonSummary.size <= MAX_RECENT_MESSAGES) return messages
    val toDrop = nonSummary.take(nonSummary.size - MAX_RECENT_MESSAGES).map { it.id }.toSet()
    return messages.filterNot { it.id in toDrop }
}

@Composable
private fun DesktopChatApp() {
    val scope = rememberCoroutineScope()
    val memoryJson = remember {
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
    val memoryRepository = remember {
        AgentMemoryRepository(createAgentMemoryStore(memoryJson))
    }
    var settings by remember { mutableStateOf(AppSettings()) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var compressionStats by remember { mutableStateOf(listOf<CompressionStats>()) }
    var summaryCounter by remember { mutableStateOf(0) }
    var isCompressionRunning by remember { mutableStateOf(false) }
    var memoryCount by remember { mutableStateOf(0) }
    var recentMemoryEntries by remember { mutableStateOf<List<AgentMemoryEntry>>(emptyList()) }
    var ragMode by remember { mutableStateOf(RagMode.PLAIN) }
    var indexingState by remember {
        mutableStateOf(
            IndexingState(
                isRunning = false,
                currentStep = "",
                totalChunks = null,
                processedChunks = 0,
                startedAtMillis = 0L
            )
        )
    }

    LaunchedEffect(Unit) {
        StoragePaths.ensureDirs()
    }

    suspend fun persistTurnAndFetch(
        userMessage: String,
        assistantMessage: String,
        stats: MessageStats?,
        tags: List<String>,
        isSummaryTurn: Boolean
    ): List<AgentMemoryEntry> {
        memoryRepository.rememberTurn(
            conversationId = "default",
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            stats = stats,
            tags = tags,
            isSummaryTurn = isSummaryTurn
        )
        println(
            "[AgentMemory] rememberTurn: user='${userMessage.take(60)}', assistant='${assistantMessage.take(60)}', isSummary=$isSummaryTurn"
        )
        return memoryRepository.getAllEntries()
    }

    fun refreshMemory(entries: List<AgentMemoryEntry>) {
        memoryCount = entries.size
        recentMemoryEntries = entries.takeLast(5)
    }

    fun clearExternalMemory() {
        scope.launch(Dispatchers.IO) {
            runCatching { memoryRepository.clearAll() }
            withContext(Dispatchers.Main) {
                memoryCount = 0
                recentMemoryEntries = emptyList()
            }
        }
    }

    fun configureRagProviders(apiKey: String, model: String) {
        RagServiceLocator.embeddingProvider = { text, embeddingModel ->
            val result = callOpenRouterEmbeddings(listOf(text), embeddingModel, apiKeyOverride = apiKey)
            result.getOrElse { throw it }.firstOrNull() ?: emptyList()
        }
        RagServiceLocator.llmResponder = { prompt ->
            val messages = listOf(
                ORMessage("system", selectSystemPrompt(false)),
                ORMessage("user", prompt)
            )
            when (val result = callOpenRouter(
                model = model,
                messages = messages,
                forceJson = false,
                apiKeyOverride = apiKey,
                temperature = 0.0
            )) {
                is OpenRouterResult.Success -> result.content
                is OpenRouterResult.Failure -> throw IllegalStateException(result.error.toUserMessage())
            }
        }
    }

    fun formatChunks(chunks: List<ScoredChunk>): String {
        if (chunks.isEmpty()) return "Чанки не найдены для этого запроса."
        return buildString {
            appendLine("Источники:")
            chunks.forEachIndexed { idx, scored ->
                val number = idx + 1
                val bugreport = scored.chunk.metadata["bugreport"] ?: "unknown"
                val range = scored.chunk.metadata["range"] ?: "unknown"
                appendLine("[${number}] bugreport=$bugreport, range=$range, score=${"%.3f".format(scored.score)}")
                val snippet = scored.chunk.text.take(160).replace('\n', ' ')
                appendLine("   \"$snippet...\"")
                appendLine()
            }
        }
    }

    LaunchedEffect(memoryRepository) {
        val entries = runCatching { memoryRepository.getAllEntries() }.getOrElse { emptyList() }
        println("[AgentMemory] init: loaded ${entries.size} entries from repository")
        refreshMemory(entries)
        if (entries.isNotEmpty() && messages.isEmpty()) {
            messages = entries.flatMap { it.toChatMessages() }
        }
    }

    fun appendMessage(message: ChatMessage) {
        messages = messages + message
    }

    fun applySummary(toCompress: List<ChatMessage>, result: SummaryResult) {
        summaryCounter += 1
        val groupId = summaryCounter
        val idsToRemove = toCompress.map { it.id }.toSet()
        val charsBefore = toCompress.sumOf { it.text.length }
        val charsAfter = result.text.length
        val reduction = if (charsBefore > 0) {
            (100 - (charsAfter * 100 / charsBefore)).coerceIn(-999, 999)
        } else {
            0
        }
        val earliestTimestamp = toCompress.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        val summaryMessage = ChatMessage(
            id = "summary-$groupId",
            author = "Summary",
            role = AuthorRole.SUMMARY,
            text = result.text,
            timestamp = earliestTimestamp,
            metrics = null,
            isSummary = true,
            summaryGroupId = groupId
        )
        val remainingMessages = messages.filterNot { it.id in idsToRemove }.toMutableList()
        val insertIndex = remainingMessages.indexOfFirst { it.timestamp > earliestTimestamp }
        if (insertIndex >= 0) {
            remainingMessages.add(insertIndex, summaryMessage)
        } else {
            remainingMessages.add(summaryMessage)
        }
        val trimmed = trimRecentMessages(remainingMessages)
        messages = trimmed
        val stats = CompressionStats(
            summaryGroupId = groupId,
            messagesCount = toCompress.size,
            charsBefore = charsBefore,
            charsAfter = charsAfter,
            reductionPercent = reduction,
            summaryPromptTokens = result.promptTokens,
            summaryCompletionTokens = result.completionTokens
        )
        compressionStats = compressionStats + stats
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                persistTurnAndFetch(
                    userMessage = "[SUMMARY REQUEST]",
                    assistantMessage = result.text,
                    stats = result.toMessageStats(),
                    tags = listOf("summary"),
                    isSummaryTurn = true
                )
            }
                .onFailure {
                    println("[AgentMemory] persistTurnAndFetch (summary) failed: ${it.message}")
                    it.printStackTrace()
                }
                .getOrNull()
            if (entries != null) {
                withContext(Dispatchers.Main) {
                    refreshMemory(entries)
                }
            }
        }
    }

    fun maybeCompressHistory() {
        if (!settings.useCompression || isCompressionRunning) return
        val nonSummaryMessages = messages.filter { !it.isSummary }
        if (nonSummaryMessages.size < MESSAGES_PER_SUMMARY) return
        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (apiKey.isNullOrBlank()) return
        val toCompress = nonSummaryMessages.take(MESSAGES_PER_SUMMARY)
        val model = settings.openRouterModel
        isCompressionRunning = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { summarizeMessages(toCompress, model, apiKey) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (result != null && settings.useCompression) {
                    applySummary(toCompress, result)
                }
                isCompressionRunning = false
            }
        }
    }



    fun addAssistantMessage(text: String) {
        appendMessage(
            ChatMessage(
                author = "Эксперт",
                role = AuthorRole.EXPERT,
                text = text,
                metrics = null
            )
        )
    }

    fun addSystemMessage(text: String) {
        appendMessage(
            ChatMessage(
                author = "System",
                role = AuthorRole.EXPERT,
                text = text,
                metrics = null
            )
        )
    }

    suspend fun extractLargestTxtFromBugreportZip(zipPath: String): String {
        println("[BugreportExtractor] Extracting largest .txt from: $zipPath")
        println("[BugreportExtractor] Opening ZIP bugreport at $zipPath")
        return withContext(Dispatchers.IO) {
            try {
                ZipFile(zipPath).use { zipFile ->
                    val txtEntries = zipFile.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory && entry.name.lowercase(Locale.getDefault()).endsWith(".txt")
                        }
                        .map { entry ->
                            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                            entry to bytes
                        }
                        .toList()

                    if (txtEntries.isEmpty()) {
                        println("[BugreportExtractor] No .txt files found in zip: $zipPath")
                        throw IllegalStateException("No .txt files in bugreport zip")
                    }

                    val (largestEntry, bytes) = txtEntries.maxByOrNull { it.second.size }
                        ?: throw IllegalStateException("No .txt files in bugreport zip")

                    println("[BugreportExtractor] Found ${txtEntries.size} .txt entries in zip, largest is ${largestEntry.name} (${bytes.size} bytes)")
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                println("[BugreportExtractor] Failed to extract txt from $zipPath: ${t.message}")
                throw BugreportExtractionException(
                    "Не удалось извлечь .txt из bugreport.zip. Убедись, что это стандартный Android bugreport.",
                    t
                )
            }
        }
    }

    suspend fun loadBugreportTextResult(bugreportFilePath: String): BugreportTextResult {
        val bugreportFile = File(bugreportFilePath)
        if (!bugreportFile.exists()) {
            throw BugreportExtractionException("Bugreport file not found: $bugreportFilePath")
        }

        val ext = bugreportFile.extension.lowercase(Locale.getDefault())
        var effectiveSource = bugreportFilePath
        val bugreportText = try {
            if (ext == "zip") {
                println("[BugreportExtractor] Treating bugreport as ZIP archive: $bugreportFilePath")
                withContext(Dispatchers.IO) { BugreportFileReader.readTextRobust(bugreportFile.toPath()) }
            } else {
                println("[BugreportExtractor] Treating bugreport as plain text file: $bugreportFilePath")
                val text = withContext(Dispatchers.IO) { BugreportFileReader.readTextRobust(bugreportFile.toPath()) }
                val pointerRegex = Regex("Bug report copied to\\s+(\\S+\\.zip)")
                val pointerZip = pointerRegex.find(text)?.groupValues?.getOrNull(1)
                val inlineZip = "/\\S+\\.zip".toRegex().find(text)?.value
                val zipPath = pointerZip ?: inlineZip
                if (zipPath != null) {
                    val zipFile = File(zipPath)
                    if (zipFile.exists()) {
                        println("[BugreportExtractor] Detected pointer TXT, redirecting to ZIP: $zipPath")
                        effectiveSource = zipPath
                        withContext(Dispatchers.IO) { BugreportFileReader.readTextRobust(zipFile.toPath()) }
                    } else {
                        println("[BugreportExtractor] Pointer TXT found, but ZIP not found at $zipPath, falling back to plain text.")
                        text
                    }
                } else {
                    text
                }
            }
        } catch (e: BugreportReadException) {
            HistoryLogger.log("[BugreportExtractor] Failed to read bugreport $bugreportFilePath: ${e.message}")
            throw BugreportExtractionException("Не удалось прочитать bugreport: ${e.message}", e)
        }

        val normalizedBugreportText = trimBugreportForSummary(bugreportText, BUGREPORT_SUMMARY_PRIMARY_LIMIT)
        println("[Orchestrator] Loaded bugreport text from $effectiveSource, length=${bugreportText.length}, used=${normalizedBugreportText.length}")
        val preview = normalizedBugreportText.replace("\\s+".toRegex(), " ").take(300)
        println("[Orchestrator] Bugreport text preview: $preview")

        return BugreportTextResult(
            text = normalizedBugreportText,
            source = effectiveSource,
            originalLength = bugreportText.length,
            usedLength = normalizedBugreportText.length
        )
    }

    suspend fun loadBugreportText(bugreportFilePath: String): String {
        return loadBugreportTextResult(bugreportFilePath).text
    }

    fun copyBugreportToStorage(bugreportPath: String): String {
        val source = Paths.get(bugreportPath)
        val target = StoragePaths.bugreportsDir.resolve(source.fileName)
        return try {
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            println("[Storage] Copying bugreport from $source to $target")
            HistoryLogger.log("Bugreport copied to local storage: $target")
            target.toString()
        } catch (t: Throwable) {
            println("[Storage] Failed to copy bugreport from $source to $target: ${t.message}")
            HistoryLogger.log("Failed to copy bugreport from $source to $target: ${t.message}")
            bugreportPath
        }
    }

    suspend fun callBugreportSummaryLlm(
        bugreportText: String,
        limit: Int,
        apiKey: String,
        model: String
    ): OpenRouterResult {
        val truncated = trimBugreportForSummary(bugreportText, limit)
        val truncatedLog = "[Orchestrator] Bugreport text truncated for LLM: original=${bugreportText.length}, used=${truncated.length}, limit=$limit"
        println(truncatedLog)
        HistoryLogger.log(truncatedLog)
        HistoryLogger.log("LLM bugreport summary: usingLimit=$limit, original=${bugreportText.length}, used=${truncated.length}")

        val prompt = buildBugreportSummaryPrompt(truncated)
        val promptLengthLog = "[Orchestrator] LLM bugreport summary prompt length=${prompt.length}"
        println(promptLengthLog)
        HistoryLogger.log(promptLengthLog)

        return withContext(Dispatchers.IO) {
            callOpenRouter(
                model = model,
                messages = listOf(
                    ORMessage("system", BUGREPORT_SYSTEM_PROMPT),
                    ORMessage("user", prompt)
                ),
                forceJson = false,
                apiKeyOverride = apiKey,
                temperature = 0.2,
                maxTokens = BUGREPORT_SUMMARY_MAX_TOKENS,
                topP = 0.9
            )
        }
    }

    suspend fun generateBugreportSummary(
        bugreportText: String,
        model: String,
        apiKey: String,
        onMessage: (String) -> Unit
    ): String? {
        val primaryResult = callBugreportSummaryLlm(
            bugreportText = bugreportText,
            limit = BUGREPORT_SUMMARY_PRIMARY_LIMIT,
            apiKey = apiKey,
            model = model
        )

        val finalResult = when (primaryResult) {
            is OpenRouterResult.Success -> primaryResult
            is OpenRouterResult.Failure -> {
                val error = primaryResult.error
                if (error.isStackOverflowLike()) {
                    HistoryLogger.log("LLM bugreport summary StackOverflow: httpCode=${error.httpCode}, message=${error.message}, rawBody=${error.rawBody}")
                    HistoryLogger.log("Retrying bugreport summary with stricter limit=$BUGREPORT_SUMMARY_FALLBACK_LIMIT")
                    onMessage("[Pipeline] Модели стало тяжело от объёма багрепорта, пробую отправить более короткую версию...")

                    callBugreportSummaryLlm(
                        bugreportText = bugreportText,
                        limit = BUGREPORT_SUMMARY_FALLBACK_LIMIT,
                        apiKey = apiKey,
                        model = model
                    )
                } else {
                    primaryResult
                }
            }
        }

        return when (finalResult) {
            is OpenRouterResult.Success -> finalResult.content
            is OpenRouterResult.Failure -> {
                val err = finalResult.error
                val message = err.message ?: err.rawBody ?: "неизвестная ошибка"
                val userMessage = if (err.isStackOverflowLike()) {
                    "[Pipeline] Ошибка: багрепорт оказался слишком тяжёлым для модели даже после повторной попытки. Попробуйте передать более короткий фрагмент."
                } else {
                    "[Pipeline] Ошибка при вызове LLM для summary багрепорта: $message"
                }
                onMessage(userMessage)
                HistoryLogger.log("LLM bugreport summary failed after retry: httpCode=${err.httpCode}, message=${err.message}, rawBody=${err.rawBody}")
                null
            }
            else -> {
                onMessage("[Pipeline] Неизвестный результат при генерации summary багрепорта.")
                HistoryLogger.log("LLM bugreport summary: unexpected OpenRouterResult type: ${finalResult::class.qualifiedName}")
                null
            }
        }
    }

    suspend fun runIndexingPipelineFromText(
        sourceText: String,
        sourcePath: String,
        onMessage: (String) -> Unit
    ): Boolean {
        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            onMessage("[Pipeline] Ошибка: отсутствует OPENROUTER_API_KEY для вызова LLM.")
            HistoryLogger.log("LLM call skipped: missing OPENROUTER_API_KEY")
            return false
        }

        StoragePaths.ensureDirs()

        val timeoutMillis = settings.indexingTimeoutMinutes.coerceAtLeast(1) * 60_000L
        withContext(Dispatchers.Main) {
            indexingState = IndexingState(
                isRunning = true,
                currentStep = "Разбиение на чанки…",
                totalChunks = null,
                processedChunks = 0,
                startedAtMillis = System.currentTimeMillis()
            )
        }

        return try {
            val index = withTimeoutOrNull(timeoutMillis) {
                buildBugreportIndex(
                    bugreportText = sourceText,
                    bugreportSourcePath = sourcePath,
                    embeddingsModel = DEFAULT_EMBEDDINGS_MODEL,
                    apiKey = apiKey,
                    onChunksPrepared = { total ->
                        scope.launch(Dispatchers.Main) {
                            indexingState = indexingState.copy(
                                currentStep = "Построение эмбеддингов…",
                                totalChunks = total,
                                processedChunks = 0
                            )
                        }
                    },
                    onChunkProcessed = { processed, total ->
                        scope.launch(Dispatchers.Main) {
                            indexingState = indexingState.copy(
                                processedChunks = processed,
                                totalChunks = total
                            )
                        }
                    }
                )
            }

            if (index == null) {
                onMessage("Индексация прервана: превышен лимит ${settings.indexingTimeoutMinutes} минут.")
                HistoryLogger.log("BugreportIndex: timed out after ${settings.indexingTimeoutMinutes} minutes")
                return false
            }

            withContext(Dispatchers.Main) {
                indexingState = indexingState.copy(
                    currentStep = "Финализация индекса",
                    totalChunks = index.chunks.size,
                    processedChunks = index.chunks.size
                )
            }

            val indexPath = saveBugreportIndexToFile(index, StoragePaths.indexesDir)
            onMessage("[Pipeline] Индекс источника сохранён: $indexPath")
            HistoryLogger.log("BugreportIndex: saved to $indexPath, chunks=${index.chunks.size}")
            true
        } catch (t: Throwable) {
            onMessage("[Pipeline] Не удалось построить индекс bugreport (чанки + эмбеддинги): ${t.message}")
            HistoryLogger.log("BugreportIndex: failed to build index: ${t.message}")
            false
        } finally {
            withContext(Dispatchers.Main) {
                indexingState = IndexingState(
                    isRunning = false,
                    currentStep = "",
                    totalChunks = null,
                    processedChunks = 0,
                    startedAtMillis = System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun indexBugreportFromFile(serial: String, outputFile: File) {
        withContext(Dispatchers.Main) {
            indexingState = IndexingState(
                isRunning = true,
                currentStep = "Чтение файла",
                totalChunks = null,
                processedChunks = 0,
                startedAtMillis = System.currentTimeMillis()
            )
        }

        val bugreportTextResult = try {
            loadBugreportTextResult(outputFile.absolutePath)
        } catch (e: BugreportExtractionException) {
            addSystemMessage(e.message ?: "Не удалось подготовить bugreport из файла: ${outputFile.absolutePath}")
            HistoryLogger.log("Bugreport extraction failed: ${e.message}")
            withContext(Dispatchers.Main) {
                indexingState = IndexingState(
                    isRunning = false,
                    currentStep = "",
                    totalChunks = null,
                    processedChunks = 0,
                    startedAtMillis = System.currentTimeMillis()
                )
            }
            return
        } catch (t: Throwable) {
            addSystemMessage("Не удалось прочитать текст bugreport из файла `${outputFile.absolutePath}`. Детали: ${t.message}")
            HistoryLogger.log("Bugreport read failed: ${t.message}")
            withContext(Dispatchers.Main) {
                indexingState = IndexingState(
                    isRunning = false,
                    currentStep = "",
                    totalChunks = null,
                    processedChunks = 0,
                    startedAtMillis = System.currentTimeMillis()
                )
            }
            return
        }

        addSystemMessage("Снял bugreport с устройства `$serial`, файл: ${bugreportTextResult.source}. Запускаю индексацию…")
        runIndexingPipelineFromText(
            sourceText = bugreportTextResult.text,
            sourcePath = bugreportTextResult.source,
            onMessage = ::addSystemMessage
        )
    }

    suspend fun indexLogcatFromFile(serial: String, outputFile: File) {
        withContext(Dispatchers.Main) {
            indexingState = IndexingState(
                isRunning = true,
                currentStep = "Чтение файла",
                totalChunks = null,
                processedChunks = 0,
                startedAtMillis = System.currentTimeMillis()
            )
        }

        val logcatText = try {
            withContext(Dispatchers.IO) { BugreportFileReader.readTextRobust(outputFile.toPath()) }
        } catch (t: Throwable) {
            val message = when (t) {
                is BugreportReadException -> "Не удалось прочитать logcat из файла `${outputFile.absolutePath}`: ${t.message}"
                else -> "Не удалось прочитать logcat из файла `${outputFile.absolutePath}`: ${t.message}"
            }
            addSystemMessage(message)
            HistoryLogger.log("Logcat read failed: ${t.message}")
            withContext(Dispatchers.Main) {
                indexingState = IndexingState(
                    isRunning = false,
                    currentStep = "",
                    totalChunks = null,
                    processedChunks = 0,
                    startedAtMillis = System.currentTimeMillis()
                )
            }
            return
        }

        addSystemMessage("Снял logcat с устройства `$serial`, файл: ${outputFile.absolutePath}. Запускаю индексацию…")
        runIndexingPipelineFromText(
            sourceText = logcatText,
            sourcePath = outputFile.absolutePath,
            onMessage = ::addSystemMessage
        )
    }

    suspend fun findRelatedPullRequestsBySummary(
        summaryText: String,
        pullRequests: List<GithubPullRequest>,
        apiKey: String,
        model: String
    ): String {
        val truncatedSummary = summaryText.take(2_000)
        val prsText = buildString {
            pullRequests.forEach { pr ->
                appendLine("#${pr.number}: ${pr.title}")
                appendLine("- labels: ${if (pr.labels.isEmpty()) "нет" else pr.labels.joinToString(", ")}")
                appendLine("- updatedAt: ${pr.updatedAt ?: "n/a"}")
                appendLine("- url: ${pr.htmlUrl}")
                pr.bodyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                    appendLine("- body: ${preview.take(200)}")
                }
                appendLine()
            }
        }

        val prompt = buildString {
            appendLine("У тебя есть summary Android bugreport и список открытых PR в GitHub.")
            appendLine()
            appendLine("Сначала summary:")
            appendLine()
            appendLine(truncatedSummary)
            appendLine()
            appendLine("Теперь список PR:")
            appendLine()
            appendLine(prsText.ifBlank { "(список пуст)" })
            appendLine("Твоя задача:")
            appendLine("1. Определи, есть ли среди PR те, которые, скорее всего, относятся к описанной в summary проблеме.")
            appendLine("2. Если да — перечисли такие PR (номера и названия) и кратко объясни, почему они релевантны.")
            appendLine("3. Если нет — напиши, что подходящих PR нет.")
            appendLine()
            append("Ответ верни в кратком Markdown-формате.")
        }

        HistoryLogger.log("LLM: analyzing ${pullRequests.size} PR(s) relevance to bugreport summary")

        val llmResult = withContext(Dispatchers.IO) {
            callOpenRouter(
                model = model,
                messages = listOf(
                    ORMessage(
                        "system",
                        "Ты ассистент, который сопоставляет багрепорты Android с открытыми PR и сообщает об их релевантности."
                    ),
                    ORMessage("user", prompt)
                ),
                forceJson = false,
                apiKeyOverride = apiKey,
                temperature = 0.3,
                maxTokens = 1_200,
                topP = 0.9
            )
        }

        return when (llmResult) {
            is OpenRouterResult.Success -> llmResult.content
            is OpenRouterResult.Failure -> throw IllegalStateException(
                llmResult.error.message ?: llmResult.error.rawBody ?: "LLM returned an error"
            )
        }
    }

    suspend fun runBugreportPipeline(mode: BugreportPipelineMode, onMessage: (String) -> Unit) {
        HistoryLogger.log("COMMAND: /orchestrate bugreport $mode")
        println("[Orchestrator] Starting bugreport pipeline for mode=$mode")
        onMessage("[Pipeline] Стартую пайплайн ADB → LLM → файл (режим: $mode)")

        val serial = when (mode) {
            is BugreportPipelineMode.SingleDevice -> mode.serial
        }

        onMessage("[Pipeline] Снимаю bugreport с устройства $serial через MCP ADB...")

        val bugreportResult = withContext(Dispatchers.IO) {
            withMcpAdbClient { conn ->
                conn.adbGetBugreport(serial)
            }
        }

        val bugreportPath = bugreportResult.filePath
        onMessage("[Pipeline] Bugreport сохранён в файле: $bugreportPath")
        HistoryLogger.log("MCP-ADB: bugreport stored at $bugreportPath")

        val localBugreportPath = copyBugreportToStorage(bugreportPath)

        val bugreportTextResult = try {
            loadBugreportTextResult(localBugreportPath)
        } catch (e: BugreportExtractionException) {
            println("[BugreportExtractor] Failed to prepare bugreport text file: ${e.message}")
            onMessage(e.message ?: "Не удалось подготовить bugreport из файла: $localBugreportPath")
            HistoryLogger.log("Bugreport extraction failed: ${e.message}")
            return
        } catch (t: Throwable) {
            println("[Orchestrator] Failed to read bugreport text: ${t.message}")
            onMessage("Не удалось прочитать текст bugreport из файла `$localBugreportPath`. Проверь, что файл существует и доступен.")
            HistoryLogger.log("Bugreport read failed: ${t.message}")
            return
        }
        println("[Orchestrator] Using bugreport text file for analysis: ${bugreportTextResult.source}")
        HistoryLogger.log("Bugreport text ready from ${bugreportTextResult.source}, length=${bugreportTextResult.originalLength}, used=${bugreportTextResult.usedLength}")

        val normalizedBugreportText = bugreportTextResult.text
        val isTruncated = bugreportTextResult.originalLength > bugreportTextResult.usedLength
        if (isTruncated) {
            println("[Orchestrator] Truncating bugreport text from ${bugreportTextResult.originalLength} to ${bugreportTextResult.usedLength} characters for LLM")
        }

        onMessage("Готово! Нашёл bugreport.txt в архиве и отправил в анализ.")

        val truncatedForStorage = trimBugreportForSummary(normalizedBugreportText, BUGREPORT_SUMMARY_PRIMARY_LIMIT)
        val userPrompt = buildBugreportSummaryPrompt(truncatedForStorage)

        val timestampForFiles = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val llmInputPath = StoragePaths.llmInputsDir.resolve("bugreport-${serial}-${timestampForFiles}.txt")
        runCatching {
            Files.writeString(llmInputPath, userPrompt, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }.onSuccess {
            println("[Storage] Saved LLM bugreport input to $llmInputPath (length=${userPrompt.length})")
            HistoryLogger.log("LLM input (bugreport) saved to $llmInputPath, length=${userPrompt.length}")
        }.onFailure {
            println("[Storage] Failed to store LLM bugreport input: ${it.message}")
            HistoryLogger.log("Failed to store LLM bugreport input: ${it.message}")
        }

        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            onMessage("[Pipeline] Ошибка: отсутствует OPENROUTER_API_KEY для вызова LLM.")
            HistoryLogger.log("LLM call skipped: missing OPENROUTER_API_KEY")
            return
        }

        println("[Orchestrator] Calling LLM bugreport summary, inputLength=${userPrompt.length}")
        HistoryLogger.log("LLM: calling bugreport summary, inputLength=${userPrompt.length}")
        onMessage("[Pipeline] Отправляю bugreport в LLM для генерации summary...")
        val summaryText = generateBugreportSummary(
            bugreportText = normalizedBugreportText,
            model = settings.openRouterModel,
            apiKey = apiKey,
            onMessage = onMessage
        ) ?: return

        println("[Orchestrator] Bugreport summary generated, length=${summaryText.length}")
        HistoryLogger.log("LLM: bugreport summary length=${summaryText.length}")

        onMessage("[Pipeline] Строю локальный индекс bugreport (чанки + эмбеддинги)...")
        HistoryLogger.log("BugreportIndex: building index for current bugreport")
        val indexSuccess = runIndexingPipelineFromText(
            sourceText = normalizedBugreportText,
            sourcePath = bugreportTextResult.source,
            onMessage = onMessage
        )
        if (!indexSuccess) return

        addSystemMessage("[Pipeline] Ищу связанные PR в GitHub через MCP...")

        val pullRequests = try {
            listOpenPullRequestsViaMcpGithub(limit = 10)
        } catch (e: Exception) {
            HistoryLogger.log("GitHub MCP list_open_pull_requests failed: ${e.message}")
            addSystemMessage("[Pipeline] Не удалось получить список PR из GitHub через MCP: ${e.message}")
            emptyList()
        }

        var prAnalysisForChat: String? = null
        if (pullRequests.isEmpty()) {
            addSystemMessage("[Pipeline] Открытых PR не нашлось или GitHub MCP недоступен.")
        } else {
            HistoryLogger.log("GitHub MCP returned ${pullRequests.size} open PR(s) for analysis")
            val prAnalysis = try {
                findRelatedPullRequestsBySummary(
                    summaryText = summaryText,
                    pullRequests = pullRequests,
                    apiKey = apiKey,
                    model = settings.openRouterModel
                )
            } catch (e: Exception) {
                HistoryLogger.log("GitHub PR analysis via LLM failed: ${e.message}")
                addSystemMessage("[Pipeline] Не удалось проанализировать PR через LLM: ${e.message}")
                null
            }

            prAnalysis?.let { analysis ->
                HistoryLogger.log("LLM: PR relevance analysis length=${analysis.length}")
                addSystemMessage("[Pipeline] Анализ PR завершён. Ниже результаты:")
                addSystemMessage(analysis)
                addAssistantMessage(
                    buildString {
                        appendLine("Я проанализировал открытые PR в GitHub и их возможную связь с этим багрепортом:")
                        appendLine()
                        appendLine(analysis)
                    }
                )
                prAnalysisForChat = analysis
            }
        }

        onMessage("[Pipeline] LLM сгенерировал summary, сохраняю в файл через MCP...")

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val fileName = "bugreport-${serial}-$timestamp-summary.md"

        println("[MCP-CLIENT] Calling fs.save_summary with fileName=$fileName")
        val saveResult = withContext(Dispatchers.IO) {
            withMcpGithubApiClient { client ->
                client.saveSummaryToFile(
                    fileName = fileName,
                    content = summaryText
                )
            }
        }

        val summaryFilePath = saveResult.filePath
        println("[MCP-CLIENT] fs.save_summary -> filePath=$summaryFilePath")
        HistoryLogger.log("fs.save_summary saved bugreport summary to $summaryFilePath")
        onMessage("[Pipeline] Summary по bugreport сохранён в файле: $summaryFilePath")
        onMessage("[Orchestration] Анализ проведён. Использован текстовый bugreport.")

        val shortSummary = summaryText
            .split("\n\n".toRegex())
            .take(2)
            .joinToString("\n\n")
            .ifBlank { summaryText.take(600) }

        addAssistantMessage(
            buildString {
                appendLine("Готово! Я:")
                appendLine("1. Снял bugreport с устройства `$serial` через MCP ADB.")
                appendLine("2. Проанализировал его с помощью LLM.")
                appendLine("3. Сохранил summary в файл:")
                appendLine("   $summaryFilePath")
                appendLine()
                prAnalysisForChat?.let { analysis ->
                    appendLine("Я также проанализировал открытые PR в GitHub и их связь с багрепортом:")
                    appendLine()
                    appendLine(analysis)
                    appendLine()
                }
                appendLine("Краткое summary:")
                append(shortSummary)
            }
        )
    }


    fun handleBugreportPipelineCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/orchestrate", ignoreCase = true)) return false

        val payload = trimmed.removePrefix("/orchestrate").trim()
        if (!payload.startsWith("bugreport", ignoreCase = true)) {
            addSystemMessage("Некорректный формат команды /orchestrate. Используй: /orchestrate bugreport <serial>.")
            return true
        }

        val serial = payload.removePrefix("bugreport").trim()
        if (serial.isBlank()) {
            addSystemMessage("Укажи serial устройства: /orchestrate bugreport <serial>")
            return true
        }

        scope.launch {
            isSending = true
            try {
                runBugreportPipeline(BugreportPipelineMode.SingleDevice(serial)) { message ->
                    addSystemMessage(message)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                addSystemMessage("[Pipeline] Ошибка пайплайна bugreport: ${t.message ?: "см. логи"}")
            } finally {
                isSending = false
            }
        }

        return true
    }

    suspend fun runPrSummaryPipeline(
        mode: PipelineMode,
        onMessage: (String) -> Unit
    ) {
        try {
            HistoryLogger.log("COMMAND: /pipeline $mode")
            onMessage(
                when (mode) {
                    PipelineMode.AllOpenPrs -> "[Pipeline] Запускаю пайплайн summary по всем открытым PR..."
                    is PipelineMode.SinglePr -> "[Pipeline] Запускаю пайплайн summary по PR #${mode.number}..."
                }
            )
            println("[Pipeline] Starting pipeline: mode=$mode")

            val prListForSummary: List<McpPullRequest>
            val diffByPrNumber: Map<Int, String>

            when (mode) {
                PipelineMode.AllOpenPrs -> {
                    val prList = withContext(Dispatchers.IO) {
                        withMcpGithubApiClient { client ->
                            client.listPullRequests(state = "open")
                        }
                    }
                    val limitedPrList = prList.take(5)
                    println("[Pipeline] MCP returned ${prList.size} open PR(s), using ${limitedPrList.size} for summary")
                    onMessage("[Pipeline] Получил ${limitedPrList.size} открытых PR через MCP.")
                    prListForSummary = limitedPrList
                    diffByPrNumber = if (limitedPrList.isEmpty()) {
                        emptyMap()
                    } else {
                        withContext(Dispatchers.IO) {
                            withMcpGithubApiClient { client ->
                                limitedPrList.associate { pr ->
                                    pr.number to client.getPrDiff(number = pr.number)
                                }
                            }
                        }
                    }
                }

                is PipelineMode.SinglePr -> {
                    val prNumber = mode.number
                    val pr = withContext(Dispatchers.IO) {
                        withMcpGithubApiClient { client ->
                            client.listPullRequests(state = "open")
                                .find { it.number == prNumber }
                        }
                    }
                    if (pr == null) {
                        onMessage("[Pipeline] PR #$prNumber не найден среди открытых.")
                        println("[Pipeline] PR #$prNumber not found")
                        return
                    }
                    val diff = withContext(Dispatchers.IO) {
                        withMcpGithubApiClient { client ->
                            client.getPrDiff(number = prNumber)
                        }
                    }
                    prListForSummary = listOf(pr)
                    diffByPrNumber = mapOf(pr.number to diff)
                    onMessage("[Pipeline] Нашёл PR #$prNumber и получил его diff через MCP.")
                    println("[Pipeline] Got diff for PR #$prNumber")
                }
            }

            if (prListForSummary.isEmpty()) {
                onMessage("[Pipeline] Открытых PR не найдено.")
                return
            }

            val maxDiffChars = 6000
            fun limitDiff(text: String): String {
                return if (text.length > maxDiffChars) {
                    text.take(maxDiffChars) + "\n\n... [обрезано]"
                } else {
                    text
                }
            }

            val llmInput = buildString {
                appendLine("Ты — ассистент, который делает техническое summary pull request-ов в GitHub репозитории AOSPBugreportAnalyzer.")
                appendLine("Сделай краткое, структурированное summary в формате Markdown.")
                appendLine("Обязательно выдели:")
                appendLine("- краткое описание изменений;")
                appendLine("- возможные риски;")
                appendLine("- что особенно стоит проверить при ревью.")
                appendLine()
                when (mode) {
                    PipelineMode.AllOpenPrs -> {
                        appendLine("Вот список открытых PR:")
                        prListForSummary.forEach { pr ->
                            appendLine("- #${pr.number} [${pr.state}] ${pr.title} (${pr.url})")
                            diffByPrNumber[pr.number]?.takeIf { it.isNotBlank() }?.let { diff ->
                                appendLine()
                                appendLine("Diff для #${pr.number}:")
                                appendLine(limitDiff(diff))
                                appendLine()
                            }
                        }
                    }

                    is PipelineMode.SinglePr -> {
                        val pr = prListForSummary.first()
                        appendLine("Вот данные по PR #${pr.number}:")
                        appendLine("${pr.title} (${pr.url})")
                        diffByPrNumber[pr.number]?.takeIf { it.isNotBlank() }?.let { diff ->
                            appendLine()
                            appendLine("Diff:")
                            appendLine(limitDiff(diff))
                        }
                    }
                }
            }

            val prTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
                .format(Instant.now())
            val llmInputFileName = when (mode) {
                is PipelineMode.SinglePr -> "pr-${mode.number}-summary-input.md"
                PipelineMode.AllOpenPrs -> "prs-open-$prTimestamp-summary-input.md"
            }
            val prLlmInputPath = StoragePaths.llmInputsDir.resolve(llmInputFileName)
            runCatching {
                Files.writeString(prLlmInputPath, llmInput, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }.onSuccess {
                println("[Storage] Saved LLM PR input to $prLlmInputPath (length=${llmInput.length})")
                HistoryLogger.log("LLM input (PR summary) saved to $prLlmInputPath, length=${llmInput.length}")
            }.onFailure {
                println("[Storage] Failed to store LLM PR input: ${it.message}")
                HistoryLogger.log("Failed to store LLM PR input: ${it.message}")
            }

            val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
            if (apiKey.isNullOrBlank()) {
                onMessage("[Pipeline] Ошибка: отсутствует OPENROUTER_API_KEY для вызова LLM.")
                return
            }

            println("[Pipeline] Calling LLM to generate summary...")
            HistoryLogger.log("LLM: calling PR summary, inputLength=${llmInput.length}")
            val llmResult = withContext(Dispatchers.IO) {
                callOpenRouter(
                    model = settings.openRouterModel,
                    messages = listOf(
                        ORMessage(
                            "system",
                            "Ты ассистент, который делает краткое, структурированное summary PR в формате Markdown."
                        ),
                        ORMessage("user", llmInput)
                    ),
                    forceJson = false,
                    apiKeyOverride = apiKey,
                    temperature = 0.2
                )
            }
            val summaryText = when (llmResult) {
                is OpenRouterResult.Success -> llmResult.content
                is OpenRouterResult.Failure -> {
                    val message = llmResult.error.message ?: llmResult.error.rawBody ?: "неизвестная ошибка"
                    onMessage("[Pipeline] Ошибка при вызове LLM: $message")
                    return
                }
            }
            println("[Pipeline] LLM summary generated, length=${summaryText.length}")
            onMessage("[Pipeline] LLM сгенерировал summary, сохраняю в файл через MCP...")
            HistoryLogger.log("LLM: PR summary generated, length=${summaryText.length}")

            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())
                .format(Instant.now())
            val fileName = when (mode) {
                is PipelineMode.SinglePr -> "pr-${mode.number}-summary.md"
                PipelineMode.AllOpenPrs -> "prs-open-summary-$timestamp.md"
            }
            println("[MCP-CLIENT] Calling fs.save_summary with fileName=$fileName")
            val saveResult = withContext(Dispatchers.IO) {
                withMcpGithubApiClient { client ->
                    client.saveSummaryToFile(
                        fileName = fileName,
                        content = summaryText
                    )
                }
            }
            val filePath = saveResult.filePath
            println("[MCP-CLIENT] fs.save_summary -> filePath=$filePath")
            HistoryLogger.log("fs.save_summary saved PR summary to $filePath")
            println("[Pipeline] Summary saved via MCP to: $filePath")
            onMessage(
                buildString {
                    appendLine("Готово! Я сделал summary по PR и сохранил файл через MCP.")
                    appendLine()
                    appendLine("Путь к файлу:")
                    appendLine(filePath)
                }
            )
            addAssistantMessage(
                buildString {
                    appendLine("Краткое summary (Markdown):")
                    appendLine()
                    append(summaryText)
                }
            )
        } catch (e: Throwable) {
            println("[Pipeline] Error during pipeline: ${e.message}")
            e.printStackTrace()
            onMessage("[Pipeline] Ошибка при выполнении пайплайна: ${e.message ?: "см. логи"}")
        }
    }


    suspend fun requestGithubPrSummaryFromLlm(prs: List<McpPullRequest>): String {
        if (prs.isEmpty()) {
            return "Сейчас нет открытых PR — все задачи закрыты 👍"
        }
        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            return "Не удалось получить summary от LLM: отсутствует OPENROUTER_API_KEY."
        }
        val listText = buildString {
            appendLine("Вот список PR:")
            prs.forEachIndexed { index, pr ->
                appendLine("${index + 1}) #${pr.number} [${pr.state}] ${pr.title} (${pr.url})")
            }
            appendLine()
            append("Сделай короткий отчёт в 3–5 пунктах.")
        }
        val messages = listOf(
            ORMessage(
                "system",
                "Ты ассистент, который готовит краткий отчёт о состоянии open PR в GitHub. Пиши по-русски. Дай краткое summary: сколько PR, какие ключевые, что стоит сделать."
            ),
            ORMessage("user", listText)
        )
        val result = withContext(Dispatchers.IO) {
            callOpenRouter(
                model = settings.openRouterModel,
                messages = messages,
                forceJson = false,
                apiKeyOverride = apiKey,
                temperature = 0.2
            )
        }
        return when (result) {
            is OpenRouterResult.Success -> result.content.ifBlank {
                "LLM вернул пустой ответ на запрос summary."
            }
            is OpenRouterResult.Failure -> {
                val message = result.error.message ?: result.error.rawBody ?: "неизвестная ошибка"
                "Не удалось получить summary от LLM (ошибка: $message). Попробуйте позже."
            }
        }
    }

    fun formatMcpError(message: String, throwable: Throwable? = null): String {
        return if (throwable is McpGithubClientException && throwable.isConnectionError) {
            "MCP: не удалось подключиться к GitHub серверу. Проверьте GITHUB_TOKEN и путь к серверу. Детали: ${throwable.message}"
        } else if (throwable != null) {
            "$message (детали: ${throwable.message})"
        } else {
            message
        }
    }

    fun handleMcpListPullRequests() {
        scope.launch {
            isSending = true
            try {
                addSystemMessage("MCP: запрашиваю список PR через GitHub MCP сервер...")
                val prs = withContext(Dispatchers.IO) {
                    withMcpGithubApiClient { client ->
                        client.listPullRequests()
                    }
                }
                if (prs.isEmpty()) {
                    addSystemMessage("MCP: открытых PR не найдено.")
                } else {
                    val message = buildString {
                        appendLine("MCP: найдено ${prs.size} PR:")
                        prs.forEach { pr ->
                            appendLine("- #${pr.number} [${pr.state}] ${pr.title} (${pr.url})")
                        }
                    }.trimEnd()
                    addSystemMessage(message)
                }
            } catch (t: Throwable) {
                addSystemMessage(formatMcpError("MCP: ошибка при получении списка PR", t))
            } finally {
                isSending = false
            }
        }
    }

    fun handleMcpGetPrDiff(number: Int) {
        scope.launch {
            isSending = true
            try {
                addSystemMessage("MCP: запрашиваю diff для PR #$number...")
                val diff = withContext(Dispatchers.IO) {
                    withMcpGithubApiClient { client ->
                        client.getPrDiff(number = number)
                    }
                }
                if (diff.isBlank()) {
                    addSystemMessage("MCP: пустой diff для PR #$number.")
                } else {
                    val maxChars = 4000
                    val shownDiff = if (diff.length > maxChars) {
                        diff.take(maxChars) + "\n\n... [обрезано]"
                    } else {
                        diff
                    }
                    val message = buildString {
                        appendLine("MCP: unified diff для PR #$number:")
                        appendLine("```diff")
                        appendLine(shownDiff)
                        append("```")
                    }
                    addAssistantMessage(message)
                }
            } catch (t: Throwable) {
                addSystemMessage(formatMcpError("MCP: ошибка при получении diff для PR #$number", t))
            } finally {
                isSending = false
            }
        }
    }

    fun handlePipelineCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/pipeline")) return false

        val payload = trimmed.removePrefix("/pipeline").trim()
        val mode = when {
            payload.equals("prs", ignoreCase = true) -> PipelineMode.AllOpenPrs
            payload.startsWith("pr", ignoreCase = true) -> {
                val number = payload.removePrefix("pr").trim().toIntOrNull()
                number?.let { PipelineMode.SinglePr(it) }
            }

            else -> null
        }

        if (mode == null) {
            addSystemMessage("Некорректный формат команды /pipeline. Используй /pipeline prs или /pipeline pr <номер>.")
            return true
        }

        scope.launch {
            isSending = true
            try {
                runPrSummaryPipeline(mode) { message ->
                    addSystemMessage(message)
                }
            } finally {
                isSending = false
            }
        }
        return true
    }

    fun handleAdbCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.equals("/adb devices", ignoreCase = true)) {
            HistoryLogger.log("COMMAND: /adb devices")
            addSystemMessage("[Orchestration] Запрашиваю список adb-устройств через MCP...")
            scope.launch {
                isSending = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        withMcpAdbClient { conn ->
                            conn.adbListDevices()
                        }
                    }
                    HistoryLogger.log("MCP-ADB: listed ${result.devices.size} device(s)")
                    val message = if (result.devices.isEmpty()) {
                        "[Orchestration] adb-устройства не найдены."
                    } else {
                        buildString {
                            appendLine("Найдены adb-устройства:")
                            appendLine()
                            result.devices.forEach { device ->
                                appendLine("- serial: ${device.serial}")
                                appendLine("  state: ${device.state}")
                                device.model?.let { appendLine("  model: $it") }
                                device.device?.let { appendLine("  device: $it") }
                                appendLine()
                            }
                        }.trimEnd()
                    }
                    addAssistantMessage(message)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    addSystemMessage("[Orchestration] Ошибка при запросе устройств adb: ${t.message ?: "неизвестная ошибка"}")
                } finally {
                    isSending = false
                }
            }
            return true
        }
        val bugreportMatch = Regex("^/adb\\s+bugreport\\s+(\\S+)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
        if (bugreportMatch != null) {
            val serial = bugreportMatch.groupValues[1]
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
            val outputFile = StoragePaths.bugreportsDir.resolve("bugreport-$serial-$timestamp.zip").toFile()
            HistoryLogger.log("COMMAND: /adb bugreport $serial")
            scope.launch {
                isSending = true
                try {
                    StoragePaths.ensureDirs()
                    Files.createDirectories(outputFile.toPath().parent)
                    addSystemMessage("Снимаю bugreport с устройства `$serial`, сохраняю в ${outputFile.absolutePath}...")
                    val result = withContext(Dispatchers.IO) { AdbHelper.runBugreport(serial, outputFile) }
                    if (result.exitCode != 0) {
                        val details = result.stderrPreview.takeIf { it.isNotBlank() }
                            ?.let { "\nПодробности ошибки:\n$it" }
                            ?: ""
                        addSystemMessage("ADB-команда завершилась с ошибкой (код ${result.exitCode}). Проверьте устройство и доступность adb.$details")
                        HistoryLogger.log("ADB bugreport failed with exitCode=${result.exitCode}, stderr=${result.stderrPreview}")
                        return@launch
                    }
                    if (!outputFile.exists() || outputFile.length() < 10) {
                        addSystemMessage("Bugreport получен, но выглядит пустым или повреждённым.")
                        HistoryLogger.log("ADB bugreport produced empty file at ${outputFile.absolutePath}")
                        return@launch
                    }
                    indexBugreportFromFile(serial, outputFile)
                } catch (ioe: IOException) {
                    addSystemMessage("adb не найден. Добавьте adb в PATH или установите Android Platform Tools.")
                    HistoryLogger.log("ADB bugreport failed: adb not found (${ioe.message})")
                } catch (t: Throwable) {
                    addSystemMessage("Ошибка при выполнении adb bugreport: ${t.message}")
                    HistoryLogger.log("ADB bugreport failed: ${t.message}")
                } finally {
                    isSending = false
                }
            }
            return true
        }
        if (trimmed.startsWith("/adb bugreport", ignoreCase = true)) {
            addSystemMessage("Неверный формат. Используй: `/adb bugreport <serial>`")
            return true
        }

        val logcatMatch = Regex("^/adb\\s+logcat\\s+(\\S+)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
        if (logcatMatch != null) {
            val serial = logcatMatch.groupValues[1]
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
            val outputFile = StoragePaths.logcatDir.resolve("logcat-$serial-$timestamp.txt").toFile()
            HistoryLogger.log("COMMAND: /adb logcat $serial")
            scope.launch {
                isSending = true
                try {
                    StoragePaths.ensureDirs()
                    Files.createDirectories(outputFile.toPath().parent)
                    addSystemMessage("Снимаю logcat с устройства `$serial`, сохраняю в ${outputFile.absolutePath}...")
                    val result = withContext(Dispatchers.IO) { AdbHelper.runLogcatDump(serial, outputFile) }
                    if (result.exitCode != 0) {
                        val details = result.stderrPreview.takeIf { it.isNotBlank() }
                            ?.let { "\nПодробности ошибки:\n$it" }
                            ?: ""
                        addSystemMessage("ADB-команда завершилась с ошибкой (код ${result.exitCode}). Проверьте устройство и доступность adb.$details")
                        HistoryLogger.log("ADB logcat failed with exitCode=${result.exitCode}, stderr=${result.stderrPreview}")
                        return@launch
                    }
                    if (!outputFile.exists() || outputFile.length() < 10) {
                        addSystemMessage("Logcat получен, но выглядит пустым или повреждённым.")
                        HistoryLogger.log("ADB logcat produced empty file at ${outputFile.absolutePath}")
                        return@launch
                    }
                    indexLogcatFromFile(serial, outputFile)
                } catch (ioe: IOException) {
                    addSystemMessage("adb не найден. Добавьте adb в PATH или установите Android Platform Tools.")
                    HistoryLogger.log("ADB logcat failed: adb not found (${ioe.message})")
                } catch (t: Throwable) {
                    addSystemMessage("Ошибка при выполнении adb logcat: ${t.message}")
                    HistoryLogger.log("ADB logcat failed: ${t.message}")
                } finally {
                    isSending = false
                }
            }
            return true
        }
        if (trimmed.startsWith("/adb logcat", ignoreCase = true)) {
            addSystemMessage("Неверный формат. Используй: `/adb logcat <serial>`")
            return true
        }

        return false
    }

    fun handleMcpCommand(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (trimmed.equals("/mcp prs", ignoreCase = true)) {
            handleMcpListPullRequests()
            return true
        }

        val diffPrefix = "/mcp diff"
        if (trimmed.startsWith(diffPrefix, ignoreCase = true)) {
            val numberPart = trimmed.removePrefix(diffPrefix).trim()
            val number = numberPart.toIntOrNull()
            if (number == null) {
                addSystemMessage("Неверный номер PR. Используйте: /mcp diff <number>")
            } else {
                handleMcpGetPrDiff(number)
            }
            return true
        }

        if (
            lower.contains("покажи pr") ||
            lower.contains("какие pr сейчас") ||
            lower.contains("какие pr") ||
            lower.contains("пул реквест") ||
            (lower.contains("pr") && lower.contains("ревью"))
        ) {
            handleMcpListPullRequests()
            return true
        }

        if (
            lower.contains("diff") ||
            lower.contains("дифф") ||
            lower.contains("изменения") ||
            lower.contains("изменения в") ||
            lower.contains("изменения по")
        ) {
            val numberRegex = Regex("""\b(\d{1,5})\b""")
            val number = numberRegex.find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (number != null) {
                handleMcpGetPrDiff(number)
                return true
            }
        }

        return false
    }

    fun handlePlainMode(
        history: List<ChatMessage>,
        userText: String,
        apiKey: String,
        strictJson: Boolean,
        model: String
    ) {
        scope.launch(Dispatchers.IO) {
            val requestMessages = buildChatRequestMessages(history, strictJson)
            val result = callOpenRouter(
                model = model,
                messages = requestMessages,
                forceJson = strictJson,
                apiKeyOverride = apiKey,
                temperature = 0.0
            )
            val agentMessage = when (result) {
                is OpenRouterResult.Success -> ChatMessage(
                    author = "Эксперт",
                    role = AuthorRole.EXPERT,
                    text = result.content,
                    metrics = result.stats.toMsgMetrics()
                )

                is OpenRouterResult.Failure -> {
                    val errorMessage = result.error.toUserMessage()
                    ChatMessage(
                        author = "Эксперт",
                        role = AuthorRole.EXPERT,
                        text = errorResponse(strictJson, errorMessage),
                        metrics = MsgMetrics(null, null, null, null, null, error = errorMessage)
                    )
                }
            }
            println("[AgentMemory] before persistTurnAndFetch: result=${result::class.simpleName}, agentText='${agentMessage.text.take(80)}'")
            withContext(Dispatchers.Main) {
                appendMessage(agentMessage)
                maybeCompressHistory()
                isSending = false
            }
            val updatedEntries = runCatching {
                persistTurnAndFetch(
                    userMessage = userText,
                    assistantMessage = agentMessage.text,
                    stats = (result as? OpenRouterResult.Success)?.stats?.toMessageStats(),
                    tags = listOf("chat"),
                    isSummaryTurn = false
                )
            }
                .onFailure {
                    println("[AgentMemory] persistTurnAndFetch failed: ${it.message}")
                    it.printStackTrace()
                }
                .getOrNull()
            if (updatedEntries != null) {
                withContext(Dispatchers.Main) {
                    refreshMemory(updatedEntries)
                }
            }
        }
    }

    fun handleRagOnlyMode(
        userText: String,
        appendMessage: (ChatMessage) -> Unit,
        addSystemMessage: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val index = loadLatestBugreportIndex()?.toEmbeddingIndex()
            if (index == null) {
                withContext(Dispatchers.Main) {
                    addSystemMessage("Не найден локальный индекс bugreport. Сначала постройте индекс.")
                    isSending = false
                }
                return@launch
            }
            val result = runCatching { askLlmWithRag(userText, index, topK = 5) }
            val responseMessage = result.fold(
                onSuccess = {
                    ChatMessage(
                        author = "Эксперт",
                        role = AuthorRole.EXPERT,
                        text = "${it.answer}\n\nРежим: RAG",
                        metrics = null
                    )
                },
                onFailure = {
                    ChatMessage(
                        author = "Эксперт",
                        role = AuthorRole.EXPERT,
                        text = "Ошибка RAG: ${it.message}",
                        metrics = MsgMetrics(null, null, null, null, null, error = it.message)
                    )
                }
            )
            val chunkMessage = result.getOrNull()?.let {
                ChatMessage(
                    author = "RAG",
                    role = AuthorRole.EXPERT,
                    text = formatChunks(it.usedChunks),
                    metrics = null
                )
            }
            withContext(Dispatchers.Main) {
                appendMessage(responseMessage)
                chunkMessage?.let { appendMessage(it) }
                isSending = false
            }
        }
    }

    fun handleCompareMode(
        userText: String,
        appendMessage: (ChatMessage) -> Unit,
        addSystemMessage: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val index = loadLatestBugreportIndex()?.toEmbeddingIndex()
            if (index == null) {
                withContext(Dispatchers.Main) {
                    addSystemMessage("Не найден локальный индекс bugreport. Сначала постройте индекс.")
                    isSending = false
                }
                return@launch
            }
            val comparison = runCatching { compareRagAndPlain(userText, index, topK = 5) }
            comparison.onFailure { err ->
                withContext(Dispatchers.Main) {
                    addSystemMessage("Ошибка сравнения RAG: ${err.message}")
                    isSending = false
                }
            }
            val result = comparison.getOrNull() ?: return@launch
            val plainMsg = ChatMessage(
                author = "Эксперт",
                role = AuthorRole.EXPERT,
                text = "Ответ без RAG:\n${result.plainAnswer}",
                metrics = null
            )
            val ragMsg = ChatMessage(
                author = "Эксперт",
                role = AuthorRole.EXPERT,
                text = "Ответ с RAG:\n${result.ragAnswer}",
                metrics = null
            )
            val chunkMsg = ChatMessage(
                author = "RAG",
                role = AuthorRole.EXPERT,
                text = formatChunks(result.usedChunks),
                metrics = null
            )
            withContext(Dispatchers.Main) {
                appendMessage(plainMsg)
                appendMessage(ragMsg)
                appendMessage(chunkMsg)
                isSending = false
            }
        }
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || isSending) return

        if (handleBugreportPipelineCommand(text)) {
            input = ""
            return
        }

        if (handlePipelineCommand(text)) {
            input = ""
            return
        }

        if (handleAdbCommand(text)) {
            input = ""
            return
        }

        if (handleMcpCommand(text)) {
            input = ""
            return
        }

        println("[AgentMemory] sendMessage: userText='${text.take(80)}'")
        val userMessage = ChatMessage(
            author = "Вы",
            role = AuthorRole.USER,
            text = text,
            metrics = MsgMetrics(null, null, null, null, null, error = null)
        )
        val newHistory = messages + userMessage
        messages = newHistory
        input = ""
        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            addSystemMessage("Укажите OPENROUTER_API_KEY, чтобы Эксперт мог отвечать.")
            return
        }
        if (!settings.ragEnabled && ragMode != RagMode.PLAIN) {
            addSystemMessage("RAG выключен в настройках, использую обычный ответ без индекса.")
            ragMode = RagMode.PLAIN
        }
        configureRagProviders(apiKey, settings.openRouterModel)
        isSending = true
        val strictJson = settings.strictJsonEnabled
        val currentHistory = newHistory
        when (ragMode) {
            RagMode.PLAIN -> handlePlainMode(
                history = currentHistory,
                userText = text,
                apiKey = apiKey,
                strictJson = strictJson,
                model = settings.openRouterModel
            )

            RagMode.RAG_ONLY -> handleRagOnlyMode(
                userText = text,
                appendMessage = { appendMessage(it) },
                addSystemMessage = { addSystemMessage(it) }
            )

            RagMode.COMPARE -> handleCompareMode(
                userText = text,
                appendMessage = { appendMessage(it) },
                addSystemMessage = { addSystemMessage(it) }
            )
        }
    }

    LaunchedEffect(Unit) {
        if ((settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey).isNullOrBlank()) {
            appendMessage(
                ChatMessage(
                    author = "Эксперт",
                    role = AuthorRole.EXPERT,
                    text = "Укажите OPENROUTER_API_KEY, чтобы Эксперт мог отвечать.",
                    metrics = MsgMetrics(null, null, null, null, null, error = null)
                )
            )
        }
    }

    when (screen) {
        Screen.MAIN -> {
            Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("AOSP Bugreport Analyzer — Чат") },
                                actions = {
                                    Text(
                                        text = "Память: $memoryCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    TextButton(onClick = { screen = Screen.SETTINGS }) { Text("Настройки") }
                                }
                            )
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
                    if (compressionStats.isNotEmpty()) {
                        CompressionStatsBlock(compressionStats)
                    }
                    if (indexingState.isRunning) {
                        IndexingProgress(indexingState)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Режим ответа:", style = MaterialTheme.typography.bodyMedium)
                        val modes = if (settings.ragEnabled) RagMode.values().toList() else listOf(RagMode.PLAIN)
                        modes.forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = ragMode == mode,
                                    onClick = { ragMode = mode },
                                    enabled = settings.ragEnabled || mode == RagMode.PLAIN
                                )
                                Text(
                                    text = when (mode) {
                                        RagMode.PLAIN -> "Без RAG"
                                        RagMode.RAG_ONLY -> "RAG"
                                        RagMode.COMPARE -> "Сравнить"
                                    }
                                )
                            }
                        }
                        if (!settings.ragEnabled) {
                            Text("RAG выключен", style = MaterialTheme.typography.labelSmall)
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
        Screen.SETTINGS -> {
            SettingsScreen(
                settings = settings,
                onChange = { settings = it },
                onClose = { screen = Screen.MAIN },
                memoryCount = memoryCount,
                onClearMemory = { clearExternalMemory() }
            )
        }
    }
}

@Composable
private fun CompressionStatsBlock(stats: List<CompressionStats>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(
            text = "Сжатие истории",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        stats.takeLast(3).reversed().forEach { entry ->
            Text(
                text = "Сводка #${entry.summaryGroupId}: ${entry.messagesCount} сообщений → ${entry.charsBefore}→${entry.charsAfter} символов (${entry.reductionPercent}% экономии). summary токены: prompt=${entry.summaryPromptTokens ?: "—"}, completion=${entry.summaryCompletionTokens ?: "—"}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun IndexingProgress(state: IndexingState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(state.currentStep.ifBlank { "Идёт индексация..." })
        val progress = state.totalChunks?.takeIf { it > 0 }?.let { total ->
            state.processedChunks.toFloat() / total.toFloat()
        }
        if (progress != null) {
            LinearProgressIndicator(progress = progress)
            Text("${state.processedChunks}/${state.totalChunks}", style = MaterialTheme.typography.labelSmall)
        } else {
            LinearProgressIndicator()
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
                if (message.isSummary) {
                    SummaryBubble(message)
                } else {
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
                }
                Divider(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun SummaryBubble(message: ChatMessage) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = message.summaryGroupId?.let { "Сводка диалога #$it" } ?: "Сводка диалога",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MetricsLine(role: AuthorRole, metrics: MsgMetrics?) {
    val m = metrics ?: MsgMetrics(null, null, null, null, null, error = null)
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!m.error.isNullOrBlank()) {
            Text(
                text = m.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onClose: () -> Unit,
    memoryCount: Int,
    onClearMemory: () -> Unit
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сжатие истории (summary)",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = draft.useCompression,
                    onCheckedChange = { draft = draft.copy(useCompression = it) }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Использовать RAG (поиск по индексу)",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = draft.ragEnabled,
                    onCheckedChange = { draft = draft.copy(ragEnabled = it) }
                )
            }
            OutlinedTextField(
                value = draft.indexingTimeoutMinutes.toString(),
                onValueChange = { value ->
                    val minutes = value.toIntOrNull() ?: draft.indexingTimeoutMinutes
                    draft = draft.copy(indexingTimeoutMinutes = minutes.coerceAtLeast(1))
                },
                label = { Text("Максимальная длительность индексации (мин)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.weight(1f))
            Divider()
            Text(
                text = "Во внешней памяти: $memoryCount ходов",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onClearMemory,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Очистить внешнюю память")
            }
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

private fun AgentMemoryEntry.toChatMessages(): List<ChatMessage> {
    val timestamp = parseEntryTimestamp(createdAt)
    if (meta?.isSummaryTurn == true) {
        return listOf(
            ChatMessage(
                id = "memory-summary-$id",
                author = "Summary",
                role = AuthorRole.SUMMARY,
                text = assistantMessage,
                timestamp = timestamp,
                metrics = null,
                isSummary = true,
                summaryGroupId = null
            )
        )
    }
    val entries = mutableListOf<ChatMessage>()
    if (userMessage.isNotBlank()) {
        entries += ChatMessage(
            id = "memory-user-$id",
            author = "USER",
            role = AuthorRole.USER,
            text = userMessage,
            timestamp = timestamp,
            metrics = MsgMetrics(null, null, null, null, null, error = null)
        )
    }
    if (assistantMessage.isNotBlank()) {
        entries += ChatMessage(
            id = "memory-expert-$id",
            author = "Эксперт",
            role = AuthorRole.EXPERT,
            text = assistantMessage,
            timestamp = timestamp + 1,
            metrics = meta.toMsgMetrics()
        )
    }
    return entries
}

private fun MemoryMeta?.toMsgMetrics(): MsgMetrics? {
    val meta = this ?: return null
    return MsgMetrics(
        promptTokens = meta.promptTokens,
        completionTokens = meta.completionTokens,
        totalTokens = meta.totalTokens,
        elapsedMs = meta.durationMs,
        costUsd = meta.costUsd,
        error = null
    )
}

private fun SummaryResult.toMessageStats(): MessageStats =
    MessageStats(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = if (promptTokens != null && completionTokens != null) {
            promptTokens + completionTokens
        } else {
            null
        }
    )

private fun parseEntryTimestamp(value: String): Long =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
