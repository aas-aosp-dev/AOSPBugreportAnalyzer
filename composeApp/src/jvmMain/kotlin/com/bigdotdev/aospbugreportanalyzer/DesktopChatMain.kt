package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private enum class Screen { MAIN_CHAT, DEV_SETTINGS }

private data class DevProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val temperature: Double
)

private data class DevSettings(
    val devs: MutableList<DevProfile> = mutableListOf()
)

private data class ChatLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val author: String,
    val text: String,
    val ts: Long = System.currentTimeMillis()
)

data class AppSettings(
    val openRouterApiKey: String = System.getenv("OPENROUTER_API_KEY") ?: "",
    val openRouterModel: String = "openai/gpt-4o-mini",
    val strictJsonEnabled: Boolean = false
)

private data class ORMessage(val role: String, val content: String)

private data class ORRequest(
    val model: String,
    val messages: List<ORMessage>,
    val response_format: Map<String, String>? = null,
    val temperature: Double
)

private object OpenRouterConfig {
    const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    val apiKey: String? get() = System.getenv("OPENROUTER_API_KEY")
    const val referer: String = "http://localhost"
    const val title: String = "AOSPBugreportAnalyzer"
}

private val httpClient: HttpClient = HttpClient.newHttpClient()

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(timestamp: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(timestamp))

private fun defaultDevs() = mutableListOf(
    DevProfile(
        name = "Иван",
        prompt = "Суперпедантичный senior Android. Любит доказательства, RFC, ссылки на AOSP. Пишет сухо и точно.",
        temperature = 0.0
    ),
    DevProfile(
        name = "Максим",
        prompt = "Системный архитектор. Сначала продумывает архитектуру/границы, потом детали. Баланс между качеством и сроками.",
        temperature = 0.0
    ),
    DevProfile(
        name = "Геннадий",
        prompt = "Практик. Ненавидит избыточные абстракции. Предлагает простой и быстрый путь, отмечает риски.",
        temperature = 0.0
    )
)

private fun buildMessages(dev: DevProfile, userPrompt: String): List<ORMessage> = listOf(
    ORMessage("system", "Be concise and precise."),
    ORMessage("system", "Persona: ${dev.prompt}"),
    ORMessage("user", userPrompt)
)

