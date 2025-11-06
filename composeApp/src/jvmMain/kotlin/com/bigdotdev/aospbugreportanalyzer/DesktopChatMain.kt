package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.menuAnchor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// Необязательные, но рекомендуемые заголовки для OpenRouter
private const val OPENROUTER_REFERER = "https://github.com/aas-aosp-dev/AOSPBugreportAnalyzer"
private const val OPENROUTER_TITLE = "AOSP Bugreport Analyzer"
private const val DEFAULT_OPENROUTER_MODEL = "gpt-4o-mini"

private const val DEFAULT_SYSTEM_PROMPT = """
You are a strict JSON formatter. Return ONLY valid JSON (UTF-8), no Markdown, no comments, no extra text.

Always return an object:
{
  "version": "1.0",
  "ok": true,
  "generated_at": "<ISO8601>",
  "items": [],
  "error": ""
}

Behavioral rules:
- Never return errors. If the request is unclear, conversational, or empty, still output ok=true and include a single item describing the user's intent.
- Always include at least one item. Use this shape for the first item:
  { "type": "summary", "intent": "<one-word label like 'greeting'|'smalltalk'|'other'>", "echo": "<verbatim user text>" }
- If you can extract structured results, append them as additional items (e.g., { "type": "result", ... }).
- Do not invent failures. Keep "error" empty.
""".trimIndent()

private enum class ProviderOption(val displayName: String) {
    OpenRouter("OpenRouter")
}

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Chat") {
        MaterialTheme {
            DesktopChatApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopChatApp() {
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(ProviderOption.OpenRouter) }
    var apiKey by remember { mutableStateOf(System.getenv("OPENROUTER_API_KEY") ?: "") }
    var model by remember {
        mutableStateOf(
            System.getenv("OPENROUTER_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENROUTER_MODEL
        )
    }
    var strictJsonEnabled by remember { mutableStateOf(true) }
    var systemPromptText by remember { mutableStateOf(DEFAULT_SYSTEM_PROMPT) }

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<Pair<String, String>>() } // role -> content

    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            provider = provider,
            onProviderChange = { provider = it },
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            model = model,
            onModelChange = { model = it },
            strictJsonEnabled = strictJsonEnabled,
            onStrictJsonChange = { strictJsonEnabled = it },
            systemPromptText = systemPromptText,
            onSystemPromptChange = { systemPromptText = it },
            onClose = { showSettings = false }
        )
        return
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isEmpty()) return
        val historySnapshot = history.toList()
        input = ""
        error = null
        history += "user" to prompt
        scope.launch {
            isLoading = true
            val reply = withContext(Dispatchers.IO) {
                callApi(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    history = historySnapshot,
                    userInput = prompt,
                    strictJsonEnabled = strictJsonEnabled,
                    systemPromptText = systemPromptText
                )
            }
            history += "assistant" to reply
            isLoading = false
        }
    }

    ChatScreen(
        history = history,
        isLoading = isLoading,
        error = error,
        input = input,
        onInputChange = { input = it },
        onSend = { sendMessage() },
        onClearHistory = { history.clear() },
        onOpenSettings = { showSettings = true },
        canSend = !isLoading && input.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    history: List<Pair<String, String>>,
    isLoading: Boolean,
    error: String?,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    canSend: Boolean
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Чат") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                history.forEach { (role, text) ->
                    Text("${role.uppercase()}: $text")
                    Spacer(Modifier.height(6.dp))
                }
                if (isLoading) Text("…генерация ответа")
                error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Сообщение") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canSend,
                    onClick = onSend
                ) { Text("Отправить") }

                OutlinedButton(onClick = onClearHistory, enabled = !isLoading) { Text("Очистить чат") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    provider: ProviderOption,
    onProviderChange: (ProviderOption) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    strictJsonEnabled: Boolean,
    onStrictJsonChange: (Boolean) -> Unit,
    systemPromptText: String,
    onSystemPromptChange: (String) -> Unit,
    onClose: () -> Unit
) {
    var providersExpanded by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Подключение", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = providersExpanded,
                onExpandedChange = { providersExpanded = it }
            ) {
                OutlinedTextField(
                    value = provider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Провайдер") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providersExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = providersExpanded,
                    onDismissRequest = { providersExpanded = false }
                ) {
                    ProviderOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                onProviderChange(option)
                                providersExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (apiKeyVisible) "Скрыть ключ" else "Показать ключ"
                        )
                    }
                }
            )

            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )

            Text(text = "System prompt", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = strictJsonEnabled,
                    onCheckedChange = onStrictJsonChange
                )
                Spacer(Modifier.width(8.dp))
                Text("Использовать system prompt для строгого JSON")
            }

            if (strictJsonEnabled) {
                OutlinedTextField(
                    value = systemPromptText,
                    onValueChange = onSystemPromptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    label = { Text("System prompt") }
                )
            }
        }
    }
}

