package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString

fun main() = runBlocking {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    val config = McpServerConfig(
        command = listOf(
            "npx", "tsx",
            "/Users/artem/work/projects/kmp/AOSPBugreportAnalyzerMCPServer/src/server.ts"
        )
    )

    val connection = McpConnection.start(config, json)
    try {
        println("📡 [MCP-CLIENT] Sending initialize...")
        val initializeResponse = connection.initialize()
        if (initializeResponse.error != null) {
            System.err.println("❌ [MCP-CLIENT] initialize failed: ${formatError(initializeResponse.error)}")
            return@runBlocking
        }
        val initResult = json.encodeToString(initializeResponse.result ?: JsonNull)
        println("✅ [MCP-CLIENT] Initialize completed: $initResult")

        println("📡 [MCP-CLIENT] Requesting tools.list...")
        val toolsResponse = try {
            connection.sendRequest(
                method = "tools/list",
                params = buildJsonObject { }
            )
        } catch (e: Exception) {
            System.err.println("❌ [MCP-CLIENT] tools.list failed: ${e.message}")
            return@runBlocking
        }

        if (toolsResponse.error != null) {
            System.err.println("❌ [MCP-CLIENT] tools.list failed: ${formatError(toolsResponse.error)}")
            return@runBlocking
        }

        val toolsArray = extractToolsArray(toolsResponse.result)
        println("✅ [MCP-CLIENT] tools.list response:")
        toolsArray.forEach { tool ->
            val obj = tool.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "<no-name>"
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            if (desc.isBlank()) {
                println("   • $name")
            } else {
                println("   • $name — $desc")
            }
        }

        val hasGetPrDiff = toolsArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content == "github.get_pr_diff"
        }

        if (!hasGetPrDiff) {
            println("Tool github.get_pr_diff not found, skipping diff call.")
            return@runBlocking
        }

        println("📡 [MCP-CLIENT] Requesting github.get_pr_diff for PR #40...")
        val prParams = buildJsonObject {
            put("owner", JsonPrimitive("aas-aosp-dev"))
            put("repo", JsonPrimitive("AOSPBugreportAnalyzer"))
            put("number", JsonPrimitive(40))
        }

        val diffResponse = try {
            connection.sendRequest(
                method = "tools/call",
                params = buildJsonObject {
                    put("name", JsonPrimitive("github.get_pr_diff"))
                    put("arguments", prParams)
                }
            )
        } catch (e: Exception) {
            System.err.println("❌ [MCP-CLIENT] github.get_pr_diff failed: ${e.message}")
            return@runBlocking
        }

        if (diffResponse.error != null) {
            System.err.println("❌ [MCP-CLIENT] github.get_pr_diff failed: ${formatError(diffResponse.error)}")
            return@runBlocking
        }

        println("✅ [MCP-CLIENT] github.get_pr_diff response:")
        println(json.encodeToString(diffResponse.result ?: JsonNull))
    } finally {
        println("🏁 [MCP-CLIENT] Done. MCP server will be terminated.")
        connection.close()
    }
}

private fun extractToolsArray(result: JsonElement?): JsonArray {
    val obj = result?.jsonObject
        ?: return JsonArray(emptyList())
    val tools = obj["tools"]?.jsonArray
    return tools ?: JsonArray(emptyList())
}

private fun formatError(error: JsonRpcError): String {
    val data = error.data?.toString() ?: "<no-data>"
    return "code=${error.code}, message=${error.message}, data=$data"
}
