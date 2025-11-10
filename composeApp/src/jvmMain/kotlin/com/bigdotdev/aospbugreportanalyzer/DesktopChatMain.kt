package com.bigdotdev.aospbugreportanalyzer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
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
import java.util.Locale

private enum class TeamDirection { DEVELOPMENT, DESIGN, ANALYTICS }

private enum class MemberRole { TEAM_LEAD, EMPLOYEE }

private enum class Screen { MAIN, SETTINGS, TEAMS }

private data class Member(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val role: MemberRole,
    val position: String,
    val temperature: Double,
    val prompt: String
)

private data class Team(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val direction: TeamDirection,
    val members: MutableList<Member> = mutableListOf()
)

private fun TeamDirection.displayName(): String = when (this) {
    TeamDirection.DEVELOPMENT -> "Разработка"
    TeamDirection.DESIGN -> "Дизайн"
    TeamDirection.ANALYTICS -> "Аналитика"
}

private fun TeamDirection.shortLabel(): String = when (this) {
    TeamDirection.DEVELOPMENT -> "DEV"
    TeamDirection.DESIGN -> "DESIGN"
    TeamDirection.ANALYTICS -> "ANALYTICS"
}

private fun defaultTeams(): List<Team> = listOf(
    Team(
        title = "Разработка",
        direction = TeamDirection.DEVELOPMENT,
        members = mutableListOf(
            Member(
                name = "Иван",
                role = MemberRole.TEAM_LEAD,
                position = "Senior Android разработчик",
                temperature = 0.0,
                prompt = "Act as Android tech lead. Be rigorous and practical."
            ),
            Member(
                name = "Максим",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик",
                temperature = 0.0,
                prompt = "Architect-first mindset. Suggest clean, maintainable designs."
            ),
            Member(
                name = "Геннадий",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик",
                temperature = 0.0,
                prompt = "Prefer simple, fast solutions. Avoid over-abstraction."
            ),
            Member(
                name = "Юрия",
                role = MemberRole.EMPLOYEE,
                position = "Senior Android разработчик",
                temperature = 0.0,
                prompt = "Security-first. Enforce safe patterns and data protection."
            )
        )
    ),
    Team(
        title = "Дизайн",
        direction = TeamDirection.DESIGN,
        members = mutableListOf(
            Member(
                name = "Александра",
                role = MemberRole.TEAM_LEAD,
                position = "Лид-дизайнер",
                temperature = 0.0,
                prompt = "Lead designer. Ensure guideline alignment and UX clarity."
            ),
            Member(
                name = "Екатерина",
                role = MemberRole.EMPLOYEE,
                position = "Senior дизайнер",
                temperature = 0.0,
                prompt = "Senior designer. Accessibility and patterns."
            ),
            Member(
                name = "Роман",
                role = MemberRole.EMPLOYEE,
                position = "Middle UI дизайнер",
                temperature = 0.0,
                prompt = "Middle UI. States and micro-interactions."
            )
        )
    ),
    Team(
        title = "Аналитика",
        direction = TeamDirection.ANALYTICS,
        members = mutableListOf(
            Member(
                name = "Марина",
                role = MemberRole.TEAM_LEAD,
                position = "Лид-аналитик",
                temperature = 0.0,
                prompt = "Lead analyst. Define KPIs and evaluate risks."
            ),
            Member(
                name = "Олег",
                role = MemberRole.EMPLOYEE,
                position = "Junior аналитик",
                temperature = 0.0,
                prompt = "Junior analyst. Ask clarifying questions."
            ),
            Member(
                name = "Инга",
                role = MemberRole.EMPLOYEE,
                position = "Middle data analyst",
                temperature = 0.0,
                prompt = "Data collection plan and dashboards."
            )
        )
    )
)

private fun Team.findLead(): Member? = members.firstOrNull { it.role == MemberRole.TEAM_LEAD }

private fun Team.employees(): List<Member> = members.filter { it.role == MemberRole.EMPLOYEE }

private fun List<Team>.sortedForDisplay(): List<Team> =
    sortedWith(compareBy<Team> { it.direction.ordinal }.thenBy { it.title.lowercase() })

private data class MemberEditorState(
    val teamId: String,
    val member: Member,
    val isNew: Boolean
)

