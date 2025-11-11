package com.bigdotdev.aospbugreportanalyzer

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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.math.round

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
    val temperature: Double? = null
)

data class AppSettings(
    val openRouterApiKey: String = System.getenv("OPENROUTER_API_KEY") ?: "",
    val openRouterModel: String = "openai/gpt-4o-mini",
    val strictJsonEnabled: Boolean = false
)

private val neutralSystemPrompt = "Be concise and precise."

private val httpClient: HttpClient = HttpClient.newHttpClient()

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
        temperature = temperature
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
        request.temperature?.let {
            append(",\"temperature\":")
            append(it)
        }
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
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf(Screen.MAIN_CHAT) }
    var appSettings by remember { mutableStateOf(AppSettings()) }
    var devSettings by remember { mutableStateOf(DevSettings(defaultDevs())) }

    val chatLines = remember { mutableStateListOf<ChatLine>() }
    var userPrompt by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мульти-разработчики") },
                actions = {
                    TextButton(
                        onClick = {
                            val chatText = chatLines.joinToString("\n") { "[${it.author}] ${it.text}" }
                            if (chatText.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(chatText))
                                scope.launch { snackbarHostState.showSnackbar("Скопировано") }
                            }
                        },
                        enabled = chatLines.isNotEmpty()
                    ) {
                        Text("Скопировать чат")
                    }
                    TextButton(onClick = { screen = Screen.DEV_SETTINGS }) {
                        Text("Разработчики")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (screen) {
            Screen.MAIN_CHAT -> {
                MainChatScreen(
                    modifier = Modifier.padding(padding),
                    chatLines = chatLines,
                    userPrompt = userPrompt,
                    onPromptChange = { userPrompt = it },
                    isSending = isSending,
                    onSend = {
                        val text = userPrompt.trim()
                        if (text.isEmpty() || isSending) return@MainChatScreen
                        userPrompt = ""
                        val userMessage = ChatLine(author = "USER", text = text)
                        chatLines.add(userMessage)
                        isSending = true
                        scope.launch {
                            try {
                                val jobs = devSettings.devs.map { dev ->
                                    async {
                                        val messages = listOf(
                                            ORMessage("system", neutralSystemPrompt),
                                            ORMessage("system", "Persona: ${dev.prompt}"),
                                            ORMessage("user", text)
                                        )
                                        val content = withContext(Dispatchers.IO) {
                                            callOpenRouter(
                                                model = appSettings.openRouterModel,
                                                messages = messages,
                                                forceJson = appSettings.strictJsonEnabled,
                                                apiKeyOverride = appSettings.openRouterApiKey,
                                                temperature = dev.temperature
                                            )
                                        }
                                        chatLines.add(
                                            ChatLine(author = dev.name, text = content)
                                        )
                                    }
                                }
                                jobs.awaitAll()
                            } finally {
                                isSending = false
                            }
                        }
                    },
                    settings = appSettings,
                    onSettingsChange = { appSettings = it }
                )
            }
            Screen.DEV_SETTINGS -> {
                DevSettingsScreen(
                    modifier = Modifier.padding(padding),
                    settings = devSettings,
                    onSave = {
                        devSettings = DevSettings(it.toMutableList())
                        screen = Screen.MAIN_CHAT
                    },
                    onCancel = {
                        screen = Screen.MAIN_CHAT
                    }
                )
            }
        }
    }
}

@Composable
private fun MainChatScreen(
    modifier: Modifier,
    chatLines: List<ChatLine>,
    userPrompt: String,
    onPromptChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
            if (chatLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Отправьте запрос, чтобы получить ответы разработчиков")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(chatLines, key = { it.id }) { line ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(line.author, style = MaterialTheme.typography.titleMedium)
                                    Text(formatTimestamp(line.ts), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(line.text)
                            }
                        }
                    }
                }
            }
            if (isSending) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }

        OutlinedTextField(
            value = userPrompt,
            onValueChange = onPromptChange,
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onSend, enabled = userPrompt.isNotBlank() && !isSending) {
                Text("Отправить")
            }
            if (isSending) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("OpenRouter настройки", style = MaterialTheme.typography.titleMedium)
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
        }
    }
}

@Composable
private fun DevSettingsScreen(
    modifier: Modifier,
    settings: DevSettings,
    onSave: (List<DevProfile>) -> Unit,
    onCancel: () -> Unit
) {
    var localDevs by remember(settings) { mutableStateOf(settings.devs.map { it.copy() }) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Профили разработчиков", style = MaterialTheme.typography.headlineSmall)
        localDevs.forEachIndexed { index, dev ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Разработчик ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = dev.name,
                        onValueChange = { value ->
                            localDevs = localDevs.toMutableList().also {
                                it[index] = dev.copy(name = value)
                            }
                        },
                        label = { Text("Имя") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dev.prompt,
                        onValueChange = { value ->
                            localDevs = localDevs.toMutableList().also {
                                it[index] = dev.copy(prompt = value)
                            }
                        },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Температура: ${"%.1f".format(dev.temperature)}")
                        Slider(
                            value = dev.temperature.toFloat(),
                            onValueChange = { value ->
                                val snapped = round(value * 10f) / 10.0
                                localDevs = localDevs.toMutableList().also {
                                    it[index] = dev.copy(temperature = snapped.coerceIn(0.0, 2.0))
                                }
                            },
                            valueRange = 0f..2f,
                            steps = 19,
                            colors = SliderDefaults.colors()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
        ) {
            Button(onClick = { onSave(localDevs) }) {
                Text("Сохранить")
            }
            TextButton(onClick = onCancel) {
                Text("Назад")
            }
        }
    }
}
