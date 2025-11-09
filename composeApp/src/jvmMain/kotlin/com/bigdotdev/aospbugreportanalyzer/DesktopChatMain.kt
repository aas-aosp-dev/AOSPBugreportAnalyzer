package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private enum class TeamId { DEV, DESIGN, ANALYTICS }
private enum class Role { USER, TEAM_LEAD, EMPLOYEE }

private data class Member(
    val name: String,
    val role: Role,
    val position: String,
    val prompt: String
)

private data class Team(
    val id: TeamId,
    val title: String,
    val lead: Member,
    val employees: List<Member>
)

private sealed class ChatRoomId {
    data object Main : ChatRoomId()
    data class TeamRoom(val teamId: TeamId) : ChatRoomId()
}

private data class ChatMessage(
    val id: String,
    val room: ChatRoomId,
    val author: String,
    val role: Role,
    val text: String,
    val timestamp: Long
)

private data class ConversationState(
    val main: MutableList<ChatMessage> = mutableListOf(),
    val teamThreads: MutableMap<TeamId, MutableList<ChatMessage>> = mutableMapOf(
        TeamId.DEV to mutableListOf(),
        TeamId.DESIGN to mutableListOf(),
        TeamId.ANALYTICS to mutableListOf()
    ),
    val activeRoom: ChatRoomId = ChatRoomId.Main
)

private val teamsSeed = listOf(
    Team(
        id = TeamId.DEV, title = "Разработка",
        lead = Member("Тимлид Разраб", Role.TEAM_LEAD, "Tech Lead Android", prompt = "Act as Android tech lead. Provide concise, actionable steps with trade-offs."),
        employees = listOf(
            Member("Иван", Role.EMPLOYEE, "Senior Android Developer", prompt = "Senior Android dev. Focus on feasibility, code-level steps."),
            Member("Максим", Role.EMPLOYEE, "Middle Android Developer", prompt = "Middle Android dev. Add implementation details and pitfalls.")
        )
    ),
    Team(
        id = TeamId.DESIGN, title = "Дизайн",
        lead = Member("Тимлид Дизайн", Role.TEAM_LEAD, "Lead Product Designer", prompt = "Lead designer. Propose UX options with pros/cons."),
        employees = listOf(
            Member("Ольга", Role.EMPLOYEE, "Senior Product Designer", prompt = "Senior designer. Provide guidelines alignment."),
            Member("Егор", Role.EMPLOYEE, "Middle UI Designer", prompt = "Middle UI designer. Provide specific UI states.")
        )
    ),
    Team(
        id = TeamId.ANALYTICS, title = "Аналитика",
        lead = Member("Тимлид Аналитика", Role.TEAM_LEAD, "Lead Analyst", prompt = "Lead analyst. Define metrics, success criteria."),
        employees = listOf(
            Member("Илья", Role.EMPLOYEE, "Junior Analyst", prompt = "Junior analyst. Ask clarifying questions."),
            Member("Анна", Role.EMPLOYEE, "Middle Data Analyst", prompt = "Middle data analyst. Add data collection plan.")
        )
    )
)

private object OpenRouterConfig {
    const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    val apiKey: String? get() = System.getenv("OPENROUTER_API_KEY")
    const val referer: String = "http://localhost"
    const val title: String = "AOSPBugreportAnalyzer"
}

private data class ORMessage(val role: String, val content: String)

private data class ORRequest(
    val model: String,
    val messages: List<ORMessage>,
    val response_format: Map<String, String>? = null,
    val temperature: Double? = 0.2
)

private const val SYSTEM_V3 = """
You are a concise teammate. Keep answers short and actionable.
If JSON mode is enabled, return ONLY valid JSON (UTF-8) with keys: version, ok, generated_at, items, error.
""".trimIndent()

private fun buildMessagesForMember(
    member: Member,
    team: Team,
    task: String,
    priorTeamMessages: List<ChatMessage>
): List<ORMessage> {
    val sys = ORMessage("system", SYSTEM_V3)
    val persona = ORMessage(
        "system",
        "Your name: ${member.name}. Role: ${member.position}. Persona: ${member.prompt}. Team: ${team.title}."
    )
    val contextTail = priorTeamMessages.takeLast(3).map {
        val role = if (it.role == Role.TEAM_LEAD || it.role == Role.EMPLOYEE) "user" else "system"
        ORMessage(role, "[${it.author}] ${it.text}")
    }
    val taskMsg = ORMessage("user", "Task: $task")
    return listOf(sys, persona) + contextTail + taskMsg
}

private val httpClient: HttpClient = HttpClient.newHttpClient()

