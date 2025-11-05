package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

private enum class Provider { OpenAI, Groq, OpenRouter }

// Необязательные, но рекомендуемые заголовки для OpenRouter
private const val OPENROUTER_REFERER = "https://github.com/aas-aosp-dev/AOSPBugreportAnalyzer"
private const val OPENROUTER_TITLE = "AOSP Bugreport Analyzer"
private const val DEFAULT_VPN_SERVER_URL = "http://localhost:8080"

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

            val vpnServerBaseUrl = remember {
                sanitizeBaseUrl(System.getenv("VPN_SERVER_URL") ?: DEFAULT_VPN_SERVER_URL)
            }
            var vpnVlessKey by remember { mutableStateOf("") }
            var vpnConnectionState by remember { mutableStateOf<VpnConnectionViewData?>(null) }
            var vpnBanner by remember { mutableStateOf<VpnBannerMessage?>(null) }
            var vpnBusy by remember { mutableStateOf(false) }

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
                                            connectVpn(
                                                serverUrl = vpnServerBaseUrl,
                                                vlessKey = key
                                            )
                                        }
                                        vpnBusy = false
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
                                enabled = !vpnBusy && vpnConnectionState != null,
                                onClick = {
                                    val active = vpnConnectionState ?: return@OutlinedButton
                                    scope.launch {
                                        vpnBusy = true
                                        vpnBanner = null
                                        val result = withContext(Dispatchers.IO) {
                                            disconnectVpn(
                                                serverUrl = vpnServerBaseUrl,
                                                connectionId = active.connectionId
                                            )
                                        }
                                        vpnBusy = false
                                        result.status?.let { status ->
                                            vpnConnectionState = active.copy(status = status)
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
)

private data class VpnConnectResult(
    val session: VpnConnectionViewData?,
    val error: String?,
)

private data class VpnDisconnectResult(
    val status: String?,
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

private fun connectVpn(serverUrl: String, vlessKey: String): VpnConnectResult {
    return try {
        val base = sanitizeBaseUrl(serverUrl)
        val client = HttpClient.newHttpClient()
        val body = """{"vlessKey":"${escapeJsonValue(vlessKey)}"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/vpn/connect"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val payload = response.body()
        if (status !in 200..299) {
            val message = extractJsonStringValue(payload, "\"message\":\"")
                ?.ifBlank { null }
                ?: payload.take(400)
            return VpnConnectResult(null, "Ошибка сервера ($status): $message")
        }
        val parsed = parseVpnConnectionResponse(payload)
            ?: return VpnConnectResult(null, "Не удалось распознать ответ VPN-сервера")
        VpnConnectResult(parsed, null)
    } catch (t: Throwable) {
        VpnConnectResult(session = null, error = t.message ?: t::class.simpleName ?: "Неизвестная ошибка")
    }
}

private fun disconnectVpn(serverUrl: String, connectionId: String): VpnDisconnectResult {
    return try {
        val base = sanitizeBaseUrl(serverUrl)
        val client = HttpClient.newHttpClient()
        val body = """{"connectionId":"${escapeJsonValue(connectionId)}"}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/vpn/disconnect"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val payload = response.body()
        if (status !in 200..299) {
            val message = extractJsonStringValue(payload, "\"message\":\"")
                ?.ifBlank { null }
                ?: payload.take(400)
            return VpnDisconnectResult(null, "Ошибка сервера ($status): $message")
        }
        val parsedStatus = parseVpnDisconnectResponse(payload)
            ?: return VpnDisconnectResult(null, "Не удалось распознать ответ VPN-сервера")
        VpnDisconnectResult(parsedStatus, null)
    } catch (t: Throwable) {
        VpnDisconnectResult(status = null, error = t.message ?: t::class.simpleName ?: "Неизвестная ошибка")
    }
}

private fun sanitizeBaseUrl(input: String): String {
    val trimmed = input.trim().ifEmpty { DEFAULT_VPN_SERVER_URL }
    return trimmed.trimEnd('/')
}

private fun parseVpnConnectionResponse(payload: String): VpnConnectionViewData? {
    val connectionId = extractJsonStringValue(payload, "\"connectionId\":\"") ?: return null
    val status = extractJsonStringValue(payload, "\"status\":\"") ?: "UNKNOWN"
    val host = extractJsonStringValue(payload, "\"host\":\"")
    val port = extractJsonNumberValue(payload, "\"port\":")
    val userId = extractJsonStringValue(payload, "\"userId\":\"")
    val displayName = extractJsonStringValue(payload, "\"displayName\":\"")
    val parameters = extractJsonObjectValue(payload, "\"parameters\":")
    return VpnConnectionViewData(
        connectionId = connectionId,
        status = status,
        host = host,
        port = port,
        userId = userId,
        displayName = displayName,
        parametersDescription = parameters,
    )
}

private fun parseVpnDisconnectResponse(payload: String): String? {
    return extractJsonStringValue(payload, "\"status\":\"")
}

private fun escapeJsonValue(value: String): String {
    if (value.isEmpty()) return value
    val builder = StringBuilder(value.length + 16)
    value.forEach { ch ->
        when (ch) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> builder.append(ch)
        }
    }
    return builder.toString()
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

private fun extractJsonNumberValue(source: String, marker: String): Int? {
    val startIndex = source.indexOf(marker)
    if (startIndex < 0) return null
    var index = startIndex + marker.length
    while (index < source.length && source[index].isWhitespace()) {
        index++
    }
    if (index >= source.length) return null
    val builder = StringBuilder()
    while (index < source.length) {
        val ch = source[index]
        if (ch.isDigit()) {
            builder.append(ch)
            index++
        } else {
            break
        }
    }
    return builder.toString().toIntOrNull()
}

private fun extractJsonObjectValue(source: String, marker: String): String? {
    val startIndex = source.indexOf(marker)
    if (startIndex < 0) return null
    var index = startIndex + marker.length
    while (index < source.length && source[index] != '{') {
        index++
    }
    if (index >= source.length || source[index] != '{') return null
    var depth = 0
    var i = index
    while (i < source.length) {
        val ch = source[i]
        when (ch) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return source.substring(index, i + 1)
                }
            }
        }
        i++
    }
    return null
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
