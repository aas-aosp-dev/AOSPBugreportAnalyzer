package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.Locale
import java.util.UUID

private enum class TeamDirection { DEVELOPMENT, DESIGN, ANALYTICS }
private enum class MemberRole { TEAM_LEAD, EMPLOYEE }
private enum class AuthorRole { USER, TEAM_LEAD, EMPLOYEE }

private enum class Screen { MAIN, SETTINGS, TEAMS }

private data class Member(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: MemberRole,
    val position: String,
    val temperature: Double,
    val prompt: String
)

private data class Team(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val direction: TeamDirection,
    val members: MutableList<Member> = mutableListOf()
)

private sealed class ChatRoomId {
    data object Main : ChatRoomId()
    data class TeamRoom(val teamId: String) : ChatRoomId()
}

private data class ChatMessage(
    val id: String,
    val room: ChatRoomId,
    val author: String,
    val role: AuthorRole,
    val text: String,
    val timestamp: Long
)

private data class ConversationState(
    val main: MutableList<ChatMessage> = mutableListOf(),
    val teamThreads: MutableMap<String, MutableList<ChatMessage>> = mutableMapOf(),
    val activeRoom: ChatRoomId = ChatRoomId.Main
)

private fun Team.deepCopy(): Team = copy(members = members.map { it.copy() }.toMutableList())

private fun TeamDirection.displayName(): String = when (this) {
    TeamDirection.DEVELOPMENT -> "Разработка"
    TeamDirection.DESIGN -> "Дизайн"
    TeamDirection.ANALYTICS -> "Аналитика"
}

private fun MemberRole.displayName(): String = when (this) {
    MemberRole.TEAM_LEAD -> "Тимлид"
    MemberRole.EMPLOYEE -> "Сотрудник"
}

private fun AuthorRole.displayName(): String = when (this) {
    AuthorRole.USER -> "Пользователь"
    AuthorRole.TEAM_LEAD -> "Тимлид"
    AuthorRole.EMPLOYEE -> "Сотрудник"
}

private fun defaultTeams(): List<Team> {
    val development = Team(
        title = "Разработка",
        direction = TeamDirection.DEVELOPMENT,
        members = mutableListOf(
            Member(
                name = "Иван",
                role = MemberRole.TEAM_LEAD,
                position = "Senior Android разработчик с большим опытом. Холодный ум, делает очень качественно.",
                temperature = 0.0,
                prompt = "Act as Android tech lead. Be rigorous and practical."
            ),
            Member(
                name = "Максим",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик. В первую очередь думает об архитектуре.",
                temperature = 0.0,
                prompt = "Architect-first mindset. Suggest clean, maintainable designs."
            ),
            Member(
                name = "Геннадий",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик. Не любит сложные архитектуры, делает быстро, меньше абстракций.",
                temperature = 0.0,
                prompt = "Prefer simple, fast solutions. Avoid over-abstraction."
            ),
            Member(
                name = "Юрия",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик. За безопасность, следит за утечками и рисками.",
                temperature = 0.0,
                prompt = "Security-first. Enforce safe patterns and data protection."
            )
        )
    )

    val design = Team(
        title = "Дизайн",
        direction = TeamDirection.DESIGN,
        members = mutableListOf(
            Member(
                name = "Мария",
                role = MemberRole.TEAM_LEAD,
                position = "Лид-дизайнер. Системное мышление, соответствие гайдам, UX-рисерч.",
                temperature = 0.0,
                prompt = "Lead designer. Ensure guideline alignment and UX clarity."
            ),
            Member(
                name = "Ольга",
                role = MemberRole.EMPLOYEE,
                position = "Senior дизайнер. UX-паттерны, accessibility.",
                temperature = 0.0,
                prompt = "Senior designer. Accessibility and patterns."
            ),
            Member(
                name = "Дмитрий",
                role = MemberRole.EMPLOYEE,
                position = "Middle UI дизайнер. Состояния, микровзаимодействия.",
                temperature = 0.0,
                prompt = "Middle UI. States and micro-interactions."
            )
        )
    )

    val analytics = Team(
        title = "Аналитика",
        direction = TeamDirection.ANALYTICS,
        members = mutableListOf(
            Member(
                name = "Алексей",
                role = MemberRole.TEAM_LEAD,
                position = "Лид-аналитик. Метрики успеха, риски, A/B.",
                temperature = 0.0,
                prompt = "Lead analyst. Define KPIs and evaluate risks."
            ),
            Member(
                name = "Ирина",
                role = MemberRole.EMPLOYEE,
                position = "Junior аналитик. Уточняющие вопросы.",
                temperature = 0.0,
                prompt = "Junior analyst. Ask clarifying questions."
            ),
            Member(
                name = "Сергей",
                role = MemberRole.EMPLOYEE,
                position = "Middle data analyst. План сбора данных, дашборды.",
                temperature = 0.0,
                prompt = "Data collection plan and dashboards."
            )
        )
    )

    return listOf(development, design, analytics)
}

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
    val temperature: Double
)

