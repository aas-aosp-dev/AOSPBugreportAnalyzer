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

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Chat") {
        MaterialTheme {
            val messages = remember { mutableStateListOf("system: Desktop chat ready") }
            var input by remember { mutableStateOf("") }
            var apiKey by remember { mutableStateOf(System.getenv("OPENAI_API_KEY") ?: "") }
            var model by remember { mutableStateOf(System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini") }

            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.width(220.dp))
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    messages.forEach { Text(it); Spacer(Modifier.height(6.dp)) }
                }
                OutlinedTextField(input, { input = it }, label = { Text("Сообщение") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (input.isNotBlank()) { messages += "user: " + input; input = "" } }) { Text("Отправить (mock)") }
                    OutlinedButton(onClick = { messages.clear() }) { Text("Очистить чат") }
                }
                Spacer(Modifier.height(8.dp))
                Text("TODO: wire OpenAI Responses API in next commit.")
            }
        }
    }
}
