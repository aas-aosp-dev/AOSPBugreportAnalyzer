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

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Chat") {
        MaterialTheme {
            val scope = rememberCoroutineScope()
            var apiKey by remember { mutableStateOf(System.getenv("OPENAI_API_KEY") ?: "") }
            var model by remember { mutableStateOf(System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini") }
            var input by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val history = remember { mutableStateListOf<Pair<String,String>>() } // role to content

            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        apiKey, { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        model, { model = it },
                        label = { Text("Model") },
                        modifier = Modifier.width(220.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                ) {
                    history.forEach { (role, text) ->
                        Text("${role.uppercase()}: $text")
                        Spacer(Modifier.height(6.dp))
                    }
                    if (isLoading) Text("…генерация ответа")
                    error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
                }

                OutlinedTextField(
                    input, { input = it },
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
                                    callOpenAIResponses(apiKey, model, history)
                                }
                                history += "assistant" to reply
                                isLoading = false
                            }
                        }
                    ) { Text("Отправить") }

                    OutlinedButton(onClick = { history.clear() }, enabled = !isLoading) {
                        Text("Очистить чат")
                    }
                }
            }
        }
    }
}

private fun callOpenAIResponses(
    apiKey: String,
    model: String,
    history: List<Pair<String,String>>
): String {
    return try {
        // Сериализация истории в формат Responses API (input = list of messages)
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
                    content
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", " ")
                )
                append("\"}]}")
            }
            append(']')
        }

        val body = "{\"model\":\"$model\",\"input\":$messagesJson}"

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return "HTTP ${response.statusCode()}: " + response.body().take(500)
        }

        // Простой «наивный» парсинг: сначала пробуем output_text, затем text
        val s = response.body()
        val marker = "\"output_text\":\""
        val idx = s.indexOf(marker)
        if (idx >= 0) {
            val after = s.substring(idx + marker.length)
            return after.substringBefore("\"").replace("\\n", "\n").ifBlank { "(пустой ответ)" }
        }
        val marker2 = "\"text\":\""
        val idx2 = s.indexOf(marker2)
        if (idx2 >= 0) {
            val after = s.substring(idx2 + marker2.length)
            return after.substringBefore("\"").replace("\\n", "\n").ifBlank { "(пустой ответ)" }
        }
        "(не удалось распарсить ответ)"
    } catch (t: Throwable) {
        "Ошибка: " + (t.message ?: t::class.simpleName)
    }
}
