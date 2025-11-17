package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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

fun main() = runBlocking {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val config = McpServerConfig(
        command = listOf(
            // TODO: Replace with the actual command that launches your MCP GitHub server.
            "node",
            "/ABSOLUTE/PATH/TO/github-mcp-server.js"
        )
    )

    val connection = McpConnection.start(config, json)
    try {
        println("Requesting tools.list via MCP...")
        val toolsResponse = connection.sendRequest(
            method = "tools/list",
            params = buildJsonObject { }
        )

        handlePotentialError("tools/list", toolsResponse)

        val toolsArray = extractToolsArray(toolsResponse.result)
        println("Available MCP tools:")
        toolsArray.forEach { tool ->
            val obj = tool.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "<no-name>"
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            if (desc.isBlank()) {
                println("- $name")
            } else {
                println("- $name — $desc")
            }
        }

        val hasGetPrDiff = toolsArray.any {
            it.jsonObject["name"]?.jsonPrimitive?.content == "github.get_pr_diff"
        }

        if (!hasGetPrDiff) {
            println("Tool github.get_pr_diff not found, skipping diff call.")
            return@runBlocking
        }

        println("Requesting github.get_pr_diff for PR #40...")
        val prParams = buildJsonObject {
            put("owner", JsonPrimitive("aas-aosp-dev"))
            put("repo", JsonPrimitive("AOSPBugreportAnalyzer"))
            put("number", JsonPrimitive(40))
        }

        val diffResponse = connection.sendRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", JsonPrimitive("github.get_pr_diff"))
                put("arguments", prParams)
            }
        )

        handlePotentialError("github.get_pr_diff", diffResponse)

        println("Diff result:")
        println(json.encodeToString(diffResponse.result ?: JsonNull))
    } finally {
        connection.destroy()
    }
}

private fun handlePotentialError(operation: String, response: JsonRpcResponse) {
    response.error?.let { error ->
        error(
            "$operation error: code=${error.code}, message=${error.message}, data=${error.data}"
        )
    }
}

private fun extractToolsArray(result: JsonElement?): JsonArray {
    val obj = result?.jsonObject
        ?: return JsonArray(emptyList())
    val tools = obj["tools"]?.jsonArray
    return tools ?: JsonArray(emptyList())
}
