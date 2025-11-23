package com.bigdotdev.aospbugreportanalyzer.mcp

import java.nio.file.Path
import java.nio.file.Paths

private fun mcpServerPath(fileName: String): Path =
    Paths.get("../..", "AOSPBugreportAnalyzerMCPServer", "src", fileName)

internal fun defaultGithubServerCommand(): List<String> {
    val path = mcpServerPath("server.ts")
    return listOf("npx", "tsx", path.toString())
}

internal fun defaultAdbServerCommand(): List<String> {
    val path = mcpServerPath("adbServer.ts")
    return listOf("npx", "tsx", path.toString())
}

internal fun resolveCommand(
    envVarName: String,
    defaultCommand: List<String>,
    logTag: String
): List<String> {
    val envValue = System.getenv(envVarName)?.takeIf { it.isNotBlank() }
    if (envValue != null) {
        val parts = envValue.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            println("🔧 [$logTag] Using command from $$envVarName: ${parts.joinToString(" ")}")
            return parts
        }
    }
    return defaultCommand
}
