package app.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import core.ai.agents.AgentConfig
import core.ai.agents.AgentProvider
import core.ai.agents.validateTemperature
import core.data.local.AgentsStore
import core.data.local.ChatStore
import core.data.local.MetricsStore
import core.domain.chat.ChatMessage
import core.domain.chat.ChatRole
import core.domain.chat.ChatService
import core.domain.chat.ProviderResolver
import core.domain.chat.ProviderUsage
import core.ui.state.AppUiState
import core.ui.state.AppViewModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

@Composable
fun DesktopApp() {
    val scope = rememberCoroutineScope()
    val agentsStore = remember { AgentsStore() }
    val chatStore = remember { ChatStore() }
    val metricsStore = remember { MetricsStore() }
    val openRouterProvider = remember { OpenRouterProvider() }
    val resolver = remember {
        ProviderResolver { provider ->
            when (provider) {
                AgentProvider.OpenRouter -> openRouterProvider
                AgentProvider.GigaChat, AgentProvider.Yandex -> throw UnsupportedOperationException(
                    "Provider $provider is not implemented yet"
                )
            }
        }
    }
    val chatService = remember { ChatService(agentsStore, chatStore, metricsStore, resolver, scope) }
    val viewModel = remember { AppViewModel(agentsStore, chatStore, metricsStore, chatService, scope) }
    val state by viewModel.uiState.collectAsState()
    DesktopScaffold(state, viewModel)
}

