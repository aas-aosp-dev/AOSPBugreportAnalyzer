package com.bigdotdev.aospbugreportanalyzer.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object StoragePaths {
    private val homeDir: Path = Paths.get(System.getProperty("user.home"))
    val baseDir: Path = homeDir.resolve(".aosp_bugreport_analyzer")

    val configDir: Path = baseDir.resolve("config")
    val historyDir: Path = baseDir.resolve("history")
    val bugreportsDir: Path = baseDir.resolve("bugreports")
    val logcatDir: Path = baseDir.resolve("logcat")
    val llmInputsDir: Path = baseDir.resolve("llm_inputs")
    val indexesDir: Path = baseDir.resolve("indexes")

    fun ensureDirs() {
        listOf(baseDir, configDir, historyDir, bugreportsDir, logcatDir, llmInputsDir, indexesDir).forEach { dir ->
            try {
                Files.createDirectories(dir)
            } catch (t: Throwable) {
                println("[Storage] Failed to create dir $dir: ${t.message}")
            }
        }
    }

    init {
        ensureDirs()
    }
}