private fun MemberRole.displayName(): String = when (this) {
    MemberRole.TEAM_LEAD -> "Тимлид"
    MemberRole.EMPLOYEE -> "Сотрудник"
}

private sealed class ChatRoomId {
    data object Main : ChatRoomId()
    data class TeamRoom(val teamId: String) : ChatRoomId()
}

private enum class MessageRole { USER, TEAM_LEAD, EMPLOYEE }

private data class ChatMessage(
    val id: String,
    val room: ChatRoomId,
    val author: String,
    val role: MessageRole,
    val text: String,
    val timestamp: Long
)

private data class ConversationState(
    val main: MutableList<ChatMessage> = mutableListOf(),
    val teamThreads: MutableMap<String, MutableList<ChatMessage>> = mutableMapOf(),
    val activeRoom: ChatRoomId = ChatRoomId.Main
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
    val temperature: Double? = null
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
        "Your name: ${member.name}. Position: ${member.position}. Role: ${member.role.name}. " +
            "Persona: ${member.prompt}. Team: ${team.title} (${team.direction.displayName()})."
    )
    val contextTail = priorTeamMessages.takeLast(3).map {
        val role = when (it.role) {
            MessageRole.USER -> "system"
            MessageRole.TEAM_LEAD, MessageRole.EMPLOYEE -> "user"
        }
        ORMessage(role, "[${it.author}] ${it.text}")
    }
    val taskMsg = ORMessage("user", "Task: $task")
    return listOf(sys, persona) + contextTail + taskMsg
}

private val httpClient: HttpClient = HttpClient.newHttpClient()