private fun callOpenRouter(
    model: String,
    messages: List<ORMessage>,
    forceJson: Boolean,
    apiKeyOverride: String?
): String {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return jsonError("openrouter api key missing")
    }

    val request = ORRequest(
        model = model,
        messages = messages,
        response_format = if (forceJson) mapOf("type" to "json_object") else null,
        temperature = if (forceJson) 0.0 else 0.2
    )

    val body = buildString {
        append('{')
        append("\"model\":\"").append(encodeJsonString(request.model)).append('\"')
        append(",\"messages\":[")
        request.messages.forEachIndexed { index, msg ->
            if (index > 0) append(',')
            append('{')
            append("\"role\":\"").append(encodeJsonString(msg.role)).append('\"')
            append(",\"content\":\"").append(encodeJsonString(msg.content)).append('\"')
            append('}')
        }
        append(']')
        request.response_format?.let { format ->
            append(",\"response_format\":{\"type\":\"")
            append(encodeJsonString(format["type"] ?: "json_object"))
            append("\"}")
        }
        request.temperature?.let {
            append(",\"temperature\":")
            append(it)
        }
        append('}')
    }

    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(OpenRouterConfig.BASE_URL))
        .header("Authorization", "Bearer $key")
        .header("Content-Type", "application/json")
        .header("HTTP-Referer", OpenRouterConfig.referer)
        .header("X-Title", OpenRouterConfig.title)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    return try {
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            return jsonError("openrouter http ${response.statusCode()}")
        }
        val content = extractContentFromOpenAIJson(response.body())
        content ?: jsonError("openrouter empty content")
    } catch (t: Throwable) {
        jsonError(t.message ?: t::class.simpleName ?: "unknown error")
    }
}

