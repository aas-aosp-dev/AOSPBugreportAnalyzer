package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.api.chatRoutes
import com.bigdotdev.aospbugreportanalyzer.app.BuildPrompt
import com.bigdotdev.aospbugreportanalyzer.app.EnsureJson
import com.bigdotdev.aospbugreportanalyzer.app.SendChat
import com.bigdotdev.aospbugreportanalyzer.app.StreamChat
import com.bigdotdev.aospbugreportanalyzer.domain.Agent
import com.bigdotdev.aospbugreportanalyzer.domain.AgentRole
import com.bigdotdev.aospbugreportanalyzer.domain.ProviderType
import com.bigdotdev.aospbugreportanalyzer.infra.ConversationStore
import com.bigdotdev.aospbugreportanalyzer.infra.GroqClient
import com.bigdotdev.aospbugreportanalyzer.infra.OpenAIClient
import com.bigdotdev.aospbugreportanalyzer.infra.OpenRouterClient
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderConfiguration
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderRouter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.net.http.HttpClient

private const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai"
private const val DEFAULT_OPENROUTER_REFERER = "https://github.com/aas-aosp-dev/AOSPBugreportAnalyzer"
private const val DEFAULT_OPENROUTER_TITLE = "AOSP Bugreport Analyzer"

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(CallLogging)
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = false })
    }

    val httpClient = HttpClient.newBuilder().build()

    val openRouterClient = OpenRouterClient(
        httpClient = httpClient,
        configuration = ProviderConfiguration(
            baseUrl = System.getenv("OPENROUTER_BASE_URL") ?: DEFAULT_OPENROUTER_BASE_URL,
            apiKey = System.getenv("OPENROUTER_API_KEY"),
            referer = System.getenv("OPENROUTER_REFERER") ?: DEFAULT_OPENROUTER_REFERER,
            title = System.getenv("OPENROUTER_TITLE") ?: DEFAULT_OPENROUTER_TITLE
        )
    )

    val providerRouter = ProviderRouter(
        clients = mapOf(
            ProviderType.OPENROUTER to openRouterClient,
            ProviderType.OPENAI to OpenAIClient(),
            ProviderType.GROQ to GroqClient()
        ),
        agents = mapOf(
            "default-generalist" to Agent(
                id = "default-generalist",
                displayName = "Generalist JSON Agent",
                role = AgentRole.GENERALIST,
                provider = ProviderType.OPENROUTER,
                defaultModel = System.getenv("OPENROUTER_MODEL") ?: "gpt-4o-mini"
            )
        )
    )

    val conversationStore = ConversationStore()
    val sendChat = SendChat(providerRouter, BuildPrompt(), EnsureJson(), conversationStore)
    val streamChat = StreamChat()

    routing {
        get("/") {
            call.respondText("AOSP Bugreport Analyzer BFF is running")
        }
        chatRoutes(sendChat, streamChat)
    }
}
