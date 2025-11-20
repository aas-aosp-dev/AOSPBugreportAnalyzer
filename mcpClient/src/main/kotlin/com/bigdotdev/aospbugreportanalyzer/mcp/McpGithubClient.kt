package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


data class McpPullRequest(
    val number: Int,
    val title: String,
    val url: String,
    val state: String
)

interface McpGithubClient {
    suspend fun listPullRequests(
        owner: String? = null,
        repo: String? = null,
        state: String = "open"
    ): List<McpPullRequest>

    suspend fun getPrDiff(
        owner: String? = null,
        repo: String? = null,
        number: Int
    ): String
}

class McpGithubClientException(
    message: String,
    val isConnectionError: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

suspend fun <T> withMcpGithubClient(
    block: suspend (McpGithubClient) -> T
): T {
    println("[MCP-CLIENT] withMcpGithubClient: starting connection...")
    val json = defaultMcpJson()
    val connection = startMcpConnection(json, setOf("github.list_pull_requests", "github.get_pr_diff"))
    try {
        val client = McpGithubClientImpl(connection)
        val result = block(client)
        println("[MCP-CLIENT] withMcpGithubClient: completed successfully")
        return result
    } finally {
        connection.close()
    }
}

suspend fun withMcpFsClient(
    block: suspend (McpConnection) -> SaveSummaryResult
): SaveSummaryResult {
    val json = defaultMcpJson()
    val connection = startMcpConnection(json, setOf("fs.save_summary"))
    try {
        return block(connection)
    } finally {
        connection.close()
    }
}

private fun defaultMcpJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

private fun defaultMcpConfig(): McpServerConfig = McpServerConfig(
    command = listOf(
        "npx",
        "tsx",
        "/Users/artem/work/projects/kmp/AOSPBugreportAnalyzerMCPServer/src/server.ts"
    )
)

private suspend fun startMcpConnection(
    json: Json,
    requiredTools: Set<String>
): McpConnection {
    val config = defaultMcpConfig()
    val connection = try {
        McpConnection.start(config, json)
    } catch (t: Throwable) {
        println("[MCP-CLIENT] Failed to start MCP connection: ${t.message}")
        throw McpGithubClientException(
            message = t.message ?: "Failed to start MCP server",
            isConnectionError = true,
            cause = t
        )
    }

    try {
        val initializeResponse = connection.initialize()
        println("[MCP-CLIENT] initialize() response: $initializeResponse")
        initializeResponse.error?.let { error ->
            throw McpGithubClientException(
                message = "MCP initialize failed: ${error.message}",
                isConnectionError = true
            )
        }

        val toolsListResponse = connection.sendRequest(
            method = "tools/list",
            params = buildJsonObject { }
        )
        println("[MCP-CLIENT] tools/list response: $toolsListResponse")

        toolsListResponse.error?.let { error ->
            throw McpGithubClientException(
                message = "MCP tools/list failed: ${error.message}",
                isConnectionError = true
            )
        }

        val availableTools = toolsListResponse.result
            ?.jsonObject
            ?.get("tools")
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            ?.toSet()
            ?: emptySet()

        val missingTools = requiredTools - availableTools
        if (missingTools.isNotEmpty()) {
            throw McpGithubClientException(
                message = "Missing required MCP tools: ${missingTools.joinToString()}",
                isConnectionError = true
            )
        }

        println("[MCP-CLIENT] startMcpConnection: all required tools available: ${availableTools.joinToString()}")

        return connection
    } catch (t: Throwable) {
        println("[MCP-CLIENT] startMcpConnection failed: ${t.message}")
        connection.close()
        throw t
    }
}

private class McpGithubClientImpl(
    private val connection: McpConnection
) : McpGithubClient {

    override suspend fun listPullRequests(
        owner: String?,
        repo: String?,
        state: String
    ): List<McpPullRequest> {
        val arguments = buildArguments(owner, repo) {
            put("state", JsonPrimitive(state))
        }
        val response = connection.sendRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", JsonPrimitive("github.list_pull_requests"))
                put("arguments", arguments)
            }
        )
        response.error?.let { error ->
            println("[MCP-CLIENT] tools/call error for github.list_pull_requests: code=${error.code}, message=${error.message}, data=${error.data}")
            throw McpGithubClientException(
                message = "MCP error for github.list_pull_requests: ${error.message}",
                isConnectionError = false
            )
        }
        val result = response.result?.jsonObject?.unwrapStructuredContent()
            ?: throw McpGithubClientException(
                message = "Unexpected MCP result for github.list_pull_requests: null",
                isConnectionError = false
            )
        val pullRequests = result["pullRequests"]?.jsonArray
            ?: throw McpGithubClientException(
                message = "Unexpected MCP result for github.list_pull_requests: $result",
                isConnectionError = false
            )
        return pullRequests.map { prElement ->
            val prObject = prElement.jsonObject
            val number = prObject["number"]?.jsonPrimitive?.intOrNull
                ?: throw McpGithubClientException(
                    message = "Invalid pull request payload: missing number",
                    isConnectionError = false
                )
            val title = prObject["title"]?.jsonPrimitive?.content
                ?: throw McpGithubClientException(
                    message = "Invalid pull request payload: missing title",
                    isConnectionError = false
                )
            val url = prObject["url"]?.jsonPrimitive?.content
                ?: throw McpGithubClientException(
                    message = "Invalid pull request payload: missing url",
                    isConnectionError = false
                )
            val prState = prObject["state"]?.jsonPrimitive?.content
                ?: throw McpGithubClientException(
                    message = "Invalid pull request payload: missing state",
                    isConnectionError = false
                )
            McpPullRequest(number, title, url, prState)
        }
    }

    override suspend fun getPrDiff(owner: String?, repo: String?, number: Int): String {
        val arguments = buildArguments(owner, repo) {
            put("number", JsonPrimitive(number))
        }
        val response = connection.sendRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", JsonPrimitive("github.get_pr_diff"))
                put("arguments", arguments)
            }
        )
        response.error?.let { error ->
            println("[MCP-CLIENT] tools/call error for github.get_pr_diff: code=${error.code}, message=${error.message}, data=${error.data}")
            throw McpGithubClientException(
                message = "MCP error for github.get_pr_diff: ${error.message}",
                isConnectionError = false
            )
        }
        val result = response.result?.jsonObject?.unwrapStructuredContent()
            ?: throw McpGithubClientException(
                message = "Unexpected MCP result for github.get_pr_diff: null",
                isConnectionError = false
            )
        return result["diff"]?.jsonPrimitive?.content
            ?: throw McpGithubClientException(
                message = "Unexpected MCP result for github.get_pr_diff: $result",
                isConnectionError = false
            )
    }

    private fun buildArguments(
        owner: String?,
        repo: String?,
        builder: JsonObjectBuilder.() -> Unit
    ): JsonObject {
        return buildJsonObject {
            owner?.let { put("owner", JsonPrimitive(it)) }
            repo?.let { put("repo", JsonPrimitive(it)) }
            builder()
        }
    }
}

private fun JsonObject.unwrapStructuredContent(): JsonObject =
    this["structuredContent"]?.jsonObject ?: this
