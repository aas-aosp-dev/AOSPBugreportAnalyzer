package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.storage.HistoryLogger
import com.bigdotdev.aospbugreportanalyzer.storage.StoragePaths
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

private val indexJson = Json { ignoreUnknownKeys = true }

fun loadLatestBugreportIndex(): BugreportIndex? {
    val dir = StoragePaths.indexesDir
    if (!Files.exists(dir)) return null
    val indexPath = Files.list(dir).use { stream ->
        stream
            .filter { it.fileName.toString().endsWith(".json") }
            .sorted { a, b ->
                val at = Files.getLastModifiedTime(a).toMillis()
                val bt = Files.getLastModifiedTime(b).toMillis()
                bt.compareTo(at)
            }
            .findFirst()
            .orElse(null)
    } ?: return null

    return loadBugreportIndex(indexPath)
}

fun loadBugreportIndex(path: Path): BugreportIndex? {
    return runCatching {
        val text = Files.readString(path)
        indexJson.decodeFromString(BugreportIndex.serializer(), text)
    }.onFailure { error ->
        HistoryLogger.log("[BugreportIndex] Failed to load index from $path: ${error.message}")
    }.getOrNull()
}
