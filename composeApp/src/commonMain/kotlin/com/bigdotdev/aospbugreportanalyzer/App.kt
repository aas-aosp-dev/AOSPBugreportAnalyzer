package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.bigdotdev.aospbugreportanalyzer.memory.AgentMemoryEntry
import com.bigdotdev.aospbugreportanalyzer.memory.AgentMemoryRepository
import com.bigdotdev.aospbugreportanalyzer.memory.createAgentMemoryStore

@Composable
fun App() {
    MaterialTheme {
        val json = remember {
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }
        }
        val memoryStore = remember { createAgentMemoryStore(json) }
        val memoryRepository = remember { AgentMemoryRepository(memoryStore) }
        val scope = rememberCoroutineScope()

        var userMessage by remember { mutableStateOf("") }
        var assistantMessage by remember { mutableStateOf("") }
        var tagsText by remember { mutableStateOf("chat,bugreport") }
        var statusMessage by remember { mutableStateOf("") }
        var memoryEntries by remember { mutableStateOf<List<AgentMemoryEntry>>(emptyList()) }

        LaunchedEffect(memoryRepository) {
            memoryEntries = memoryRepository.getAllEntries()
        }

        val memoryCount = memoryEntries.size
        val tags = remember(tagsText) {
            tagsText.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Memory entries stored: $memoryCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = {
                        scope.launch {
                            memoryRepository.clearAll()
                            memoryEntries = emptyList()
                            statusMessage = "Внешняя память очищена"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Очистить память")
                }
            }

            OutlinedTextField(
                value = userMessage,
                onValueChange = { userMessage = it },
                label = { Text("Сообщение пользователя") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = assistantMessage,
                onValueChange = { assistantMessage = it },
                label = { Text("Ответ ассистента") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Теги через запятую") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (userMessage.isBlank() || assistantMessage.isBlank()) {
                            statusMessage = "Введите сообщения пользователя и ассистента"
                            return@Button
                        }
                        scope.launch {
                            memoryRepository.rememberTurn(
                                conversationId = "default",
                                userMessage = userMessage,
                                assistantMessage = assistantMessage,
                                tags = if (tags.isEmpty()) listOf("chat") else tags
                            )
                            memoryEntries = memoryRepository.getAllEntries()
                            userMessage = ""
                            assistantMessage = ""
                            statusMessage = "Шаг диалога сохранён в память"
                        }
                    },
                    enabled = userMessage.isNotBlank() && assistantMessage.isNotBlank()
                ) {
                    Text("Сохранить ход")
                }

                Button(
                    onClick = {
                        scope.launch {
                            memoryEntries = memoryRepository.getAllEntries()
                            statusMessage = "Память перезагружена"
                        }
                    }
                ) {
                    Text("Обновить")
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(memoryEntries.asReversed().take(5)) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = entry.createdAt,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "User: ${entry.userMessage}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Assistant: ${entry.assistantMessage}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (entry.tags.isNotEmpty()) {
                                Text(
                                    text = "Теги: ${entry.tags.joinToString()}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
