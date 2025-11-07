package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_OPENROUTER_MODEL = "gpt-4o-mini"

private val RESEARCH_MODE_PROMPT = """
Ты — аналитик и фасилитатор. Общайся кратко, задавай по одному уточняющему вопросу за раз.
Цель: собрать требования и в нужный момент выдать итоговый документ (ТЗ).

Когда информации недостаточно — задавай уточнения.
Когда информации достаточно — сформируй ТЗ строго по итоговому формату (см. ниже), затем выведи маркер <END_TZ> и остановись.

Итоговый формат (строго JSON UTF-8, без Markdown и лишнего текста):
{
  "complete": true,
  "generated_at": "<ISO8601>",
  "tz": {
    "title": "<строка>",
    "problem": "<1-3 предложения>",
    "goals": ["<цель 1>", "<цель 2>"],
    "non_goals": ["<что не делаем>"],
    "scope": {
      "in": ["<что входит>"],
      "out": ["<что не входит>"]
    },
    "stakeholders": ["<кто участвует>"],
    "inputs": ["<исходные данные>"],
    "deliverables": ["<результаты/артефакты>"],
    "acceptance": ["<критерии приёмки>"],
    "risks": ["<риски>"],
    "timeline": "<оценка сроков/этапов>"
  }
}

Во время диалога (до готовности) отвечай только в виде JSON:
{
  "complete": false,
  "ask": "<один конкретный вопрос пользователю>",
  "known": { "title": "...", "goals": [...], "...": "..." }
}

Строго:
- Всегда JSON, без Markdown и пояснений.
- Когда ТЗ готово, только один JSON с "complete": true, затем маркер <END_TZ>.
""".trimIndent()

private val DEFAULT_SYSTEM_PROMPT = """
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

private enum class ProviderOption(val displayName: String, val apiName: String) {
    OpenRouter("OpenRouter", "openrouter")
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
    var model by remember {
        mutableStateOf(
            System.getenv("OPENROUTER_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENROUTER_MODEL
        )
    }
    var strictJsonEnabled by remember { mutableStateOf(true) }
    var systemPromptText by remember { mutableStateOf(DEFAULT_SYSTEM_PROMPT) }
    var researchModeEnabled by remember { mutableStateOf(false) }

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<Pair<String, String>>() } // role -> content

    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            provider = provider,
            onProviderChange = { provider = it },
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
            val response = withContext(Dispatchers.IO) {
                ApiClient.chatComplete(
                    request = ChatBffRequest(
                        provider = provider.apiName,
                        model = model,
                        history = historySnapshot.map { (role, content) -> ChatHistoryItem(role, content) },
                        userInput = prompt,
                        strictJson = strictJsonEnabled || researchModeEnabled,
                        systemPrompt = if (researchModeEnabled) {
                            RESEARCH_MODE_PROMPT
                        } else {
                            systemPromptText
                        },
                        responseFormat = if (strictJsonEnabled || researchModeEnabled) "json" else "text"
                    )
                )
            }

            if (!response.ok) {
                error = response.error ?: response.text ?: "Неизвестная ошибка сервера"
            } else {
                val assistantReply = when (response.contentType.lowercase()) {
                    "json" -> response.data ?: "{}"
                    else -> response.text ?: ""
                }
                history += "assistant" to assistantReply.ifBlank { "(пустой ответ)" }
            }
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
        researchModeEnabled = researchModeEnabled,
        onResearchModeToggle = { researchModeEnabled = !researchModeEnabled },
        canSend = !isLoading && input.isNotBlank() && model.isNotBlank()
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
    researchModeEnabled: Boolean,
    onResearchModeToggle: () -> Unit,
    canSend: Boolean
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат") },
                actions = {
                    Button(onClick = onResearchModeToggle) {
                        Text(
                            if (researchModeEnabled) "Выключить режим исследования" else "Включить режим исследования"
                        )
                    }
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
                if (researchModeEnabled) {
                    Text(
                        text = "Режим исследования включён",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
    model: String,
    onModelChange: (String) -> Unit,
    strictJsonEnabled: Boolean,
    onStrictJsonChange: (Boolean) -> Unit,
    systemPromptText: String,
    onSystemPromptChange: (String) -> Unit,
    onClose: () -> Unit
) {
    var providersExpanded by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
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
                    modifier = Modifier.fillMaxWidth()
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