private val SYSTEM_TEXT = """
You are a concise teammate. Keep answers short, actionable, and plain text without extra formatting.
""".trimIndent()

private val SYSTEM_JSON = """
You are a concise teammate. Keep answers short and actionable.
Return ONLY valid JSON (UTF-8) with keys: version, ok, generated_at, items, error.
""".trimIndent()

data class AppSettings(
    val openRouterApiKey: String = System.getenv("OPENROUTER_API_KEY") ?: "",
    val openRouterModel: String = "openai/gpt-4o-mini",
    val strictJsonEnabled: Boolean = false
)

private fun selectSystemPrompt(forceJson: Boolean): String = if (forceJson) SYSTEM_JSON else SYSTEM_TEXT

private fun buildMessagesForMember(
    member: Member,
    team: Team,
    task: String,
    priorTeamMessages: List<ChatMessage>,
    systemPrompt: String
): List<ORMessage> {
    val sys = ORMessage("system", systemPrompt)
    val persona = ORMessage(
        "system",
        "Your name: ${member.name}. Role: ${member.role.displayName()} (${member.position}). Persona: ${member.prompt}. Team: ${team.title} (${team.direction.displayName()})."
    )
    val contextTail = priorTeamMessages.takeLast(3).map {
        val role = if (it.role == AuthorRole.TEAM_LEAD || it.role == AuthorRole.EMPLOYEE) "user" else "system"
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
    apiKeyOverride: String?,
    temperature: Double
): String {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return errorResponse(forceJson, "openrouter api key missing")
    }

    val request = ORRequest(
        model = model,
        messages = messages,
        response_format = if (forceJson) mapOf("type" to "json_object") else null,
        temperature = temperature.coerceIn(0.0, 2.0)
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
        append(",\"temperature\":")
        append(String.format(Locale.US, "%.2f", request.temperature))
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
            return errorResponse(forceJson, "openrouter http ${response.statusCode()}")
        }
        val content = extractContentFromOpenAIJson(response.body())
        content ?: errorResponse(forceJson, "openrouter empty content")
    } catch (t: Throwable) {
        errorResponse(forceJson, t.message ?: t::class.simpleName ?: "unknown error")
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

private fun errorResponse(forceJson: Boolean, message: String): String =
    if (forceJson) jsonError(message) else "Error: $message"

private fun ConversationState.clone(activeRoomOverride: ChatRoomId = activeRoom): ConversationState {
    val mainCopy = main.toMutableList()
    val threadsCopy = mutableMapOf<String, MutableList<ChatMessage>>()
    for ((teamId, messages) in teamThreads) {
        threadsCopy[teamId] = messages.toMutableList()
    }
    return ConversationState(mainCopy, threadsCopy, activeRoomOverride)
}

private fun ConversationState.addMainMessage(message: ChatMessage): ConversationState {
    val copy = clone()
    copy.main.add(message)
    return copy
}

private fun ConversationState.addTeamMessage(teamId: String, message: ChatMessage): ConversationState {
    val copy = clone()
    val list = copy.teamThreads.getOrPut(teamId) { mutableListOf() }
    list.add(message)
    return copy
}

private fun ConversationState.withActiveRoom(room: ChatRoomId): ConversationState = clone(room)

private fun ConversationState.syncWithTeams(teams: List<Team>): ConversationState {
    val teamIds = teams.map { it.id }.toSet()
    val active = when (val room = activeRoom) {
        is ChatRoomId.TeamRoom -> if (room.teamId in teamIds) room else ChatRoomId.Main
        ChatRoomId.Main -> room
    }
    val copy = clone(active)
    for (teamId in teamIds) {
        if (!copy.teamThreads.containsKey(teamId)) {
            copy.teamThreads[teamId] = mutableListOf()
        }
    }
    val iterator = copy.teamThreads.keys.iterator()
    while (iterator.hasNext()) {
        val id = iterator.next()
        if (id !in teamIds) {
            iterator.remove()
        }
    }
    return copy
}

private class TeamLLMOrchestrator(
    private val model: String,
    private val forceJson: Boolean,
    private val getTeams: () -> List<Team>,
    private val getState: () -> ConversationState,
    private val setState: (ConversationState) -> Unit,
    private val apiKeyProvider: () -> String?
) {
    private val maxTeamMsgs = 10
    private val maxMainDebateMsgs = 10
    private val mutex = Mutex()
    private val systemPrompt = selectSystemPrompt(forceJson)

    suspend fun runTeamRound(teamId: String, task: String) {
        val team = getTeams().firstOrNull { it.id == teamId } ?: return
        val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: return
        val employees = team.members.filter { it.role == MemberRole.EMPLOYEE }

        suspend fun addFrom(member: Member) {
            val context = mutex.withLock {
                val thread = getState().teamThreads[teamId]
                if (thread != null && thread.size >= maxTeamMsgs) return
                thread?.toList().orEmpty()
            }
            val msgs = buildMessagesForMember(member, team, task, context, systemPrompt)
            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), member.temperature)
            val newState = mutex.withLock {
                val snapshot = getState()
                val thread = snapshot.teamThreads[teamId]
                if (thread != null && thread.size >= maxTeamMsgs) return@withLock null
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.TeamRoom(teamId),
                    author = member.name,
                    role = if (member.role == MemberRole.TEAM_LEAD) AuthorRole.TEAM_LEAD else AuthorRole.EMPLOYEE,
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

        addFrom(lead)
        for (employee in employees) {
            if (reachedTeamLimit(teamId)) break
            addFrom(employee)
        }
        if (!reachedTeamLimit(teamId)) {
            addFrom(lead)
        }
    }

    suspend fun postTeamSummariesToMain(task: String) {
        for (team in getTeams()) {
            val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: continue
            val msgs = listOf(
                ORMessage("system", systemPrompt),
                ORMessage(
                    "system",
                    "You are ${lead.name}, ${lead.position}. Team: ${team.title} (${team.direction.displayName()})."
                ),
                ORMessage("user", "Summarize your team's solution for the main room in 2–3 sentences. Task: $task")
            )
            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), lead.temperature)
            val newState = mutex.withLock {
                val snapshot = getState()
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = lead.name,
                    role = AuthorRole.TEAM_LEAD,
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
        val teamsSnapshot = getTeams()
        suspend fun leaderReply(team: Team, cue: String) {
            val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: return
            val context = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == AuthorRole.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return
                snapshot.main.takeLast(4)
            }
            val msgs = mutableListOf(
                ORMessage("system", systemPrompt),
                ORMessage(
                    "system",
                    "You are ${lead.name}, ${lead.position}. Debate as ${team.direction.displayName()} lead."
                )
            )
            msgs += context.map { ORMessage("user", "[${it.author}] ${it.text}") }
            msgs += ORMessage("user", "Task: $task. Respond: $cue")

            val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), lead.temperature)
            val newState = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == AuthorRole.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return@withLock null
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = lead.name,
                    role = AuthorRole.TEAM_LEAD,
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

        val cues = mapOf(
            TeamDirection.DEVELOPMENT to "Argue for the technical solution briefly.",
            TeamDirection.DESIGN to "Highlight design constraints and UX considerations briefly.",
            TeamDirection.ANALYTICS to "Add analytics KPIs and risk checks briefly."
        )
        for (team in teamsSnapshot) {
            if (reachedMainLimit()) break
            val cue = cues[team.direction] ?: "Add your team's perspective briefly."
            leaderReply(team, cue)
        }
    }

    suspend fun sendLeadMessage(teamId: String, task: String) {
        val team = getTeams().firstOrNull { it.id == teamId } ?: return
        val lead = team.members.firstOrNull { it.role == MemberRole.TEAM_LEAD } ?: return
        val context = mutex.withLock { getState().teamThreads[teamId]?.toList().orEmpty() }
        val msgs = buildMessagesForMember(lead, team, task, context, systemPrompt)
        val content = callOpenRouter(model, msgs, forceJson, apiKeyProvider(), lead.temperature)
        val newState = mutex.withLock {
            val snapshot = getState()
            val thread = snapshot.teamThreads[teamId]
            if (thread != null && thread.size >= maxTeamMsgs) return@withLock null
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                room = ChatRoomId.TeamRoom(teamId),
                author = lead.name,
                role = AuthorRole.TEAM_LEAD,
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

    fun reachedTeamLimit(teamId: String): Boolean =
        getState().teamThreads[teamId]?.size ?: 0 >= maxTeamMsgs

    private fun reachedMainLimit(): Boolean =
        getState().main.count { it.role == AuthorRole.TEAM_LEAD } >= maxMainDebateMsgs
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
    val initialTeams = remember { defaultTeams() }
    var teams by remember { mutableStateOf(initialTeams) }
    var state by remember { mutableStateOf(ConversationState().syncWithTeams(initialTeams)) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var taskInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var lastTask by remember { mutableStateOf("") }
    var selectedTeamId by remember { mutableStateOf(initialTeams.firstOrNull()?.id) }
    var memberEditorTeamId by remember { mutableStateOf<String?>(null) }
    var editingMember by remember { mutableStateOf<Member?>(null) }
    var deleteTeamId by remember { mutableStateOf<String?>(null) }
    var teamsUiMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val apiKeyState by rememberUpdatedState(settings.openRouterApiKey)

    fun setTeamsMessage(text: String, isError: Boolean) {
        teamsUiMessage = text to isError
    }

    fun updateTeams(transform: (List<Team>) -> List<Team>) {
        val updated = transform(teams)
        val sorted = updated
            .map { it.deepCopy() }
            .sortedWith(compareBy<Team>({ it.direction.ordinal }, { it.title }))
        teams = sorted
        state = state.syncWithTeams(sorted)
        if (selectedTeamId != null && sorted.none { it.id == selectedTeamId }) {
            selectedTeamId = sorted.firstOrNull()?.id
        }
    }

    val orchestrator = remember(settings.openRouterModel, settings.strictJsonEnabled) {
        TeamLLMOrchestrator(
            model = settings.openRouterModel,
            forceJson = settings.strictJsonEnabled,
            getTeams = { teams },
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
            role = AuthorRole.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        state = state.addMainMessage(message).withActiveRoom(ChatRoomId.Main)
    }

    fun handleSaveMember(teamId: String, updated: Member, previous: Member?): Boolean {
        val team = teams.firstOrNull { it.id == teamId } ?: return false
        val members = team.members.toMutableList()
        val existingIndex = members.indexOfFirst { it.id == updated.id }
        if (previous != null && previous.role == MemberRole.TEAM_LEAD && updated.role != MemberRole.TEAM_LEAD) {
            if (members.count { it.id != updated.id && it.role == MemberRole.TEAM_LEAD } < 1) {
                setTeamsMessage("В команде должен оставаться тимлид.", true)
                return false
            }
        }
        if (existingIndex >= 0) {
            members[existingIndex] = updated
        } else {
            members.add(updated)
        }
        if (updated.role == MemberRole.TEAM_LEAD) {
            for (i in members.indices) {
                val member = members[i]
                if (member.id != updated.id && member.role == MemberRole.TEAM_LEAD) {
                    members[i] = member.copy(role = MemberRole.EMPLOYEE)
                }
            }
        }
        if (members.count { it.role == MemberRole.TEAM_LEAD } != 1) {
            setTeamsMessage("В команде должен быть ровно один тимлид.", true)
            return false
        }
        val newTeam = team.copy(members = members.map { it.copy() }.toMutableList())
        updateTeams { current -> current.map { if (it.id == teamId) newTeam else it } }
        setTeamsMessage("Сотрудник сохранён", false)
        return true
    }

    fun handleDeleteMember(teamId: String, memberId: String): Boolean {
        val team = teams.firstOrNull { it.id == teamId } ?: return false
        val members = team.members.toMutableList()
        val index = members.indexOfFirst { it.id == memberId }
        if (index < 0) return false
        if (members[index].role == MemberRole.TEAM_LEAD) {
            setTeamsMessage("Нельзя удалить единственного тимлида.", true)
            return false
        }
        members.removeAt(index)
        if (members.count { it.role == MemberRole.TEAM_LEAD } != 1) {
            setTeamsMessage("В команде должен оставаться тимлид.", true)
            return false
        }
        val newTeam = team.copy(members = members.map { it.copy() }.toMutableList())
        updateTeams { current -> current.map { if (it.id == teamId) newTeam else it } }
        setTeamsMessage("Сотрудник удалён", false)
        return true
    }

    fun createTeam() {
        val newLead = Member(
            name = "Новый тимлид",
            role = MemberRole.TEAM_LEAD,
            position = "Опишите роль тимлида",
            temperature = 0.0,
            prompt = "Describe leadership style and responsibilities."
        )
        val newTeam = Team(
            title = "Новая команда",
            direction = TeamDirection.DEVELOPMENT,
            members = mutableListOf(newLead)
        )
        updateTeams { it + newTeam }
        selectedTeamId = newTeam.id
        setTeamsMessage("Команда создана", false)
    }

    fun deleteTeam(teamId: String) {
        updateTeams { current -> current.filterNot { it.id == teamId } }
        setTeamsMessage("Команда удалена", false)
    }

    fun sendTask() {
        val task = taskInput.trim()
        if (task.isEmpty() || isProcessing) return
        val resolvedKey = settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
        if (resolvedKey.isNullOrBlank()) {
            appendUserMessage("OPENROUTER_API_KEY is not set")
            taskInput = ""
            return
        }
        appendUserMessage(task)
        taskInput = ""
        lastTask = task
        isProcessing = true
        val teamIds = teams.map { it.id }
        val jobs = teamIds.map { teamId ->
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
        if ((settings.openRouterApiKey.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey).isNullOrBlank()) {
            appendUserMessage("Задайте OPENROUTER_API_KEY, чтобы команды могли отвечать.")
        }
    }

    LaunchedEffect(teamsUiMessage) {
        if (teamsUiMessage != null) {
            delay(2500)
            teamsUiMessage = null
        }
    }

    when (screen) {
        Screen.MAIN -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Командный чат — OpenRouter") },
                        actions = {
                            TextButton(onClick = { screen = Screen.TEAMS }) {
                                Text("Команды")
                            }
                            TextButton(onClick = { screen = Screen.SETTINGS }) {
                                Text("Настройки")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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
                        if (state.activeRoom is ChatRoomId.TeamRoom) {
                            val teamId = (state.activeRoom as ChatRoomId.TeamRoom).teamId
                            val team = teams.firstOrNull { it.id == teamId }
                            if (team == null || team.members.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Нет сотрудников")
                                }
                            } else {
                                MessageList(messages)
                            }
                        } else {
                            MessageList(messages)
                        }
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
                                    Text("Текущая задача: $lastTask")
                                }
                            }
                        }
                        is ChatRoomId.TeamRoom -> {
                            val team = teams.firstOrNull { it.id == room.teamId }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Внутренний чат команды \"${team?.title ?: ""}\"")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val count = state.teamThreads[room.teamId]?.size ?: 0
                                    Text("Лимит: $count / 10")
                                    TextButton(
                                        onClick = {
                                            if (lastTask.isNotBlank() && !isProcessing && team != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    orchestrator.sendLeadMessage(team.id, lastTask)
                                                }
                                            }
                                        },
                                        enabled = lastTask.isNotBlank() && !isProcessing && team != null && !orchestrator.reachedTeamLimit(team.id)
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
        Screen.SETTINGS -> {
            SettingsScreen(
                settings = settings,
                onChange = { settings = it },
                onClose = { screen = Screen.MAIN }
            )
        }
        Screen.TEAMS -> {
            TeamsScreen(
                teams = teams,
                selectedTeamId = selectedTeamId,
                message = teamsUiMessage,
                onSelectTeam = { selectedTeamId = it },
                onCreateTeam = { createTeam() },
                onDeleteTeam = { teamId -> deleteTeamId = teamId },
                onUpdateTeamTitle = { teamId, title ->
                    updateTeams { list ->
                        list.map { team -> if (team.id == teamId) team.copy(title = title) else team }
                    }
                },
                onUpdateTeamDirection = { teamId, direction ->
                    updateTeams { list ->
                        list.map { team -> if (team.id == teamId) team.copy(direction = direction) else team }
                    }
                },
                onAddMember = { teamId ->
                    memberEditorTeamId = teamId
                    editingMember = null
                },
                onEditMember = { teamId, member ->
                    memberEditorTeamId = teamId
                    editingMember = member
                },
                onRemoveMember = { teamId, member ->
                    handleDeleteMember(teamId, member.id)
                },
                onBack = { screen = Screen.MAIN }
            )
        }
    }

    if (memberEditorTeamId != null) {
        val teamId = memberEditorTeamId
        MemberEditorDialog(
            member = editingMember,
            onDismiss = {
                memberEditorTeamId = null
                editingMember = null
            },
            onSave = { member ->
                if (teamId != null) {
                    val success = handleSaveMember(teamId, member, editingMember)
                    if (success) {
                        memberEditorTeamId = null
                        editingMember = null
                    }
                }
            },
            onDelete = if (editingMember != null) {
                {
                    if (teamId != null) {
                        val success = handleDeleteMember(teamId, editingMember!!.id)
                        if (success) {
                            memberEditorTeamId = null
                            editingMember = null
                        }
                    }
                }
            } else null
        )
    }

    if (deleteTeamId != null) {
        val team = teams.firstOrNull { it.id == deleteTeamId }
        if (team != null) {
            AlertDialog(
                onDismissRequest = { deleteTeamId = null },
                title = { Text("Удалить команду") },
                text = { Text("Вы уверены, что хотите удалить команду \"${team.title}\"?") },
                confirmButton = {
                    Button(onClick = {
                        deleteTeam(team.id)
                        deleteTeamId = null
                    }) {
                        Text("Удалить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTeamId = null }) {
                        Text("Отмена")
                    }
                }
            )
        } else {
            deleteTeamId = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onClose: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Настройки OpenRouter") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = draft.openRouterApiKey,
                onValueChange = { draft = draft.copy(openRouterApiKey = it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showApiKey) "Скрыть ключ" else "Показать ключ"
                        )
                    }
                }
            )
            OutlinedTextField(
                value = draft.openRouterModel,
                onValueChange = { draft = draft.copy(openRouterModel = it) },
                label = { Text("Модель") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = draft.strictJsonEnabled,
                    onCheckedChange = { draft = draft.copy(strictJsonEnabled = it) }
                )
                Text("Строгий JSON (response_format)")
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    onChange(draft)
                    onClose()
                }) {
                    Text("Сохранить")
                }
                TextButton(onClick = onClose) {
                    Text("Назад")
                }
            }
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
    val clipboard = LocalClipboardManager.current
    var lastCopiedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastCopiedId) {
        if (lastCopiedId != null) {
            delay(2000)
            lastCopiedId = null
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${formatTimestamp(message.timestamp)} · ${message.author} (${message.role.displayName()})",
                    style = MaterialTheme.typography.labelSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        lastCopiedId = message.id
                    }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Скопировать")
                    }
                }
                if (lastCopiedId == message.id) {
                    Text(
                        text = "Скопировано",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamsScreen(
    teams: List<Team>,
    selectedTeamId: String?,
    message: Pair<String, Boolean>?,
    onSelectTeam: (String?) -> Unit,
    onCreateTeam: () -> Unit,
    onDeleteTeam: (String) -> Unit,
    onUpdateTeamTitle: (String, String) -> Unit,
    onUpdateTeamDirection: (String, TeamDirection) -> Unit,
    onAddMember: (String) -> Unit,
    onEditMember: (String, Member) -> Unit,
    onRemoveMember: (String, Member) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Управление командами") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Назад")
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onCreateTeam) { Text("Создать команду") }
                    TextButton(
                        onClick = {
                            selectedTeamId?.let(onDeleteTeam)
                        },
                        enabled = selectedTeamId != null
                    ) {
                        Text("Удалить команду")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(teams, key = { it.id }) { team ->
                        val isSelected = team.id == selectedTeamId
                        Button(
                            onClick = { onSelectTeam(team.id) },
                            enabled = !isSelected,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(team.title)
                                Text(
                                    text = team.direction.displayName(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            val team = teams.firstOrNull { it.id == selectedTeamId }
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (message != null) {
                    val color = if (message.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    Text(message.first, color = color)
                }
                if (team == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Выберите команду слева")
                    }
                } else {
                    OutlinedTextField(
                        value = team.title,
                        onValueChange = { onUpdateTeamTitle(team.id, it) },
                        label = { Text("Название команды") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    var directionExpanded by remember(team.id) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = directionExpanded,
                        onExpandedChange = { directionExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = team.direction.displayName(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Направление") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = directionExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = directionExpanded,
                            onDismissRequest = { directionExpanded = false }
                        ) {
                            TeamDirection.values().forEach { direction ->
                                DropdownMenuItem(
                                    text = { Text(direction.displayName()) },
                                    onClick = {
                                        onUpdateTeamDirection(team.id, direction)
                                        directionExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    val leadCount = team.members.count { it.role == MemberRole.TEAM_LEAD }
                    if (leadCount != 1) {
                        Text(
                            text = "Внимание: в команде должен быть ровно один тимлид.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Имя", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Text("Роль", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
                            Text("Должность", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
                            Text("T", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                            Text("Prompt", modifier = Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.width(96.dp))
                        }
                        team.members.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(member.name, modifier = Modifier.weight(1f))
                                Text(member.role.displayName(), modifier = Modifier.weight(0.6f))
                                Text(member.position, modifier = Modifier.weight(1.2f))
                                Text(
                                    text = String.format(Locale.US, "%.1f", member.temperature),
                                    modifier = Modifier.width(48.dp)
                                )
                                Text(member.prompt, modifier = Modifier.weight(1.6f))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onEditMember(team.id, member) }) {
                                        Text("Ред.")
                                    }
                                    TextButton(onClick = { onRemoveMember(team.id, member) }) {
                                        Text("Удалить")
                                    }
                                }
                            }
                        }
                        if (team.members.isEmpty()) {
                            Text("В команде пока нет сотрудников")
                        }
                        Button(onClick = { onAddMember(team.id) }) {
                            Text("Добавить сотрудника")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberEditorDialog(
    member: Member?,
    onDismiss: () -> Unit,
    onSave: (Member) -> Unit,
    onDelete: (() -> Unit)?
) {
    val isEditing = member != null
    var name by remember(member) { mutableStateOf(member?.name ?: "") }
    var role by remember(member) { mutableStateOf(member?.role ?: MemberRole.EMPLOYEE) }
    var position by remember(member) { mutableStateOf(member?.position ?: "") }
    var prompt by remember(member) { mutableStateOf(member?.prompt ?: "") }
    var temperature by remember(member) { mutableStateOf(member?.temperature ?: 0.0) }
    var temperatureText by remember(member) { mutableStateOf(String.format(Locale.US, "%.1f", temperature)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Редактирование сотрудника" else "Новый сотрудник") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MemberRole.values().forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            RadioButton(
                                selected = role == option,
                                onClick = { role = option }
                            )
                            Text(option.displayName())
                        }
                    }
                }
                OutlinedTextField(
                    value = position,
                    onValueChange = { position = it },
                    label = { Text("Должность") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = {
                        temperatureText = it
                        it.toDoubleOrNull()?.let { value ->
                            val sanitized = value.coerceIn(0.0, 2.0)
                            temperature = kotlin.math.round(sanitized * 10) / 10.0
                        }
                    },
                    label = { Text("Температура (0.0–2.0)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Slider(
                    value = temperature.toFloat(),
                    onValueChange = { value ->
                        val snapped = (kotlin.math.round(value * 10f) / 10f).coerceIn(0f, 2f)
                        temperature = snapped.toDouble()
                        temperatureText = String.format(Locale.US, "%.1f", temperature)
                    },
                    valueRange = 0f..2f,
                    steps = 19
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    singleLine = false
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val trimmedName = name.trim()
                if (trimmedName.isEmpty()) {
                    error = "Имя не может быть пустым"
                    return@Button
                }
                val parsedTemperature = temperatureText.toDoubleOrNull()?.coerceIn(0.0, 2.0)
                    ?: temperature.coerceIn(0.0, 2.0)
                val finalMember = if (member != null) {
                    member.copy(
                        name = trimmedName,
                        role = role,
                        position = position.trim(),
                        temperature = kotlin.math.round(parsedTemperature * 10) / 10.0,
                        prompt = prompt.trim()
                    )
                } else {
                    Member(
                        name = trimmedName,
                        role = role,
                        position = position.trim(),
                        temperature = kotlin.math.round(parsedTemperature * 10) / 10.0,
                        prompt = prompt.trim()
                    )
                }
                onSave(finalMember)
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
                if (isEditing && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    )
}

