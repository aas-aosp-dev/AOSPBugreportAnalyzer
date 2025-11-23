package com.bigdotdev.aospbugreportanalyzer.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object HistoryLogger {
    private val historyFile: Path by lazy {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        StoragePaths.historyDir.resolve("history-$timestamp-session.log").also { path ->
            try {
                Files.createDirectories(path.parent)
                if (!Files.exists(path)) {
                    Files.writeString(path, "[History] Session started at $timestamp\n")
                }
            } catch (t: Throwable) {
                println("[History] Failed to init history file ${path.toAbsolutePath()}: ${t.message}")
            }
        }
    }

    fun log(line: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val finalLine = "[$timestamp] $line\n"
        runCatching {
            Files.writeString(
                historyFile,
                finalLine,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }.onFailure {
            println("[History] Failed to write history: ${it.message}")
        }
    }

    fun filePath(): Path = historyFile
}
