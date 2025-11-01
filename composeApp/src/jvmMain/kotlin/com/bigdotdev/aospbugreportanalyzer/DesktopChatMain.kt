package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Chat") {
        MaterialTheme {
            val scope = rememberCoroutineScope()

            var provider by remember { mutableStateOf(Provider.OpenAI) }
            var apiKey by remember { mutableStateOf(System.getenv("OPENAI_API_KEY") ?: "") }
            var model by remember { mutableStateOf(System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini") }

            var input by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val history = remember { mutableStateListOf<Pair<String, String>>() } // role -> content

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
                        value = apiKey, onValueChange = { apiKey = it },
                        label = {
                            Text(
                                "API Key (" + when (provider) {
                                    Provider.Groq -> "GROQ"
                                    Provider.OpenRouter -> "OPENROUTER"
                                    else -> "OpenAI"
                                } + ")"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.width(260.dp)
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
                                        history = history
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

// Для chat.completions (Groq/OpenRouter) нужен простой формат messages
private fun historyToMessages(history: List<Pair<String, String>>): String {
    // [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}]
    val sb = StringBuilder("[")
    var first = true
    for ((role, content) in history) {
        if (!first) sb.append(',')
        first = false
        sb.append("{\"role\":\"")
            .append(role)
            .append("\",\"content\":\"")
            .append(
                content.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", " ")
            )
            .append("\"}")
    }
    sb.append(']')
    return sb.toString()
}

private fun callApi(
    provider: Provider,
    apiKey: String,
    model: String,
    history: List<Pair<String, String>>
): String {
    return try {
        val client = HttpClient.newHttpClient()
        val response = when (provider) {
            Provider.OpenAI -> {
                // OpenAI Responses API (как было)
                val messagesJson = buildString {
                    append('[')
                    var first = true
                    for ((role, content) in history) {
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
                val messages = historyToMessages(history)
                val body = "{\"model\":\"$model\",\"messages\":$messages}"
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
                val messages = historyToMessages(history)
                val body = "{\"model\":\"$model\",\"messages\":$messages}"
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
            val marker = "\"content\":\""
            val idx = s.indexOf(marker)
            if (idx >= 0) {
                val after = s.substring(idx + marker.length)
                return after.substringBefore("\"").replace("\\n", "\n").ifBlank { "(пустой ответ)" }
            }
        }
        // OpenAI Responses: сначала output_text, затем text
        run {
            val marker = "\"output_text\":\""
            val idx = s.indexOf(marker)
            if (idx >= 0) {
                val after = s.substring(idx + marker.length)
                return after.substringBefore("\"").replace("\\n", "\n").ifBlank { "(пустой ответ)" }
            }
        }
        run {
            val marker2 = "\"text\":\""
            val idx2 = s.indexOf(marker2)
            if (idx2 >= 0) {
                val after = s.substring(idx2 + marker2.length)
                return after.substringBefore("\"").replace("\\n", "\n").ifBlank { "(пустой ответ)" }
            }
        }
        "(не удалось распарсить ответ)"
    } catch (t: Throwable) {
        "Ошибка: " + (t.message ?: t::class.simpleName)
    }
}