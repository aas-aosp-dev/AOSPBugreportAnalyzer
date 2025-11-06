package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.bigdotdev.aospbugreportanalyzer.vpn.DesktopVpnController
import com.bigdotdev.aospbugreportanalyzer.vpn.DesktopVpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.absolutePathString

private enum class Provider { OpenAI, Groq, OpenRouter }

// Необязательные, но рекомендуемые заголовки для OpenRouter
private const val OPENROUTER_REFERER = "https://github.com/aas-aosp-dev/AOSPBugreportAnalyzer"
private const val OPENROUTER_TITLE = "AOSP Bugreport Analyzer"

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Chat") {
        MaterialTheme {
            val scope = rememberCoroutineScope()

            var provider by remember { mutableStateOf(Provider.OpenAI) }
            var apiKey by remember { mutableStateOf(System.getenv("OPENAI_API_KEY") ?: "") }
            var model by remember { mutableStateOf(System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini") }
            var apiKeyVisible by remember { mutableStateOf(false) }

            var input by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val history = remember { mutableStateListOf<Pair<String, String>>() } // role -> content

            var strictJsonEnabled by remember { mutableStateOf(true) }

            var vpnVlessKey by remember { mutableStateOf("") }
            var vpnConnectionState by remember { mutableStateOf<VpnConnectionViewData?>(null) }
            var vpnBanner by remember { mutableStateOf<VpnBannerMessage?>(null) }
            var vpnBusy by remember { mutableStateOf(false) }
            var vpnHasActiveSession by remember { mutableStateOf(false) }
            val vpnController = remember { DesktopVpnController() }

            val DEFAULT_SYSTEM_PROMPT = """
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

            var systemPromptText by remember { mutableStateOf(DEFAULT_SYSTEM_PROMPT) }

            // Подстраиваем ключ и дефолтную модель при смене провайдера
            LaunchedEffect(provider) {
                when (provider) {
                    Provider.OpenAI -> {
                        if (apiKey.isBlank() || apiKey == System.getenv("GROQ_API_KEY") || apiKey == System.getenv("OPENROUTER_API_KEY")) {
                            apiKey = System.getenv("OPENAI_API_KEY") ?: ""
                        }
                        if (
                            model.isBlank() ||
                            model.startsWith("llama") ||
                            model.startsWith("meta-") ||
                            model.startsWith("openrouter/") ||
                            model.startsWith("openai/")
                        ) {
                            model = System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini"
                        }
                    }
                    Provider.Groq -> {
                        if (apiKey.isBlank() || apiKey == System.getenv("OPENAI_API_KEY") || apiKey == System.getenv("OPENROUTER_API_KEY")) {
                            apiKey = System.getenv("GROQ_API_KEY") ?: ""
                        }
                        if (model.isBlank() || model.startsWith("gpt-") || model.startsWith("openrouter/")) {
                            model = "llama-3.1-8b-instant"
                        }
                    }
                    Provider.OpenRouter -> {
                        if (apiKey.isBlank() || apiKey == System.getenv("OPENAI_API_KEY") || apiKey == System.getenv("GROQ_API_KEY")) {
                            apiKey = System.getenv("OPENROUTER_API_KEY") ?: ""
                        }
                        if (model.isBlank()) {
                            model = "openrouter/auto"
                        }
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("VPN подключение", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = vpnVlessKey,
                            onValueChange = { vpnVlessKey = it },
                            label = { Text("VLESS ключ") },
                            placeholder = { Text("vless://<uuid>@host:port?params") },
                            enabled = !vpnBusy,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !vpnBusy && vpnVlessKey.isNotBlank(),
                                onClick = {
                                    val key = vpnVlessKey.trim()
                                    if (key.isEmpty()) return@Button
                                    scope.launch {
                                        vpnBusy = true
                                        vpnBanner = null
                                        val result = withContext(Dispatchers.IO) {
                                            connectDesktopVpn(
                                                controller = vpnController,
                                                vlessKey = key
                                            )
                                        }
                                        vpnBusy = false
                                        vpnHasActiveSession = vpnController.currentSession() != null
                                        if (result.session == null) {
                                            vpnController.currentSession()?.let { current ->
                                                vpnConnectionState = current.toViewData(status = "Активно")
                                            }
                                        }
                                        result.session?.let { session ->
                                            vpnConnectionState = session
                                            vpnBanner = VpnBannerMessage("VPN подключено", isError = false)
                                        }
                                        result.error?.let { message ->
                                            vpnBanner = VpnBannerMessage(message, isError = true)
                                        }
                                    }
                                }
                            ) {
                                Text("Подключиться")
                            }

                            OutlinedButton(
                                enabled = !vpnBusy && vpnHasActiveSession,
                                onClick = {
                                    scope.launch {
                                        vpnBusy = true
                                        vpnBanner = null
                                        val result = withContext(Dispatchers.IO) {
                                            disconnectDesktopVpn(vpnController)
                                        }
                                        vpnBusy = false
                                        vpnHasActiveSession = vpnController.currentSession() != null
                                        if (result.session == null) {
                                            vpnController.currentSession()?.let { current ->
                                                vpnConnectionState = current.toViewData(status = "Активно")
                                            }
                                        }
                                        result.session?.let {
                                            vpnConnectionState = null
                                            vpnBanner = VpnBannerMessage("VPN отключено", isError = false)
                                        }
                                        result.error?.let { message ->
                                            vpnBanner = VpnBannerMessage(message, isError = true)
                                        }
                                    }
                                }
                            ) {
                                Text("Отключиться")
                            }
                        }

                        vpnConnectionState?.let { session ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Статус: ${session.status}")
                                session.host?.let { host ->
                                    val port = session.port
                                    val hostText = if (port != null) "$host:$port" else host
                                    Text("Сервер: $hostText")
                                }
                                session.userId?.let { userId ->
                                    Text("ID пользователя: $userId")
                                }
                                session.displayName?.let { display ->
                                    Text("Название: $display")
                                }
                                session.parametersDescription?.let { parameters ->
                                    Text("Параметры: $parameters")
                                }
                                session.engine?.let { engine ->
                                    Text("Движок: $engine")
                                }
                                session.proxyEndpoint?.let { endpoint ->
                                    Text("Локальный прокси: $endpoint")
                                }
                                session.binaryPath?.let { path ->
                                    Text("Бинарник: $path")
                                }
                                session.logFile?.let { log ->
                                    Text("Лог: $log")
                                }
                                Text("ID сессии: ${session.connectionId}")
                            }
                        }

                        vpnBanner?.let { banner ->
                            val color = if (banner.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            Text(banner.text, color = color)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Provider + Key + Model
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = provider.name,
                            onValueChange = {},
                            label = { Text("Provider") },
                            readOnly = true,
                            modifier = Modifier.menuAnchor().width(200.dp)
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("OpenAI") }, onClick = { provider = Provider.OpenAI; expanded = false })
                            DropdownMenuItem(text = { Text("Groq") }, onClick = { provider = Provider.Groq; expanded = false })
                            DropdownMenuItem(text = { Text("OpenRouter") }, onClick = { provider = Provider.OpenRouter; expanded = false })
                        }
                    }

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.weight(1f),
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
                        value = model, onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.width(260.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = strictJsonEnabled,
                        onCheckedChange = { strictJsonEnabled = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("System-prompt")
                }
                if (strictJsonEnabled) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = systemPromptText,
                        onValueChange = { systemPromptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        label = { Text("System-prompt (используется, если включён)") }
                    )
                }

                Spacer(Modifier.height(12.dp))

                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    history.forEach { (role, text) ->
                        Text("${role.uppercase()}: $text")
                        Spacer(Modifier.height(6.dp))
                    }
                    if (isLoading) Text("…генерация ответа")
                    error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
                }

                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("Сообщение") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isLoading && input.isNotBlank() && apiKey.isNotBlank(),
                        onClick = {
                            val prompt = input.trim()
                            if (prompt.isEmpty()) return@Button
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
                    ) { Text("Отправить") }

                    OutlinedButton(onClick = { history.clear() }, enabled = !isLoading) { Text("Очистить чат") }
                }
            }
        }
    }
}

data class ChatMessage(val role: String, val content: String)

private data class VpnConnectionViewData(
    val connectionId: String,
    val status: String,
    val host: String?,
    val port: Int?,
    val userId: String?,
    val displayName: String?,
    val parametersDescription: String?,
    val engine: String?,
    val proxyEndpoint: String?,
    val logFile: String?,
    val binaryPath: String?,
)

private data class VpnConnectResult(
    val session: VpnConnectionViewData?,
    val error: String?,
)

private data class VpnDisconnectResult(
    val session: VpnConnectionViewData?,
    val error: String?,
)

private data class VpnBannerMessage(
    val text: String,
    val isError: Boolean,
)

private fun buildMessagesForProvider(
    provider: Provider,
    history: List<Pair<String, String>>,
    userInput: String,
    strictEnabled: Boolean,
    systemText: String
): List<ChatMessage> {
    val base = history.map { ChatMessage(it.first, it.second) } + ChatMessage(role = "user", content = userInput)

    if (!strictEnabled) return base

    val supportsSystemRole = when (provider) {
        Provider.OpenAI,
        Provider.Groq -> true
        Provider.OpenRouter -> false // OpenRouter rejects requests that start with a system-only task
    }

    return if (supportsSystemRole) {
        listOf(ChatMessage(role = "system", content = systemText)) + base
    } else {
        if (base.isEmpty()) {
            listOf(ChatMessage(role = "user", content = systemText))
        } else {
            val userIndex = base.indexOfFirst { it.role == "user" }
            if (userIndex == -1) {
                listOf(ChatMessage(role = "user", content = systemText)) + base
            } else {
                val updated = base[userIndex].copy(content = systemText + "\n\n" + base[userIndex].content)
                base.mapIndexed { index, message -> if (index == userIndex) updated else message }
            }
        }
    }
}

// Для chat.completions (Groq/OpenRouter) нужен простой формат messages
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

private fun connectDesktopVpn(controller: DesktopVpnController, vlessKey: String): VpnConnectResult {
    return try {
        val session = controller.connect(vlessKey)
        VpnConnectResult(session = session.toViewData(status = "Активно"), error = null)
    } catch (failure: Throwable) {
        VpnConnectResult(session = null, error = mapDesktopVpnError(failure))
    }
}

private fun disconnectDesktopVpn(controller: DesktopVpnController): VpnDisconnectResult {
    return try {
        val session = controller.disconnect()
        VpnDisconnectResult(session = session.toViewData(status = "Отключено"), error = null)
    } catch (failure: Throwable) {
        VpnDisconnectResult(session = null, error = mapDesktopVpnError(failure))
    }
}

private fun DesktopVpnSession.toViewData(status: String): VpnConnectionViewData {
    val parametersText = if (vlessUri.parameters.isEmpty()) {
        null
    } else {
        vlessUri.parameters.entries.joinToString { (key, value) -> "$key=$value" }
    }
    return VpnConnectionViewData(
        connectionId = id,
        status = status,
        host = vlessUri.host,
        port = vlessUri.port,
        userId = vlessUri.userId,
        displayName = vlessUri.displayName,
        parametersDescription = parametersText,
        engine = engine.displayName,
        proxyEndpoint = "127.0.0.1:$localProxyPort",
        logFile = logPath.absolutePathString(),
        binaryPath = binaryPath,
    )
}

private fun mapDesktopVpnError(error: Throwable): String {
    val root = findRootCause(error)
    return when (root) {
        is IllegalArgumentException -> root.message ?: "Некорректный VLESS ключ"
        is IllegalStateException -> root.message ?: "Операция недоступна"
        is IOException -> root.message ?: "Ошибка ввода-вывода при работе с VPN"
        else -> root.message ?: root::class.simpleName ?: "Неизвестная ошибка"
    }
}

private fun findRootCause(error: Throwable): Throwable {
    var current = error
    val visited = mutableSetOf<Throwable>()
    while (current.cause != null && current.cause !in visited) {
        visited += current
        current = current.cause!!
    }
    return current
}

private fun callApi(
    provider: Provider,
    apiKey: String,
    model: String,
    history: List<Pair<String, String>>,
    userInput: String,
    strictJsonEnabled: Boolean,
    systemPromptText: String
): String {
    return try {
        val client = HttpClient.newHttpClient()
        val messages = buildMessagesForProvider(
            provider = provider,
            history = history,
            userInput = userInput,
            strictEnabled = strictJsonEnabled,
            systemText = systemPromptText
        )
        val response = when (provider) {
            Provider.OpenAI -> {
                // OpenAI Responses API (как было)
                val messagesJson = buildString {
                    append('[')
                    var first = true
                    for ((role, content) in messages) {
                        if (!first) append(',')
                        first = false
                        append("{\"role\":\"")
                        append(role)
                        append("\",\"content\":[{\"type\":\"text\",\"text\":\"")
                        append(
                            content.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", " ")
                        )
                        append("\"}]}")
                    }
                    append(']')
                }
                val body = "{\"model\":\"$model\",\"input\":$messagesJson}"
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                client.send(req, HttpResponse.BodyHandlers.ofString())
            }
            Provider.Groq -> {
                // Groq: chat/completions
                val preparedMessages = messagesToJson(messages)
                val body = "{\"model\":\"$model\",\"messages\":$preparedMessages}"
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                client.send(req, HttpResponse.BodyHandlers.ofString())
            }
            Provider.OpenRouter -> {
                // OpenRouter: chat/completions
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
                client.send(req, HttpResponse.BodyHandlers.ofString())
            }
        }

        if (response.statusCode() !in 200..299) {
            val headers = response.headers().map().entries.joinToString { (k, v) -> "$k=$v" }
            return buildString {
                appendLine("HTTP ${response.statusCode()}")
                appendLine(headers)
                appendLine(response.body())
            }.take(4000)
        }

        val s = response.body()
        // Groq/OpenRouter: choices[0].message.content
        if (provider == Provider.Groq || provider == Provider.OpenRouter) {
            extractJsonStringValue(s, "\"content\":\"")?.let { value ->
                return value.ifBlank { "(пустой ответ)" }
            }
        }
        // OpenAI Responses: сначала output_text, затем text
        run {
            extractJsonStringValue(s, "\"output_text\":\"")?.let { value ->
                return value.ifBlank { "(пустой ответ)" }
            }
        }
        run {
            extractJsonStringValue(s, "\"text\":\"")?.let { value ->
                return value.ifBlank { "(пустой ответ)" }
            }
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
