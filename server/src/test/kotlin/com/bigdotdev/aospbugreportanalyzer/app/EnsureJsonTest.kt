package com.bigdotdev.aospbugreportanalyzer.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnsureJsonTest {
    private val ensureJson = EnsureJson(Json { ignoreUnknownKeys = true })

    @Test
    fun `returns parsed json when input is valid`() = runBlocking {
        val raw = "{""version"":""1.0""}"

        val result = ensureJson.ensure(raw) { null }

        assertTrue(result is JsonObject)
        assertEquals("1.0", (result as JsonObject)["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `repairs invalid json using provider callback`() = runBlocking {
        val raw = "not json"
        val repaired = "{""version"":""1.0"",""ok"":true}"

        val result = ensureJson.ensure(raw) { repaired }

        assertTrue(result is JsonObject)
        assertEquals("1.0", (result as JsonObject)["version"]?.jsonPrimitive?.content)
    }
}
