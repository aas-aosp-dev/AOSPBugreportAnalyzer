package com.bigdotdev.aospbugreportanalyzer.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private fun projectRoot(): Path = Paths.get("").toAbsolutePath()

private fun mcpServerPath(fileName: String): Path =
    projectRoot()
        .resolveSibling("AOSPBugreportAnalyzerMCPServer")
        .resolve("src")
        .resolve(fileName)

private fun legacyGithubCommand(): List<String> = listOf(
    "npx",
    "tsx",
    "/Users/artem/work/projects/kmp/AOSPBugreportAnalyzerMCPServer/src/server.ts"
)

internal fun defaultGithubServerCommand(): List<String> {
    val path = mcpServerPath("server.ts")
    return if (Files.exists(path)) {
        listOf("npx", "tsx", path.toString())
    } else {
        legacyGithubCommand()
    }
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
