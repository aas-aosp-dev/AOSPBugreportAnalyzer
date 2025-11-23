package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GithubPullRequest(
    val number: Int,
    val title: String,
    val htmlUrl: String,
    val state: String,
    val labels: List<String>,
    val updatedAt: String?,
    val bodyPreview: String?
)

suspend fun listOpenPullRequestsViaMcpGithub(
    limit: Int = 10
): List<GithubPullRequest> {
    return withMcpGithubClient { client ->
        val response = client.sendRequest(
            method = "tools/call",
            params = buildJsonObject {
                put("name", JsonPrimitive("github.list_open_pull_requests"))
                put(
                    "arguments",
                    buildJsonObject {
                        put("limit", JsonPrimitive(limit))
                    }
                )
            }
        )

        response.error?.let { error ->
            throw McpGithubClientException(
                message = "MCP error for github.list_open_pull_requests: ${error.message}",
                isConnectionError = false
            )
        }

        val result: JsonObject = response.result?.jsonObject?.unwrapStructuredContent()
            ?: throw McpGithubClientException(
                message = "Unexpected MCP result for github.list_open_pull_requests: null",
                isConnectionError = false
            )

        val pullRequestsJson = result["pullRequests"]?.jsonArray
            ?: throw McpGithubClientException(
                message = "Unexpected MCP result for github.list_open_pull_requests: $result",
                isConnectionError = false
            )

        pullRequestsJson.mapNotNull { prElement ->
            val obj = prElement.jsonObject
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val htmlUrl = obj["htmlUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val labels = obj["labels"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            val updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull
            val bodyPreview = obj["bodyPreview"]?.jsonPrimitive?.contentOrNull?.take(200)

            GithubPullRequest(
                number = number,
                title = title,
                htmlUrl = htmlUrl,
                state = state,
                labels = labels,
                updatedAt = updatedAt,
                bodyPreview = bodyPreview
            )
        }
    }
}
