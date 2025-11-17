package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Simple configuration for launching the external MCP server process.
 */
data class McpServerConfig(
    val command: List<String>,
)

class McpConnection private constructor(
    private val process: Process,
    private val input: BufferedReader,
    private val output: BufferedWriter,
    private val json: Json,
) {

    private val idMutex = Mutex()
    private var lastId = 0

    companion object {
        fun start(config: McpServerConfig, json: Json): McpConnection {
            require(config.command.isNotEmpty()) { "MCP server command must not be empty" }
            println("Starting MCP server process: ${config.command.joinToString(" ")}")
            val process = ProcessBuilder(config.command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            val input = process.inputStream.bufferedReader()
            val output = process.outputStream.bufferedWriter()

            return McpConnection(process, input, output, json)
        }
    }

    suspend fun initialize() {
        val params = buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            putJsonObject("clientInfo") {
                put("name", JsonPrimitive("AOSPBugreportAnalyzer-mcpClient"))
                put("version", JsonPrimitive("0.1.0"))
            }
        }

        println("Sending MCP initialize...")
        val response = sendRequest("initialize", params)
        if (response.error != null) {
            throw IllegalStateException("MCP initialize failed: ${response.error}")
        }
        println("MCP initialize OK")
    }

    suspend fun sendRequest(
        method: String,
        params: JsonObject = buildJsonObject { }
    ): JsonRpcResponse {
        val nextId = idMutex.withLock {
            ++lastId
        }

        val payload = json.encodeToString(
            JsonRpcRequest(id = nextId, method = method, params = params)
        )

        println(">>> JSON-RPC request: $payload")

        output.write(payload)
        output.newLine()
        output.flush()

        val line = input.readLine()
            ?: throw IllegalStateException("MCP server process terminated unexpectedly")

        println("<<< JSON-RPC response: $line")

        return json.decodeFromString(line)
    }

    fun close() {
        try {
            output.close()
        } catch (_: Exception) {
        }
        try {
            input.close()
        } catch (_: Exception) {
        }
        process.destroy()
    }
}

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject = buildJsonObject { }
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)
