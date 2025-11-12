package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bigdotdev.aospbugreportanalyzer.chat.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.chat.ChatViewModel
import com.bigdotdev.aospbugreportanalyzer.chat.ExpertAgent
import com.bigdotdev.aospbugreportanalyzer.chat.MessageStats
import com.bigdotdev.aospbugreportanalyzer.data.ChatRepository
import com.bigdotdev.aospbugreportanalyzer.network.createHttpClient
import androidx.compose.ui.tooling.preview.Preview
import com.bigdotdev.aospbugreportanalyzer.chat.ChatRole.USER

@Composable
@Preview
fun App() {
    val httpClient = remember { createHttpClient() }
    val agent = remember { ExpertAgent() }
    val repository = remember(httpClient) { ChatRepository(httpClient) }
    val scope = rememberCoroutineScope()
    val viewModel = remember(httpClient) { ChatViewModel(repository, agent, scope) }

    DisposableEffect(httpClient) {
        onDispose { httpClient.close() }
    }

    MaterialTheme {
        ChatScreen(agent = agent, viewModel = viewModel)
    }
}

@Composable
private fun ChatScreen(agent: ExpertAgent, viewModel: ChatViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = viewModel.messages

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agent.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            agent.description,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageItem(message = message)
                }
            }

            Divider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Опишите проблему или багрепорт") },
                    maxLines = 6
                )
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            viewModel.sendMessage(text)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() && !viewModel.isSending
                ) {
                    if (viewModel.isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Отправить")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    val isUser = message.role == USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        MessageStatsText(message.stats)
    }
}

@Composable
private fun MessageStatsText(stats: MessageStats?) {
    val prompt = stats?.promptTokens?.toString() ?: "—"
    val completion = stats?.completionTokens?.toString() ?: "—"
    val total = stats?.totalTokens?.toString() ?: "—"
    val cost = stats?.costUsd?.let { formatCost(it) } ?: "—"
    val ms = stats?.durationMs?.let { "${it} ms" } ?: "—"

    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = "prompt: $prompt • completion: $completion • total: $total • cost: $cost • $ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!stats?.error.isNullOrBlank()) {
            Text(
                text = stats?.error.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun formatCost(value: Double): String = buildString {
    append('$')
    append(String.format("%.4f", value))
}
