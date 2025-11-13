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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private enum class Screen { MAIN, SETTINGS }

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
    val costUsd: Double?,
    val error: String? = null
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
    val openRouterModel: String = "meta-llama/llama-4-maverick:free",
    val strictJsonEnabled: Boolean = false
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
    temperature: Double
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
            metrics = MsgMetrics(null, null, null, null, null, error = null)
        )
        val newHistory = messages + userMessage
        messages = newHistory
        input = ""
        val apiKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
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
            val agentMessage = when (result) {
                is OpenRouterResult.Success -> ChatMessage(
                    author = "Agent",
                    role = AuthorRole.AGENT,
                    text = result.content,
                    metrics = result.stats.toMsgMetrics()
                )
                is OpenRouterResult.Failure -> {
                    val errorMessage = result.error.toUserMessage()
                    ChatMessage(
                        author = "Agent",
                        role = AuthorRole.AGENT,
                        text = errorResponse(strictJson, errorMessage),
                        metrics = MsgMetrics(null, null, null, null, null, error = errorMessage)
                    )
                }
            }
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
