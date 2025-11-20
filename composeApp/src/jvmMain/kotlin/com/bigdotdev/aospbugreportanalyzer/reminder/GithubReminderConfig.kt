package com.bigdotdev.aospbugreportanalyzer.reminder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File


data class GithubReminderConfig(
    val enabled: Boolean,
    val intervalMinutes: Long,
    val lastRunAtEpochMillis: Long?
)

private val defaultConfig = GithubReminderConfig(
    enabled = false,
    intervalMinutes = 1,
    lastRunAtEpochMillis = null
)

object GithubReminderStorage {
    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    private val configFile: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val dir = File(userHome, ".aosp_bugreport_analyzer")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        File(dir, "github_reminder.json")
    }

    fun load(): GithubReminderConfig {
        val file = configFile
        if (!file.exists()) {
            return defaultConfig
        }
        val text = runCatching { file.readText() }.getOrElse {
            println("[GithubReminder] Failed to read config: ${it.message}")
            return defaultConfig
        }
        if (text.isBlank()) {
            return defaultConfig
        }
        return runCatching {
            val element: JsonObject = json.decodeFromString(text)
            val enabled = element["enabled"]?.jsonPrimitive?.booleanOrNull ?: defaultConfig.enabled
            val interval = element["intervalMinutes"]?.jsonPrimitive?.longOrNull
                ?: element["intervalMinutes"]?.jsonPrimitive?.intOrNull?.toLong()
                ?: defaultConfig.intervalMinutes
            val lastRun = element["lastRunAtEpochMillis"]?.jsonPrimitive?.longOrNull
                ?: element["lastRunAtEpochMillis"]?.jsonPrimitive?.doubleOrNull?.toLong()
            GithubReminderConfig(
                enabled = enabled,
                intervalMinutes = interval,
                lastRunAtEpochMillis = lastRun
            )
        }.getOrElse {
            println("[GithubReminder] Failed to parse config: ${it.message}")
            defaultConfig
        }
    }

    fun save(config: GithubReminderConfig) {
        val file = configFile
        file.parentFile?.mkdirs()
        val jsonText = buildString {
            appendLine("{")
            appendLine("  \"enabled\": ${config.enabled},")
            appendLine("  \"intervalMinutes\": ${config.intervalMinutes},")
            append("  \"lastRunAtEpochMillis\": ")
            append(config.lastRunAtEpochMillis?.toString() ?: "null")
            appendLine()
            append('}')
        }
        runCatching {
            file.writeText(jsonText)
        }.onFailure {
            println("[GithubReminder] Failed to save config: ${it.message}")
        }
    }
}