private fun encodeJsonString(value: String): String {
    val sb = StringBuilder()
    value.forEach { c ->
        when (c) {
            '\\' -> sb.append("\\\\")
            '\"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun decodeJsonString(value: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (val next = value[i + 1]) {
                '\\', '\"', '/' -> {
                    sb.append(next)
                    i += 2
                }
                'b' -> {
                    sb.append('\b')
                    i += 2
                }
                'f' -> {
                    sb.append('\u000c')
                    i += 2
                }
                'n' -> {
                    sb.append('\n')
                    i += 2
                }
                'r' -> {
                    sb.append('\r')
                    i += 2
                }
                't' -> {
                    sb.append('\t')
                    i += 2
                }
                'u' -> {
                    if (i + 5 < value.length) {
                        val hex = value.substring(i + 2, i + 6)
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 6
                    } else {
                        i++
                    }
                }
                else -> {
                    sb.append(next)
                    i += 2
                }
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun extractContentFromOpenAIJson(body: String): String? {
    val regex = "\"content\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"".toRegex()
    val match = regex.find(body) ?: return null
    return decodeJsonString(match.groupValues[1]).ifBlank { null }
}

private fun jsonError(message: String): String {
    val escaped = encodeJsonString(message)
    return "{\"version\":\"1.0\",\"ok\":false,\"generated_at\":\"\",\"items\":[],\"error\":\"$escaped\"}"
}

private fun ConversationState.clone(activeRoomOverride: ChatRoomId = activeRoom): ConversationState {
    val mainCopy = main.toMutableList()
    val threadsCopy = mutableMapOf<TeamId, MutableList<ChatMessage>>()
    for (teamId in TeamId.values()) {
        threadsCopy[teamId] = (teamThreads[teamId] ?: mutableListOf()).toMutableList()
    }
    return ConversationState(mainCopy, threadsCopy, activeRoomOverride)
}

private fun ConversationState.addMainMessage(message: ChatMessage): ConversationState {
    val copy = clone()
    copy.main.add(message)
    return copy
}

private fun ConversationState.addTeamMessage(teamId: TeamId, message: ChatMessage): ConversationState {
    val copy = clone()
    val list = copy.teamThreads.getValue(teamId)
    list.add(message)
    return copy
}

private fun ConversationState.withActiveRoom(room: ChatRoomId): ConversationState = clone(room)

private class TeamLLMOrchestrator(
    private val model: String,
    private val forceJson: Boolean,
    private val teams: List<Team>,
    private val getState: () -> ConversationState,
    private val setState: (ConversationState) -> Unit,
    private val apiKeyProvider: () -> String?
) {
    private val maxTeamMsgs = 10
    private val maxMainDebateMsgs = 10
    private val mutex = Mutex()

    suspend fun runTeamRound(teamId: TeamId, task: String) {
        val team = teams.first { it.id == teamId }
        val currentSize = mutex.withLock { getState().teamThreads.getValue(teamId).size }
        if (currentSize >= maxTeamMsgs) return

        suspend fun addFrom(member: Member) {
            val context = mutex.withLock {
                val thread = getState().teamThreads.getValue(teamId)
                if (thread.size >= maxTeamMsgs) return
                thread.toList()
            }
            val msgs = buildMessagesForMember(member, team, task, context)
            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider())
            val newState = mutex.withLock {
                val snapshot = getState()
                val thread = snapshot.teamThreads.getValue(teamId)
                if (thread.size >= maxTeamMsgs) return
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.TeamRoom(teamId),
                    author = member.name,
                    role = member.role,
                    text = content,
                    timestamp = System.currentTimeMillis()
                )
                snapshot.addTeamMessage(teamId, message)
            }
            if (newState != null) {
                withContext(Dispatchers.Main) {
                    setState(newState)
                }
            }
        }

        val emp1 = team.employees.getOrNull(0)
        val emp2 = team.employees.getOrNull(1)

        addFrom(team.lead)
        if (!reachedTeamLimit(teamId) && emp1 != null) addFrom(emp1)
        if (!reachedTeamLimit(teamId) && emp2 != null) addFrom(emp2)
        if (!reachedTeamLimit(teamId)) addFrom(team.lead)
    }

    suspend fun postTeamSummariesToMain(task: String) {
        for (team in teams) {
            val msgs = listOf(
                ORMessage("system", SYSTEM_V3),
                ORMessage("system", "You are ${team.lead.name}, ${team.lead.position}."),
                ORMessage("user", "Summarize your team's solution for the main room in 2–3 sentences. Task: $task")
            )
            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider())
            val newState = mutex.withLock {
                val snapshot = getState()
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = team.lead.name,
                    role = Role.TEAM_LEAD,
                    text = content,
                    timestamp = System.currentTimeMillis()
                )
                snapshot.addMainMessage(message)
            }
            withContext(Dispatchers.Main) {
                setState(newState)
            }
        }
    }

    suspend fun runLeadsDebate(task: String) {
        val dev = teams.first { it.id == TeamId.DEV }
        val design = teams.first { it.id == TeamId.DESIGN }
        val analytics = teams.first { it.id == TeamId.ANALYTICS }

        suspend fun leaderReply(team: Team, cue: String) {
            val context = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == Role.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return
                snapshot.main.takeLast(4)
            }
            val msgs = mutableListOf(
                ORMessage("system", SYSTEM_V3),
                ORMessage("system", "You are ${team.lead.name}, ${team.lead.position}. Debate briefly.")
            )
            msgs += context.map { ORMessage("user", "[${it.author}] ${it.text}") }
            msgs += ORMessage("user", "Task: $task. Respond: $cue")

            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider())
            val newState = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == Role.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = team.lead.name,
                    role = Role.TEAM_LEAD,
                    text = content,
                    timestamp = System.currentTimeMillis()
                )
                snapshot.addMainMessage(message)
            }
            if (newState != null) {
                withContext(Dispatchers.Main) {
                    setState(newState)
                }
            }
        }

        leaderReply(dev, "Argue for solution A briefly.")
        leaderReply(design, "Add design constraints briefly.")
        leaderReply(analytics, "Add analytics KPIs briefly.")
    }

    suspend fun sendLeadMessage(teamId: TeamId, task: String) {
        val team = teams.first { it.id == teamId }
        val context = mutex.withLock { getState().teamThreads.getValue(teamId).toList() }
        val msgs = buildMessagesForMember(team.lead, team, task, context)
        val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider())
        val newState = mutex.withLock {
            val snapshot = getState()
            val thread = snapshot.teamThreads.getValue(teamId)
            if (thread.size >= maxTeamMsgs) return
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                room = ChatRoomId.TeamRoom(teamId),
                author = team.lead.name,
                role = team.lead.role,
                text = content,
                timestamp = System.currentTimeMillis()
            )
            snapshot.addTeamMessage(teamId, message)
        }
        if (newState != null) {
            withContext(Dispatchers.Main) {
                setState(newState)
            }
        }
    }

    fun reachedTeamLimit(teamId: TeamId): Boolean =
        getState().teamThreads.getValue(teamId).size >= maxTeamMsgs
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(timestamp: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(timestamp))