private fun callOpenRouter(
    model: String,
    messages: List<ORMessage>,
    temperature: Double,
    forceJson: Boolean,
    apiKeyOverride: String?
): String {
    val key = apiKeyOverride?.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey
    if (key.isNullOrBlank()) {
        return errorResponse(forceJson, "openrouter api key missing")
    }

    val safeTemperature = temperature.coerceIn(0.0, 2.0)
    val request = ORRequest(
        model = model,
        messages = messages,
        response_format = if (forceJson) mapOf("type" to "json_object") else null,
        temperature = safeTemperature
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
    teamThreads.forEach { (key, value) ->
        threadsCopy[key] = value.toMutableList()
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

private fun ConversationState.removeTeam(teamId: String): ConversationState {
    val copy = clone()
    copy.teamThreads.remove(teamId)
    val newActive = when (val current = copy.activeRoom) {
        is ChatRoomId.TeamRoom -> if (current.teamId == teamId) ChatRoomId.Main else current
        ChatRoomId.Main -> ChatRoomId.Main
    }
    return copy.copy(activeRoom = newActive)
}

private fun ConversationState.withActiveRoom(room: ChatRoomId): ConversationState {
    val resolved = when (room) {
        ChatRoomId.Main -> room
        is ChatRoomId.TeamRoom -> if (teamThreads.containsKey(room.teamId)) room else ChatRoomId.Main
    }
    return clone(resolved)
}

private fun ConversationState.ensureTeams(teamIds: Collection<String>): ConversationState {
    val copy = clone()
    val existingKeys = copy.teamThreads.keys.toSet()
    teamIds.forEach { id ->
        if (!existingKeys.contains(id)) {
            copy.teamThreads[id] = mutableListOf()
        }
    }
    val toRemove = existingKeys - teamIds.toSet()
    toRemove.forEach { copy.teamThreads.remove(it) }
    val active = when (val room = copy.activeRoom) {
        is ChatRoomId.TeamRoom -> if (teamIds.contains(room.teamId)) room else ChatRoomId.Main
        ChatRoomId.Main -> copy.activeRoom
    }
    return copy.copy(activeRoom = active)
}

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
    private val systemPrompt = selectSystemPrompt(forceJson)

    suspend fun runTeamRound(teamId: String, task: String) {
        val team = teams.firstOrNull { it.id == teamId } ?: return
        val lead = team.findLead() ?: return
        val order = buildList {
            add(lead)
            addAll(team.employees())
            add(lead)
        }
        if (order.isEmpty()) return

        suspend fun addFrom(member: Member) {
            val context = mutex.withLock {
                val thread = getState().teamThreads.getOrPut(teamId) { mutableListOf() }
                if (thread.size >= maxTeamMsgs) return
                thread.toList()
            }
            val msgs = buildMessagesForMember(member, team, task, context, systemPrompt)
            val content = callOpenRouter(model, msgs, member.temperature, forceJson, apiKeyProvider())
            val newState = mutex.withLock {
                val snapshot = getState()
                val thread = snapshot.teamThreads.getOrPut(teamId) { mutableListOf() }
                if (thread.size >= maxTeamMsgs) return
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.TeamRoom(teamId),
                    author = member.name,
                    role = when (member.role) {
                        MemberRole.TEAM_LEAD -> MessageRole.TEAM_LEAD
                        MemberRole.EMPLOYEE -> MessageRole.EMPLOYEE
                    },
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

        for (member in order) {
            if (reachedTeamLimit(teamId)) return
            addFrom(member)
        }
    }

    suspend fun postTeamSummariesToMain(task: String) {
        for (team in teams) {
            val lead = team.findLead() ?: continue
            val msgs = listOf(
                ORMessage("system", systemPrompt),
                ORMessage("system", "You are ${lead.name}, ${lead.position}."),
                ORMessage("user", "Summarize your team's solution for the main room in 2–3 sentences. Task: $task")
            )
            val content = callOpenRouter(model, msgs, lead.temperature, forceJson, apiKeyProvider())
            val newState = mutex.withLock {
                val snapshot = getState()
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = lead.name,
                    role = MessageRole.TEAM_LEAD,
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
        suspend fun leaderReply(team: Team, lead: Member, cue: String) {
            val context = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == MessageRole.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return
                snapshot.main.takeLast(4)
            }
            val msgs = mutableListOf(
                ORMessage("system", systemPrompt),
                ORMessage("system", "You are ${lead.name}, ${lead.position}. Debate briefly.")
            )
            msgs += context.map { ORMessage("user", "[${it.author}] ${it.text}") }
            msgs += ORMessage("user", "Task: $task. Respond: $cue")

            val content = callOpenRouter(model, msgs, lead.temperature, forceJson, apiKeyProvider())
            val newState = mutex.withLock {
                val snapshot = getState()
                val leadCount = snapshot.main.count { it.role == MessageRole.TEAM_LEAD }
                if (leadCount >= maxMainDebateMsgs) return
                val message = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    room = ChatRoomId.Main,
                    author = lead.name,
                    role = MessageRole.TEAM_LEAD,
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
            TeamDirection.DEVELOPMENT to "Argue for solution A briefly.",
            TeamDirection.DESIGN to "Add design constraints briefly.",
            TeamDirection.ANALYTICS to "Add analytics KPIs briefly."
        )
        cues.forEach { (direction, cue) ->
            val team = teams.firstOrNull { it.direction == direction } ?: return@forEach
            val lead = team.findLead() ?: return@forEach
            leaderReply(team, lead, cue)
        }
    }

    suspend fun sendLeadMessage(teamId: String, task: String) {
        val team = teams.firstOrNull { it.id == teamId } ?: return
        val lead = team.findLead() ?: return
        val context = mutex.withLock { getState().teamThreads.getOrPut(teamId) { mutableListOf() }.toList() }
        val msgs = buildMessagesForMember(lead, team, task, context, systemPrompt)
        val content = callOpenRouter(model, msgs, lead.temperature, forceJson, apiKeyProvider())
        val newState = mutex.withLock {
            val snapshot = getState()
            val thread = snapshot.teamThreads.getOrPut(teamId) { mutableListOf() }
            if (thread.size >= maxTeamMsgs) return
            val message = ChatMessage(
                id = UUID.randomUUID().toString(),
                room = ChatRoomId.TeamRoom(teamId),
                author = lead.name,
                role = MessageRole.TEAM_LEAD,
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
    var teams by remember { mutableStateOf(defaultTeams().sortedForDisplay()) }
    var selectedTeamId by remember { mutableStateOf(teams.firstOrNull()?.id) }
    var state by remember { mutableStateOf(ConversationState()) }
    var settings by remember { mutableStateOf(AppSettings()) }
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var taskInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var lastTask by remember { mutableStateOf("") }
    var copyFeedback by remember { mutableStateOf<String?>(null) }
    var memberEditorState by remember { mutableStateOf<MemberEditorState?>(null) }
    var teamToDelete by remember { mutableStateOf<Team?>(null) }
    var teamsMessage by remember { mutableStateOf<String?>(null) }

    val apiKeyState by rememberUpdatedState(settings.openRouterApiKey)

    LaunchedEffect(teams) {
        state = state.ensureTeams(teams.map { it.id })
        if (selectedTeamId != null && teams.none { it.id == selectedTeamId }) {
            selectedTeamId = teams.firstOrNull()?.id
        }
    }

    LaunchedEffect(copyFeedback) {
        if (copyFeedback != null) {
            delay(1500)
            if (copyFeedback != null) {
                copyFeedback = null
            }
        }
    }

    val orchestrator = remember(teams, settings.openRouterModel, settings.strictJsonEnabled) {
        TeamLLMOrchestrator(
            model = settings.openRouterModel,
            forceJson = settings.strictJsonEnabled,
            teams = teams,
            getState = { state },
            setState = { newState -> state = newState },
            apiKeyProvider = { apiKeyState.takeIf { it.isNotBlank() } ?: OpenRouterConfig.apiKey }
        )
    }

    fun showTeamsMessage(text: String) {
        teamsMessage = text
        scope.launch {
            delay(2000)
            if (teamsMessage == text) {
                teamsMessage = null
            }
        }
    }

    fun updateTeams(transform: (List<Team>) -> List<Team>) {
        teams = transform(teams).map { team ->
            team.copy(members = team.members.toMutableList())
        }.sortedForDisplay()
    }

    fun upsertMember(teamId: String, member: Member) {
        updateTeams { current ->
            current.map { team ->
                if (team.id != teamId) {
                    team
                } else {
                    val members = team.members.toMutableList()
                    if (member.role == MemberRole.TEAM_LEAD) {
                        for (index in members.indices) {
                            val existing = members[index]
                            if (existing.role == MemberRole.TEAM_LEAD && existing.id != member.id) {
                                members[index] = existing.copy(role = MemberRole.EMPLOYEE)
                            }
                        }
                    }
                    val idx = members.indexOfFirst { it.id == member.id }
                    if (idx >= 0) {
                        members[idx] = member
                    } else {
                        members.add(member)
                    }
                    team.copy(members = members)
                }
            }
        }
    }

    fun removeMember(teamId: String, memberId: String) {
        val targetTeam = teams.firstOrNull { it.id == teamId } ?: return
        val targetMember = targetTeam.members.firstOrNull { it.id == memberId } ?: return
        if (targetMember.role == MemberRole.TEAM_LEAD && targetTeam.members.count { it.role == MemberRole.TEAM_LEAD } <= 1) {
            showTeamsMessage("Нельзя удалить единственного тимлида")
            return
        }
        updateTeams { current ->
            current.map { team ->
                if (team.id != teamId) team else team.copy(members = team.members.filterNot { it.id == memberId }.toMutableList())
            }
        }
    }

    fun updateTeamMeta(teamId: String, title: String, direction: TeamDirection) {
        updateTeams { current ->
            current.map { team ->
                if (team.id != teamId) team else team.copy(title = title, direction = direction, members = team.members.toMutableList())
            }
        }
    }

    fun createTeam() {
        val newTeam = Team(title = "Новая команда", direction = TeamDirection.DEVELOPMENT)
        updateTeams { it + newTeam }
        selectedTeamId = newTeam.id
    }

    fun deleteTeam(team: Team) {
        updateTeams { current -> current.filterNot { it.id == team.id } }
        state = state.removeTeam(team.id)
        if (selectedTeamId == team.id) {
            selectedTeamId = teams.firstOrNull()?.id
        }
    }

    fun appendUserMessage(text: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            room = ChatRoomId.Main,
            author = "USER",
            role = MessageRole.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        state = state.addMainMessage(message).withActiveRoom(ChatRoomId.Main)
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
        val currentTeams = teams
        val jobs = currentTeams.map { team ->
            scope.launch(Dispatchers.IO) {
                orchestrator.runTeamRound(team.id, task)
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
                            .fillMaxWidth(),
                    ) {
                        MessageList(messages) {
                            copyFeedback = "Скопировано"
                        }
                        if (copyFeedback != null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                    Text(
                                        text = copyFeedback!!,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        if (isProcessing) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
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
                            if (team == null) {
                                Text("Команда не найдена")
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Внутренний чат команды "${team.title}"")
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
                                    if (team.members.count { it.role == MemberRole.TEAM_LEAD } != 1) {
                                        Text(
                                            text = "Требуется ровно один тимлид в команде",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    if (team.members.isEmpty()) {
                                        Text("Нет сотрудников")
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
                onSelectTeam = { selectedTeamId = it },
                onBack = { screen = Screen.MAIN },
                onCreateTeam = { createTeam() },
                onRequestDeleteTeam = { team -> teamToDelete = team },
                onUpdateTeam = { teamId, title, direction -> updateTeamMeta(teamId, title, direction) },
                onAddMember = { teamId ->
                    memberEditorState = MemberEditorState(
                        teamId = teamId,
                        member = Member(name = "", role = MemberRole.EMPLOYEE, position = "", temperature = 0.0, prompt = ""),
                        isNew = true
                    )
                },
                onEditMember = { teamId, member ->
                    memberEditorState = MemberEditorState(teamId = teamId, member = member, isNew = false)
                },
                onRemoveMember = { teamId, member -> removeMember(teamId, member.id) },
                teamsMessage = teamsMessage
            )
        }
    }

    memberEditorState?.let { editorState ->
        MemberEditorDialog(
            state = editorState,
            onDismiss = { memberEditorState = null },
            onSave = { updated ->
                upsertMember(editorState.teamId, updated)
                memberEditorState = null
            },
            onDelete = if (!editorState.isNew) {
                {
                    removeMember(editorState.teamId, editorState.member.id)
                    memberEditorState = null
                }
            } else null
        )
    }

    teamToDelete?.let { team ->
        ConfirmDeleteTeamDialog(
            team = team,
            onConfirm = {
                deleteTeam(team)
                teamToDelete = null
            },
            onDismiss = { teamToDelete = null }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamsScreen(
    teams: List<Team>,
    selectedTeamId: String?,
    onSelectTeam: (String?) -> Unit,
    onBack: () -> Unit,
    onCreateTeam: () -> Unit,
    onRequestDeleteTeam: (Team) -> Unit,
    onUpdateTeam: (String, String, TeamDirection) -> Unit,
    onAddMember: (String) -> Unit,
    onEditMember: (String, Member) -> Unit,
    onRemoveMember: (String, Member) -> Unit,
    teamsMessage: String?
) {
    val selectedTeam = teams.firstOrNull { it.id == selectedTeamId } ?: teams.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Управление командами") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Назад") }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Команды", style = MaterialTheme.typography.titleMedium)
                if (teams.isEmpty()) {
                    Box(modifier = Modifier.weight(1f, fill = true), contentAlignment = Alignment.Center) {
                        Text("Команды отсутствуют")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(teams, key = { it.id }) { team ->
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectTeam(team.id) },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (team.id == selectedTeam?.id) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(team.title, style = MaterialTheme.typography.titleMedium)
                                    Text(team.direction.displayName(), style = MaterialTheme.typography.bodySmall)
                                    Text("Участников: ${team.members.size}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCreateTeam,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Создать команду")
                    }
                    val teamForDelete = selectedTeam
                    TextButton(
                        onClick = { teamForDelete?.let(onRequestDeleteTeam) },
                        enabled = teamForDelete != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Удалить команду")
                    }
                }
                teamsMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(2f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (selectedTeam == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Выберите команду слева")
                    }
                } else {
                    var title by remember(selectedTeam.id) { mutableStateOf(selectedTeam.title) }
                    var direction by remember(selectedTeam.id) { mutableStateOf(selectedTeam.direction) }

                    LaunchedEffect(selectedTeam.title) {
                        title = selectedTeam.title
                    }
                    LaunchedEffect(selectedTeam.direction) {
                        direction = selectedTeam.direction
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                onUpdateTeam(selectedTeam.id, it, direction)
                            },
                            label = { Text("Название команды") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        var expanded by remember(selectedTeam.id) { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it }
                        ) {
                            OutlinedTextField(
                                value = direction.displayName(),
                                onValueChange = {},
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                label = { Text("Направление") },
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                TeamDirection.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.displayName()) },
                                        onClick = {
                                            expanded = false
                                            direction = option
                                            onUpdateTeam(selectedTeam.id, title, option)
                                        }
                                    )
                                }
                            }
                        }

                        val leadCount = selectedTeam.members.count { it.role == MemberRole.TEAM_LEAD }
                        if (leadCount != 1) {
                            Text(
                                text = "В команде должен быть ровно один тимлид (сейчас $leadCount)",
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Button(onClick = { onAddMember(selectedTeam.id) }) {
                            Text("Добавить сотрудника")
                        }

                        if (selectedTeam.members.isEmpty()) {
                            Text("Сотрудников нет")
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(selectedTeam.members, key = { it.id }) { member ->
                                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = member.name,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text("Роль: ${member.role.displayName()}")
                                            if (member.position.isNotBlank()) {
                                                Text("Должность: ${member.position}")
                                            }
                                            Text("T = ${String.format(Locale.US, "%.1f", member.temperature)}")
                                            Text(
                                                text = "Prompt: ${member.prompt}",
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 3
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TextButton(onClick = { onEditMember(selectedTeam.id, member) }) {
                                                    Text("Редактировать")
                                                }
                                                TextButton(onClick = { onRemoveMember(selectedTeam.id, member) }) {
                                                    Text("Удалить")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberEditorDialog(
    state: MemberEditorState,
    onDismiss: () -> Unit,
    onSave: (Member) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember(state) { mutableStateOf(state.member.name) }
    var role by remember(state) { mutableStateOf(state.member.role) }
    var position by remember(state) { mutableStateOf(state.member.position) }
    var temperature by remember(state) { mutableStateOf(state.member.temperature.coerceIn(0.0, 2.0)) }
    var temperatureText by remember(state) { mutableStateOf(String.format(Locale.US, "%.1f", temperature)) }
    var prompt by remember(state) { mutableStateOf(state.member.prompt) }
    var showError by remember { mutableStateOf(false) }

    fun submit() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            showError = true
            return
        }
        val sanitizedTemp = temperature.coerceIn(0.0, 2.0)
        onSave(
            state.member.copy(
                name = trimmedName,
                role = role,
                position = position.trim(),
                temperature = sanitizedTemp,
                prompt = prompt
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isNew) "Новый сотрудник" else "Редактирование сотрудника") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (showError) showError = false
                    },
                    label = { Text("Имя") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = showError && name.trim().isEmpty()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Роль")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RadioButton(selected = role == MemberRole.TEAM_LEAD, onClick = { role = MemberRole.TEAM_LEAD })
                        Text("Тимлид")
                        RadioButton(selected = role == MemberRole.EMPLOYEE, onClick = { role = MemberRole.EMPLOYEE })
                        Text("Сотрудник")
                    }
                }

                OutlinedTextField(
                    value = position,
                    onValueChange = { position = it },
                    label = { Text("Должность") },
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Температура")
                    Slider(
                        value = temperature.toFloat(),
                        onValueChange = {
                            temperature = it.toDouble().coerceIn(0.0, 2.0)
                            temperatureText = String.format(Locale.US, "%.1f", temperature)
                        },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                    OutlinedTextField(
                        value = temperatureText,
                        onValueChange = {
                            temperatureText = it
                            it.replace(',', '.').toDoubleOrNull()?.let { parsed ->
                                temperature = parsed.coerceIn(0.0, 2.0)
                            }
                        },
                        label = { Text("Температура (0.0 – 2.0)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(onClick = { submit() }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("Удалить")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
            }
        }
    )
}

@Composable
private fun ConfirmDeleteTeamDialog(
    team: Team,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить команду?") },
        text = { Text("Команда "${team.title}" и её переписка будут удалены.") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun MessageList(messages: List<ChatMessage>, onCopy: () -> Unit) {
    val clipboard = LocalClipboardManager.current
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
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatTimestamp(message.timestamp)} · ${message.author} (${message.role.name})",
                        style = MaterialTheme.typography.labelSmall
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        onCopy()
                    }) {
                        Text("Копировать")
                    }
                }
                Text(message.text)
            }
        }
    }
}