data class ChatMessage(val role: String, val content: String)

// OpenRouter не поддерживает отдельную роль system, поэтому внедряем system prompt
// в первое пользовательское сообщение, если строгий режим включён.
private fun buildMessages(
    history: List<Pair<String, String>>,
    userInput: String,
    strictEnabled: Boolean,
    systemText: String
): List<ChatMessage> {
    val base = history.map { ChatMessage(it.first, it.second) } + ChatMessage(role = "user", content = userInput)

    if (!strictEnabled) return base

    if (base.isEmpty()) {
        return listOf(ChatMessage(role = "user", content = systemText))
    }

    val userIndex = base.indexOfFirst { it.role == "user" }
    return if (userIndex == -1) {
        listOf(ChatMessage(role = "user", content = systemText)) + base
    } else {
        val updated = base[userIndex].copy(content = systemText + "\n\n" + base[userIndex].content)
        base.mapIndexed { index, message -> if (index == userIndex) updated else message }
    }
}

// Для chat.completions OpenRouter нужен простой формат messages
private fun messagesToJson(messages: List<ChatMessage>): String {
    val sb = StringBuilder("[")
    var first = true
    for ((role, content) in messages) {
        if (!first) sb.append(',')
        first = false
        sb.append("{\"role\":\"")
            .append(role)
            .append("\",\"content\":\"")
            .append(
                content.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
            )
            .append("\"}")
    }
    sb.append(']')
    return sb.toString()
}

private fun callApi(
    provider: ProviderOption,
    apiKey: String,
    model: String,
    history: List<Pair<String, String>>,
    userInput: String,
    strictJsonEnabled: Boolean,
    systemPromptText: String
): String {
    if (provider != ProviderOption.OpenRouter) {
        return "Провайдер ${provider.displayName} не поддерживается"
    }

    return try {
        val client = HttpClient.newHttpClient()
        val messages = buildMessages(
            history = history,
            userInput = userInput,
            strictEnabled = strictJsonEnabled,
            systemText = systemPromptText
        )
        val preparedMessages = messagesToJson(messages)
        val body = "{\"model\":\"$model\",\"messages\":$preparedMessages}"
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            // Рекомендуемые хедеры
            .header("HTTP-Referer", OPENROUTER_REFERER)
            .header("X-Title", OPENROUTER_TITLE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(req, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            val headers = response.headers().map().entries.joinToString { (k, v) -> "$k=$v" }
            return buildString {
                appendLine("HTTP ${response.statusCode()}")
                appendLine(headers)
                appendLine(response.body())
            }.take(4000)
        }

        val s = response.body()
        extractJsonStringValue(s, "\"content\":\"")?.let { value ->
            return value.ifBlank { "(пустой ответ)" }
        }
        "(не удалось распарсить ответ)"
    } catch (t: Throwable) {
        "Ошибка: " + (t.message ?: t::class.simpleName)
    }
}

private fun extractJsonStringValue(source: String, marker: String): String? {
    val startIndex = source.indexOf(marker)
    if (startIndex < 0) return null
    val from = startIndex + marker.length
    if (from >= source.length) return null
    val sb = StringBuilder()
    var i = from
    var escaping = false
    while (i < source.length) {
        val c = source[i]
        if (escaping) {
            when (c) {
                '\\', '"', '/' -> sb.append(c)
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 < source.length) {
                        val hex = source.substring(i + 1, i + 5)
                        hex.toIntOrNull(16)?.let { code ->
                            sb.append(code.toChar())
                            i += 4
                        }
                    }
                }
                else -> sb.append(c)
            }
            escaping = false
        } else {
            when (c) {
                '\\' -> escaping = true
                '"' -> return sb.toString()
                else -> sb.append(c)
            }
        }
        i++
    }
    return sb.toString()
}