@OptIn(ExperimentalMaterial3Api::class)
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOSP Bugreport Analyzer — Команды") {
        MaterialTheme {
            DesktopChatApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopChatApp() {
    val scope = rememberCoroutineScope()
    val teams = remember { teamsSeed }
    var state by remember { mutableStateOf(ConversationState()) }
    var apiKey by remember { mutableStateOf(OpenRouterConfig.apiKey.orEmpty()) }
    var model by remember { mutableStateOf("openai/gpt-4o-mini") }
    var forceJson by remember { mutableStateOf(false) }
    var taskInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var lastTask by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    val apiKeyState by rememberUpdatedState(apiKey)

    val orchestrator = remember(teams, model, forceJson) {
        TeamLLMOrchestrator(
            model = model,
            forceJson = forceJson,
            teams = teams,
            getState = { state },
            setState = { newState -> state = newState },
            apiKeyProvider = { apiKeyState.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey }
        )
    }

    fun appendUserMessage(text: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            room = ChatRoomId.Main,
            author = "USER",
            role = Role.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        state = state.addMainMessage(message).withActiveRoom(ChatRoomId.Main)
    }

    fun sendTask() {
        val task = taskInput.trim()
        if (task.isEmpty() || isProcessing) return
        val resolvedKey = apiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (resolvedKey.isNullOrBlank()) {
            appendUserMessage("OPENROUTER_API_KEY is not set")
            taskInput = ""
            return
        }
        appendUserMessage(task)
        taskInput = ""
        lastTask = task
        isProcessing = true
        val jobs = TeamId.values().map { teamId ->
            scope.launch(Dispatchers.IO) {
                orchestrator.runTeamRound(teamId, task)
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                jobs.joinAll()
                orchestrator.postTeamSummariesToMain(task)
                orchestrator.runLeadsDebate(task)
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if ((apiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey).isNullOrBlank()) {
            appendUserMessage("Задайте OPENROUTER_API_KEY, чтобы команды могли отвечать.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Командный чат — OpenRouter") })
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionSettings(
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                model = model,
                onModelChange = { model = it },
                forceJson = forceJson,
                onForceJsonChange = { forceJson = it },
                showApiKey = showApiKey,
                onToggleApiVisibility = { showApiKey = !showApiKey }
            )

            RoomNavigation(
                teams = teams,
                activeRoom = state.activeRoom,
                onSelect = { room -> state = state.withActiveRoom(room) }
            )

            val messages = when (val room = state.activeRoom) {
                ChatRoomId.Main -> state.main.toList()
                is ChatRoomId.TeamRoom -> state.teamThreads[room.teamId]?.toList().orEmpty()
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                MessageList(messages)
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            when (val room = state.activeRoom) {
                ChatRoomId.Main -> {
                    OutlinedTextField(
                        value = taskInput,
                        onValueChange = { taskInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Задача для команд") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { sendTask() },
                            enabled = taskInput.isNotBlank() && !isProcessing
                        ) {
                            Text("Отправить в команды")
                        }
                        if (lastTask.isNotBlank()) {
                            Text("Текущая задача: ${lastTask}")
                        }
                    }
                }
                is ChatRoomId.TeamRoom -> {
                    val team = teams.first { it.id == room.teamId }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Внутренний чат команды \"${team.title}\"")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Лимит: ${state.teamThreads[team.id]?.size ?: 0} / 10")
                            TextButton(
                                onClick = {
                                    if (lastTask.isNotBlank() && !isProcessing) {
                                        scope.launch(Dispatchers.IO) {
                                            orchestrator.sendLeadMessage(team.id, lastTask)
                                        }
                                    }
                                },
                                enabled = lastTask.isNotBlank() && !isProcessing && !orchestrator.reachedTeamLimit(team.id)
                            ) {
                                Text("Отправить (тимлид)")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionSettings(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    forceJson: Boolean,
    onForceJsonChange: (Boolean) -> Unit,
    showApiKey: Boolean,
    onToggleApiVisibility: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Настройки OpenRouter", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleApiVisibility) {
                    Icon(
                        imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showApiKey) "Скрыть ключ" else "Показать ключ"
                    )
                }
            }
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text("Модель") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Switch(checked = forceJson, onCheckedChange = onForceJsonChange)
            Text("Строгий JSON (response_format)")
        }
    }
}

@Composable
private fun RoomNavigation(
    teams: List<Team>,
    activeRoom: ChatRoomId,
    onSelect: (ChatRoomId) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onSelect(ChatRoomId.Main) },
            enabled = activeRoom !is ChatRoomId.Main
        ) { Text("Главный чат") }
        teams.forEach { team ->
            val room = ChatRoomId.TeamRoom(team.id)
            Button(
                onClick = { onSelect(room) },
                enabled = activeRoom != room
            ) { Text(team.title) }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    if (messages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Сообщений пока нет")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${formatTimestamp(message.timestamp)} · ${message.author} (${message.role.name})",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(message.text)
            }
        }
    }
}
