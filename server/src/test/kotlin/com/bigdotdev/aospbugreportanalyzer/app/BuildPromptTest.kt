package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatMessage
import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import com.bigdotdev.aospbugreportanalyzer.domain.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildPromptTest {
    private val buildPrompt = BuildPrompt()

    @Test
    fun `adds system prompt when strict json is requested`() {
        val request = ChatRequest(
            provider = ProviderType.OPENROUTER,
            model = "gpt-test",
            history = emptyList(),
            userInput = "Hello",
            strictJson = true,
            systemPrompt = null,
            responseFormat = "json"
        )

        val messages = buildPrompt(request)

        assertTrue(messages.first().role == "system")
    }

    @Test
    fun `injects greeting instruction when user input is greeting`() {
        val request = ChatRequest(
            provider = ProviderType.OPENROUTER,
            model = "gpt-test",
            history = listOf(ChatMessage("assistant", "Hi!")),
            userInput = "Привет",
            strictJson = true,
            systemPrompt = null,
            responseFormat = "json"
        )

        val messages = buildPrompt(request)

        val lastMessage = messages.last()
        assertEquals("user", lastMessage.role)
        assertTrue(lastMessage.content.contains("greeting", ignoreCase = true))
    }
}