private fun callOpenRouter(
    model: String,
    messages: List<ORMessage>,
    forceJson: Boolean,
    apiKeyOverride: String?,
    temperatureOverride: Double
): String {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return "Error: openrouter api key missing"
    }

    val request = ORRequest(
        model = model,
        messages = messages,
        response_format = if (forceJson) mapOf("type" to "json_object") else null,
        temperature = temperatureOverride
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
        append(request.temperature)
        append('}')
    }

    println("OpenRouter body: " + body.take(800))

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
            "Error: openrouter http ${response.statusCode()}"
        } else {
            extractContentFromOpenAIJson(response.body()) ?: "Error: openrouter empty content"
        }
    } catch (t: Throwable) {
        "Error: ${t.message ?: t::class.simpleName ?: "unknown error"}"
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

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Разработчики") {
        MaterialTheme {
            DesktopChatApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopChatApp() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var screen by remember { mutableStateOf(Screen.MAIN_CHAT) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var devSettings by remember { mutableStateOf(DevSettings(defaultDevs())) }
    var chatLines by remember { mutableStateOf(listOf<ChatLine>()) }
    var userInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var pendingResponses by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun handleSend(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || isSending) return
        val userLine = ChatLine(
            author = "USER",
            text = trimmed
        )
        chatLines = chatLines + userLine
        userInput = ""
        val devsSnapshot = devSettings.devs.map { it.copy() }
        if (devsSnapshot.isEmpty()) {
            isSending = false
            return
        }
        isSending = true
        pendingResponses = devsSnapshot.size
        devsSnapshot.forEach { dev ->
            scope.launch {
                val messages = buildMessages(dev, trimmed)
                val content = withContext(Dispatchers.IO) {
                    callOpenRouter(
                        model = settings.openRouterModel,
                        messages = messages,
                        forceJson = settings.strictJsonEnabled,
                        apiKeyOverride = settings.openRouterApiKey,
                        temperatureOverride = dev.temperature
                    )
                }
                val reply = ChatLine(
                    author = dev.name,
                    text = content
                )
                chatLines = chatLines + reply
                pendingResponses = (pendingResponses - 1).coerceAtLeast(0)
                if (pendingResponses == 0) {
                    isSending = false
                }
            }
        }
    }

    fun handleCopyChat() {
        val text = chatLines
            .sortedBy { it.ts }
            .joinToString(separator = "\n") { line -> "[${line.author}] ${line.text}" }
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar("Скопировано") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AOSP Bugreport Analyzer") },
                actions = {
                    TextButton(onClick = { screen = Screen.DEV_SETTINGS }) {
                        Text("Разработчики")
                    }
                    TextButton(
                        onClick = { handleCopyChat() },
                        enabled = chatLines.isNotEmpty()
                    ) {
                        Text("Скопировать чат")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        when (screen) {
            Screen.MAIN_CHAT -> {
                MainChatScreen(
                    modifier = Modifier.padding(padding),
                    settings = settings,
                    onSettingsChange = { settings = it },
                    chatLines = chatLines,
                    userPrompt = userInput,
                    onPromptChange = { userInput = it },
                    isSending = isSending,
                    onSend = { handleSend(userInput) }
                )
            }
            Screen.DEV_SETTINGS -> {
                DevSettingsScreen(
                    modifier = Modifier.padding(padding),
                    settings = devSettings,
                    onChange = { devSettings = it },
                    onClose = { screen = Screen.MAIN_CHAT }
                )
            }
        }
    }
}

@Composable
private fun MainChatScreen(
    modifier: Modifier,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    chatLines: List<ChatLine>,
    userPrompt: String,
    onPromptChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(chatLines.size) {
        if (chatLines.isNotEmpty()) {
            listState.animateScrollToItem(chatLines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Настройки OpenRouter",
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = settings.openRouterApiKey,
            onValueChange = { onSettingsChange(settings.copy(openRouterApiKey = it)) },
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
            value = settings.openRouterModel,
            onValueChange = { onSettingsChange(settings.copy(openRouterModel = it)) },
            label = { Text("Модель") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Switch(
                checked = settings.strictJsonEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(strictJsonEnabled = it)) }
            )
            Text("Строгий JSON (response_format)")
        }

        Text(
            text = "Чат",
            style = MaterialTheme.typography.titleMedium
        )
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (chatLines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Диалог пока пуст")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .padding(16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(chatLines, key = { it.id }) { line ->
                        Column {
                            Text(
                                text = "${line.author} · ${formatTimestamp(line.ts)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(line.text)
                        }
                    }
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = userPrompt,
                onValueChange = onPromptChange,
                label = { Text("Ваш запрос") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onSend,
                    enabled = userPrompt.isNotBlank() && !isSending
                ) {
                    Text("Отправить")
                }
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.height(32.dp).width(32.dp))
                }
            }
        }
    }
}

@Composable
private fun DevSettingsScreen(
    modifier: Modifier,
    settings: DevSettings,
    onChange: (DevSettings) -> Unit,
    onClose: () -> Unit
) {
    var drafts by remember(settings) { mutableStateOf(settings.devs.map { it.copy() }) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Профили разработчиков",
            style = MaterialTheme.typography.titleLarge
        )
        drafts.forEachIndexed { index, dev ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Разработчик ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = dev.name,
                        onValueChange = { value ->
                            drafts = drafts.mapIndexed { i, item ->
                                if (i == index) item.copy(name = value) else item
                            }
                        },
                        label = { Text("Имя") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dev.prompt,
                        onValueChange = { value ->
                            drafts = drafts.mapIndexed { i, item ->
                                if (i == index) item.copy(prompt = value) else item
                            }
                        },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Температура: ${"%.1f".format(dev.temperature)}")
                        androidx.compose.material3.Slider(
                            value = dev.temperature.toFloat(),
                            onValueChange = { value ->
                                val clamped = value.coerceIn(0f, 2f)
                                drafts = drafts.mapIndexed { i, item ->
                                    if (i == index) item.copy(temperature = (Math.round(clamped * 10f) / 10f).toDouble()) else item
                                }
                            },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) {
                Text("Назад")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    val updated = DevSettings(drafts.map { it.copy() }.toMutableList())
                    onChange(updated)
                    onClose()
                }
            ) {
                Text("Сохранить")
            }
        }
    }
}
