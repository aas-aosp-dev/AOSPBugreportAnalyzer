package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.api.chatRoutes
import com.bigdotdev.aospbugreportanalyzer.app.BuildPrompt
import com.bigdotdev.aospbugreportanalyzer.app.EnsureJson
import com.bigdotdev.aospbugreportanalyzer.app.SendChat
import com.bigdotdev.aospbugreportanalyzer.app.StreamChat
import com.bigdotdev.aospbugreportanalyzer.domain.AgentRole
import com.bigdotdev.aospbugreportanalyzer.domain.ProviderType
import com.bigdotdev.aospbugreportanalyzer.domain.StaticAgent
import com.bigdotdev.aospbugreportanalyzer.infra.InMemoryConversationStore
import com.bigdotdev.aospbugreportanalyzer.infra.OpenAIClient
import com.bigdotdev.aospbugreportanalyzer.infra.OpenRouterClient
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderConfiguration
import com.bigdotdev.aospbugreportanalyzer.infra.ProviderRouter
import com.bigdotdev.aospbugreportanalyzer.infra.GroqClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.net.http.HttpClient

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
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

    val configuration = ProviderConfiguration.fromEnvironment()
    val httpClient = HttpClient.newBuilder().build()

    val openRouterClient = OpenRouterClient(httpClient, configuration)
    val openAiClient = OpenAIClient(configuration)
    val groqClient = GroqClient(configuration)

    val providerRouter = ProviderRouter(
        clients = mapOf(
            ProviderType.OPENROUTER to openRouterClient,
            ProviderType.OPENAI to openAiClient,
            ProviderType.GROQ to groqClient
        ),
        agents = mapOf(
            "default-generalist" to StaticAgent(
                id = "default-generalist",
                displayName = "Generalist JSON Agent",
                role = AgentRole.GENERALIST,
                provider = ProviderType.OPENROUTER,
                defaultModel = System.getenv("OPENROUTER_MODEL") ?: "gpt-4o-mini"
            )
        )
    )

    val conversationStore = InMemoryConversationStore()
    val sendChat = SendChat(providerRouter, BuildPrompt(), EnsureJson(), conversationStore)
    val streamChat = StreamChat()

    routing {
        get("/") {
            call.respondText("AOSP Bugreport Analyzer BFF is running")
        }
        chatRoutes(sendChat, streamChat)
    }
}
