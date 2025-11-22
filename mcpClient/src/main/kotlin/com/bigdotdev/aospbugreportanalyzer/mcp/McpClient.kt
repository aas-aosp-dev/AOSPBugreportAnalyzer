package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
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
        fun start(
            config: McpServerConfig,
            json: Json,
            logTag: String = "MCP-CLIENT"
        ): McpConnection {
            require(config.command.isNotEmpty()) { "MCP server command must not be empty" }
            val command = config.command.first()
            val args = config.command.drop(1)
            val argsSuffix = if (args.isEmpty()) "" else " ${args.joinToString(" ")}" 
            println("🔧 [$logTag] Starting MCP server process: $command$argsSuffix")
            val process = ProcessBuilder(config.command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            println("🔧 [$logTag] MCP server PID: ${process.pid()}")

            val input = process.inputStream.bufferedReader()
            val output = process.outputStream.bufferedWriter()

            return McpConnection(process, input, output, json)
        }
    }

    suspend fun initialize(): JsonRpcResponse {
        val params = buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            putJsonObject("clientInfo") {
                put("name", JsonPrimitive("AOSPBugreportAnalyzer-mcpClient"))
                put("version", JsonPrimitive("0.1.0"))
            }
            putJsonObject("capabilities") {
                // минимальный набор, которого достаточно большинству MCP-серверов
                putJsonObject("tools") { }
                putJsonObject("resources") { }
                putJsonObject("prompts") { }
                putJsonObject("logging") { }
            }

        }

        return sendRequest("initialize", params)
    }

    suspend fun sendRequest(
        method: String,
        params: JsonObject = buildJsonObject { }
    ): JsonRpcResponse {
        val nextId = idMutex.withLock {
            ++lastId
        }

        val request = JsonRpcRequest(id = nextId, method = method, params = params)
        val payload = json.encodeToString(request)

        println(">>> [MCP-CLIENT] JSON-RPC request: $payload")

        output.write(payload)
        output.newLine()
        output.flush()

        val line = input.readLine()
        if (line == null) {
            System.err.println("❌ [MCP-CLIENT] No response from MCP server (EOF reached)")
            if (!process.isAlive) {
                System.err.println("❌ [MCP-CLIENT] MCP server process terminated with exitCode=${process.exitValue()}")
            }
            throw IllegalStateException("MCP server process terminated unexpectedly")
        }

        println("<<< [MCP-CLIENT] Raw JSON-RPC response: $line")

        return try {
            val response = json.decodeFromString<JsonRpcResponse>(line)
            println("✅ [MCP-CLIENT] Decoded JSON-RPC response for id=$nextId: $response")
            response
        } catch (e: SerializationException) {
            System.err.println("❌ [MCP-CLIENT] Failed to decode response: ${e.message}")
            throw e
        }
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
