package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String?,
    val device: String?
)

data class AdbListDevicesResult(
    val devices: List<AdbDevice>
)

data class AdbBugreportResult(
    val filePath: String
)

class McpAdbClientException(
    message: String,
    val isConnectionError: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

suspend fun <T> withMcpAdbClient(
    block: suspend (McpConnection) -> T
): T {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val defaultCommand = defaultAdbServerCommand()
    val command = resolveCommand(
        envVarName = "MCP_ADB_SERVER_COMMAND",
        defaultCommand = defaultCommand,
        logTag = "MCP-ADB-CLIENT"
    )

    println("🔧 [MCP-ADB-CLIENT] Starting MCP ADB server process: ${command.joinToString(" ")}")

    val connection = try {
        McpConnection.start(
            config = McpServerConfig(command),
            json = json,
            logTag = "MCP-ADB-CLIENT"
        )
    } catch (t: Throwable) {
        throw McpAdbClientException(
            message = t.message ?: "Failed to start MCP ADB server",
            isConnectionError = true,
            cause = t
        )
    }

    try {
        val initializeResponse = connection.initialize()
        initializeResponse.error?.let { error ->
            throw McpAdbClientException(
                message = "MCP initialize failed: ${error.message}",
                isConnectionError = true
            )
        }

        val toolsListResponse = connection.sendRequest(
            method = "tools/list",
            params = buildJsonObject { }
        )
        toolsListResponse.error?.let { error ->
            throw McpAdbClientException(
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

        val requiredTools = setOf("adb.list_devices", "adb.get_bugreport")
        val missingTools = requiredTools - availableTools
        if (missingTools.isNotEmpty()) {
            throw McpAdbClientException(
                message = "Missing required MCP tools: ${missingTools.joinToString()}",
                isConnectionError = true
            )
        }

        return block(connection)
    } finally {
        connection.close()
    }
}

suspend fun McpConnection.adbListDevices(): AdbListDevicesResult {
    val response = sendRequest(
        method = "tools/call",
        params = buildJsonObject {
            put("name", JsonPrimitive("adb.list_devices"))
            put("arguments", buildJsonObject { })
        }
    )

    response.error?.let { error ->
        throw McpAdbClientException(
            message = "MCP error for adb.list_devices: ${error.message}",
            isConnectionError = false
        )
    }

    val result: JsonObject = response.result?.jsonObject?.unwrapStructuredContent()
        ?: throw McpAdbClientException(
            message = "Unexpected MCP result for adb.list_devices: null",
            isConnectionError = false
        )

    val devicesJson = result["devices"]?.jsonArray
        ?: throw McpAdbClientException(
            message = "Unexpected MCP result for adb.list_devices: $result",
            isConnectionError = false
        )

    val devices = devicesJson.mapNotNull { element ->
        val obj = element.jsonObject
        val serial = obj["serial"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val model = obj["model"]?.jsonPrimitive?.contentOrNull
        val device = obj["device"]?.jsonPrimitive?.contentOrNull
        AdbDevice(
            serial = serial,
            state = state,
            model = model,
            device = device
        )
    }

    return AdbListDevicesResult(devices)
}

suspend fun McpConnection.adbGetBugreport(serial: String): AdbBugreportResult {
    val response = sendRequest(
        method = "tools/call",
        params = buildJsonObject {
            put("name", JsonPrimitive("adb.get_bugreport"))
            put(
                "arguments",
                buildJsonObject {
                    put("serial", JsonPrimitive(serial))
                }
            )
        }
    )

    response.error?.let { error ->
        throw McpAdbClientException(
            message = "MCP error for adb.get_bugreport: ${error.message}",
            isConnectionError = false
        )
    }

    val result: JsonObject = response.result?.jsonObject?.unwrapStructuredContent()
        ?: throw McpAdbClientException(
            message = "Unexpected MCP result for adb.get_bugreport: null",
            isConnectionError = false
        )

    val filePath = result["filePath"]?.jsonPrimitive?.contentOrNull
        ?: throw McpAdbClientException(
            message = "Unexpected MCP result for adb.get_bugreport: $result",
            isConnectionError = false
        )

    println("📁 [MCP-ADB-CLIENT] adb.get_bugreport -> filePath=$filePath")

    return AdbBugreportResult(filePath)
}
