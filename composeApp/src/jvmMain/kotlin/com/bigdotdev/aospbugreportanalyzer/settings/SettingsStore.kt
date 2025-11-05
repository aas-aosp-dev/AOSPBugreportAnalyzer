package com.bigdotdev.aospbugreportanalyzer.settings

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * Persists [AppSettings] in the user's home directory under
 * `~/.aosp-bugreport-analyzer/settings.properties`.
 */
object SettingsStore {
    private val settingsDirectory: Path = Path.of(System.getProperty("user.home"), ".aosp-bugreport-analyzer")
    private val settingsFile: Path = settingsDirectory.resolve("settings.properties")

    fun load(): AppSettings {
        val properties = Properties()
        if (Files.notExists(settingsFile)) {
            return AppSettings()
        }

        try {
            BufferedInputStream(Files.newInputStream(settingsFile, StandardOpenOption.READ)).use { input ->
                properties.load(input)
            }
        } catch (ioe: IOException) {
            // If anything goes wrong while reading, gracefully fall back to defaults.
            return AppSettings()
        }

        val strictJsonEnabled = properties.getProperty(KEY_STRICT_JSON_ENABLED)?.toBooleanStrictOrNull() ?: false
        val systemPromptText = properties.getProperty(KEY_SYSTEM_PROMPT)?.takeIf { it.isNotEmpty() }
            ?: AppSettings.DEFAULT_SYSTEM_PROMPT

        return AppSettings(strictJsonEnabled = strictJsonEnabled, systemPromptText = systemPromptText)
    }

    fun save(settings: AppSettings) {
        val properties = Properties().apply {
            setProperty(KEY_STRICT_JSON_ENABLED, settings.strictJsonEnabled.toString())
            setProperty(KEY_SYSTEM_PROMPT, settings.systemPromptText)
        }

        try {
            Files.createDirectories(settingsDirectory)
            BufferedOutputStream(
                Files.newOutputStream(
                    settingsFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
            ).use { output ->
                properties.store(output, null)
            }
        } catch (ioe: IOException) {
            throw IOException("Failed to persist settings to $settingsFile", ioe)
        }
    }

    private fun String.toBooleanStrictOrNull(): Boolean? = when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private const val KEY_STRICT_JSON_ENABLED = "strictJsonEnabled"
    private const val KEY_SYSTEM_PROMPT = "systemPromptText"
}
