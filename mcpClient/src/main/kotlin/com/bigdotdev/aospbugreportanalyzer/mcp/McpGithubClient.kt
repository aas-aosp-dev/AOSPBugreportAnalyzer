package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

data class SaveSummaryResult(
    val filePath: String
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

    suspend fun saveSummaryToFile(
        fileName: String,
        content: String
    ): SaveSummaryResult
}

class McpGithubClientException(
    message: String,
    val isConnectionError: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

suspend fun <T> withMcpGithubClient(
    block: suspend (McpGithubClient) -> T
): T {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val command = resolveCommand(
        envVarName = "MCP_GITHUB_SERVER_COMMAND",
        defaultCommand = defaultGithubServerCommand(),
        logTag = "MCP-GITHUB-CLIENT"
    )
    val config = McpServerConfig(command)

    val connection = try {
        McpConnection.start(config, json, logTag = "MCP-GITHUB-CLIENT")
    } catch (t: Throwable) {
        throw McpGithubClientException(
            message = t.message ?: "Failed to start MCP server",
            isConnectionError = true,
            cause = t
        )
    }

    try {
        val initializeResponse = connection.initialize()
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

        val requiredTools = setOf(
            "github.list_pull_requests",
            "github.get_pr_diff",
            "fs.save_summary"
        )
        val missingTools = requiredTools - availableTools
        if (missingTools.isNotEmpty()) {
            throw McpGithubClientException(
                message = "Missing required MCP tools: ${missingTools.joinToString()}",
                isConnectionError = true
            )
        }

        val client = McpGithubClientImpl(connection)
        return block(client)
    } finally {
        connection.close()
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

    override suspend fun saveSummaryToFile(
        fileName: String,
        content: String
    ): SaveSummaryResult {
        println("[MCP-CLIENT] Calling fs.save_summary with fileName=$fileName")
        val response = connection.sendRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", JsonPrimitive("fs.save_summary"))
                put(
                    "arguments",
                    buildJsonObject {
                        put("fileName", JsonPrimitive(fileName))
                        put("content", JsonPrimitive(content))
                    }
                )
            }
        )
        response.error?.let { error ->
            throw McpGithubClientException(
                message = "MCP error for fs.save_summary: ${error.message}",
                isConnectionError = false
            )
        }
        val result = response.result?.jsonObject
        val isError = result?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            val contentText = result["content"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
            throw McpGithubClientException(
                message = "MCP tool fs.save_summary returned error: ${contentText ?: "unknown error"}",
                isConnectionError = false
            )
        }
        val structured = result?.unwrapStructuredContent()
        val filePath = structured?.get("filePath")?.jsonPrimitive?.contentOrNull
            ?: throw McpGithubClientException(
                "Unexpected MCP result for fs.save_summary: structuredContent.filePath is missing"
            )
        if (filePath.isBlank()) {
            throw McpGithubClientException(
                "Unexpected MCP result for fs.save_summary: structuredContent.filePath is missing"
            )
        }
        println("[MCP-CLIENT] fs.save_summary -> filePath=$filePath")
        return SaveSummaryResult(filePath)
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