@Composable
private fun DesktopScaffold(state: AppUiState, viewModel: AppViewModel) {
    var helpMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AOSP Bugreport Analyzer") },
                actions = {
                    TextButton(onClick = { viewModel.toggleAgentsManager(true) }) {
                        Text("Agents")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI Lab Mode", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = state.labMode, onCheckedChange = { viewModel.toggleLabMode() })
                    }
                    Box {
                        IconButton(onClick = { helpMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Help")
                        }
                        DropdownMenu(expanded = helpMenuExpanded, onDismissRequest = { helpMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Project Spec") },
                                onClick = {
                                    helpMenuExpanded = false
                                    viewModel.toggleSpec(true)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ChatPane(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                state = state,
                viewModel = viewModel,
            )
            if (state.labMode) {
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
                LabPane(modifier = Modifier.weight(0.4f).fillMaxHeight())
            }
        }
    }

    if (state.showAgentsManager) {
        AgentsDialog(
            agents = state.agents,
            activeId = state.activeAgentId,
            onSelect = { viewModel.selectAgent(it) },
            onClose = { viewModel.toggleAgentsManager(false) },
            onSave = { viewModel.upsertAgent(it) },
            onDelete = { viewModel.deleteAgent(it) },
            onDuplicate = { viewModel.duplicateAgent(it) },
        )
    }

    state.usageDetails?.let { usage ->
        UsageDialog(usage) { viewModel.hideUsage() }
    }

    if (state.showSpec) {
        SpecDialog(onClose = { viewModel.toggleSpec(false) })
    }
}

@Composable
private fun ChatPane(modifier: Modifier, state: AppUiState, viewModel: AppViewModel) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(16.dp)) {
        AgentSelector(
            agents = state.agents,
            activeId = state.activeAgentId,
            onSelect = { viewModel.selectAgent(it) }
        )
        state.statusMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            StatusBanner(message = it, onDismiss = { viewModel.clearStatusMessage() })
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(state.chatMessages) { message ->
                    MessageBubble(
                        message = message,
                        usage = state.metrics[message.id],
                        onUsage = { viewModel.showUsageFor(message.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = input,
                onValueChange = { input = it },
                label = { Text("Message") },
                enabled = !state.isSending,
                singleLine = false,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                enabled = input.isNotBlank() && !state.isSending,
                onClick = {
                    viewModel.sendMessage(input)
                    input = ""
                }
            ) {
                Text(if (state.isSending) "Sending..." else "Send")
            }
        }
    }
}

@Composable
private fun AgentSelector(agents: List<AgentConfig>, activeId: String?, onSelect: (String) -> Unit) {
    val hasAgents = agents.isNotEmpty()
    val activeAgent = agents.firstOrNull { it.id == activeId }
    val helperText = activeAgent?.model ?: "Create or select an agent to start chatting"

    Column {
        Text("Active agent", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        DropdownField(
            label = "Agent",
            value = activeAgent?.name ?: "Select an agent",
            enabled = hasAgents,
            helperText = helperText
        ) { closeMenu ->
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(agent.name)
                            Text(
                                agent.model,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        closeMenu()
                        onSelect(agent.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, usage: ProviderUsage?, onUsage: () -> Unit) {
    val background = when (message.role) {
        ChatRole.User -> MaterialTheme.colorScheme.primaryContainer
        ChatRole.Assistant -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.System -> MaterialTheme.colorScheme.tertiaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(message.role.name, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            if (message.error != null) {
                Text(message.error, color = MaterialTheme.colorScheme.error)
            } else {
                Text(message.content.ifBlank { if (message.isPending) "Waiting for response..." else "" })
            }
            if (message.role == ChatRole.Assistant && !message.isPending) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onUsage) {
                    Text("ⓘ Usage")
                }
            }
        }
    }
}

@Composable
private fun LabPane(modifier: Modifier) {
    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card { Column(Modifier.padding(16.dp)) { Text("Model Bench", fontWeight = FontWeight.Bold); Text("Coming soon: benchmark models against curated bugreport sets.") } }
        Card { Column(Modifier.padding(16.dp)) { Text("Prompt Lab", fontWeight = FontWeight.Bold); Text("Experiment with prompts without affecting production agents.") } }
        Card { Column(Modifier.padding(16.dp)) { Text("Team Simulation", fontWeight = FontWeight.Bold); Text("Run cross-disciplinary reviews in a sandboxed environment.") } }
    }
}

@Composable
private fun AgentsDialog(
    agents: List<AgentConfig>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onSave: (AgentConfig) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
) {
    val localAgents = remember(agents) { mutableStateListOf(*agents.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
        title = { Text("Agents") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                localAgents.forEachIndexed { index, agent ->
                    AgentEditor(
                        agent = agent,
                        onAgentChange = { updated -> localAgents[index] = updated },
                        onSave = { onSave(localAgents[index]); },
                        onDelete = { onDelete(agent.id) },
                        onDuplicate = { onDuplicate(agent.id) },
                        isActive = agent.id == activeId,
                        onSelect = { onSelect(agent.id) }
                    )
                }
                TextButton(onClick = {
                    val newAgent = AgentConfig(
                        name = "New Agent",
                        provider = AgentProvider.OpenRouter,
                        model = "mistralai/mixtral-8x7b-instruct",
                        apiKey = "",
                        systemPrompt = "You are an assistant helping with Android bugreport triage.",
                        temperature = 0.2,
                        maxTokens = null,
                        topP = null,
                        seed = null,
                        strictJson = false,
                    )
                    localAgents.add(newAgent)
                    onSave(newAgent)
                }) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Agent")
                }
            }
        }
    )
}

@Composable
private fun AgentEditor(
    agent: AgentConfig,
    onAgentChange: (AgentConfig) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    isActive: Boolean,
    onSelect: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!isActive) {
                    TextButton(onClick = onSelect) { Text("Activate") }
                } else {
                    Text("Active", color = MaterialTheme.colorScheme.primary)
                }
            }
            OutlinedTextField(value = agent.name, onValueChange = { onAgentChange(agent.copy(name = it)) }, label = { Text("Name") })
            ProviderPicker(agent = agent, onAgentChange = onAgentChange)
            OutlinedTextField(value = agent.model, onValueChange = { onAgentChange(agent.copy(model = it)) }, label = { Text("Model") })
            OutlinedTextField(value = agent.apiKey.orEmpty(), onValueChange = { onAgentChange(agent.copy(apiKey = it)) }, label = { Text("API Key") })
            Text(
                text = "Tip: store keys locally only. Required for OpenRouter requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = agent.systemPrompt, onValueChange = { onAgentChange(agent.copy(systemPrompt = it)) }, label = { Text("System Prompt") })
            Column {
                Text("Temperature: ${"%.2f".format(agent.temperature)}")
                Slider(value = agent.temperature.toFloat(), onValueChange = { onAgentChange(agent.copy(temperature = validateTemperature(it.toDouble()))) }, valueRange = 0f..2f)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = agent.maxTokens?.toString().orEmpty(),
                    onValueChange = { value ->
                        onAgentChange(agent.copy(maxTokens = value.toIntOrNull()))
                    },
                    label = { Text("Max tokens") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = agent.topP?.toString().orEmpty(),
                    onValueChange = { value ->
                        onAgentChange(agent.copy(topP = value.toDoubleOrNull()))
                    },
                    label = { Text("Top P") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = agent.seed?.toString().orEmpty(),
                    onValueChange = { value ->
                        onAgentChange(agent.copy(seed = value.toLongOrNull()))
                    },
                    label = { Text("Seed") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = agent.strictJson, onCheckedChange = { onAgentChange(agent.copy(strictJson = it)) })
                Spacer(Modifier.width(8.dp))
                Text("Strict JSON")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
                TextButton(onClick = onDuplicate) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Clone")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ProviderPicker(agent: AgentConfig, onAgentChange: (AgentConfig) -> Unit) {
    DropdownField(
        label = "Provider",
        value = agent.provider.displayName,
        enabled = true,
        helperText = null
    ) { closeMenu ->
        AgentProvider.values().forEach { provider ->
            DropdownMenuItem(
                text = { Text(provider.displayName) },
                onClick = {
                    closeMenu()
                    onAgentChange(agent.copy(provider = provider))
                }
            )
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    enabled: Boolean,
    helperText: String?,
    menuContent: @Composable ColumnScope.(closeMenu: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Column {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(label) },
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null
                    ) { expanded = !expanded },
                trailingIcon = {
                    IconButton(onClick = { if (enabled) expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }
                }
            )
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false }
            ) {
                menuContent { expanded = false }
            }
        }
        if (!helperText.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UsageDialog(state: core.ui.state.UsagePanelState, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val usage = state.usage
    val summary = buildString {
        appendLine("Provider: ${usage.provider}")
        appendLine("Model: ${usage.model}")
        appendLine("Latency: ${usage.latencyMs.takeIf { it > 0 }?.let { "$it ms" } ?: "—"}")
        appendLine("Tokens: in=${usage.inputTokens ?: "—"}, out=${usage.outputTokens ?: "—"}, total=${usage.totalTokens ?: "—"}")
        appendLine("Cost: ${usage.costUsd?.let { "$" + "%.6f".format(it) } ?: "—"}")
        appendLine("Temperature: ${usage.temperature ?: "—"}")
        appendLine("Seed: ${usage.seed ?: "—"}")
        appendLine("Timestamp: ${usage.timestamp}")
        appendLine("Session: ${usage.sessionId ?: "—"}")
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
        title = { Text("Usage metrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(summary)
                Button(onClick = { clipboard.setText(AnnotatedString(summary)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Copy metrics")
                }
            }
        }
    )
}

@Composable
private fun SpecDialog(onClose: () -> Unit) {
    val specText = remember { loadSpecText() }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
        title = { Text("Project Specification") },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState())) {
                Text(
                    specText.ifBlank { "Unable to load spec document." },
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    )
}

private fun loadSpecText(): String {
    val path: Path = Paths.get("docs/product/AOSPBugreportAnalyzer-Spec.md")
    if (!path.exists()) return "Spec file missing."
    return runCatching { Files.readString(path) }.getOrElse { "Failed to read spec: ${it.message}" }
}

@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss status")
            }
        }
    }
}
