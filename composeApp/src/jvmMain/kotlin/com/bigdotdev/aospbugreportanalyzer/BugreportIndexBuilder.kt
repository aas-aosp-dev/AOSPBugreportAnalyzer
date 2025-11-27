package com.bigdotdev.aospbugreportanalyzer

import com.bigdotdev.aospbugreportanalyzer.storage.HistoryLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BUGREPORT_INDEX_CHUNK_SIZE = 600
private const val BUGREPORT_INDEX_CHUNK_OVERLAP = 200
const val DEFAULT_EMBEDDINGS_MODEL = "nomic-embed-text"

suspend fun buildBugreportIndex(
    bugreportText: String,
    bugreportSourcePath: String,
    embeddingsModel: String,
    apiKey: String,
    onChunksPrepared: ((Int) -> Unit)? = null,
    onChunkProcessed: ((Int, Int) -> Unit)? = null
): BugreportIndex {
    val chunks = chunkBugreportText(
        fullText = bugreportText,
        chunkSizeChars = BUGREPORT_INDEX_CHUNK_SIZE,
        chunkOverlapChars = BUGREPORT_INDEX_CHUNK_OVERLAP
    )
    onChunksPrepared?.invoke(chunks.size)

    val chunkTexts = chunks.map { it.text }
    val embeddingsResult = callOpenRouterEmbeddings(
        texts = chunkTexts,
        model = embeddingsModel,
        apiKeyOverride = apiKey,
        onChunkProcessed = { processed ->
            onChunkProcessed?.invoke(processed, chunks.size)
        }
    )

    val embeddings = embeddingsResult.getOrElse { error ->
        HistoryLogger.log("BugreportIndex: failed to get embeddings: ${error.message}")
        throw error
    }

    if (embeddings.size != chunks.size) {
        val error = IllegalStateException("Embeddings size ${embeddings.size} does not match chunks size ${chunks.size}")
        HistoryLogger.log("BugreportIndex: embeddings/chunks size mismatch")
        throw error
    }

    val chunkEmbeddings = chunks.mapIndexed { index, chunk ->
        BugreportChunkEmbedding(
            chunkId = chunk.id,
            embedding = embeddings.getOrNull(index) ?: emptyList()
        )
    }

    return BugreportIndex(
        bugreportSourcePath = bugreportSourcePath,
        createdAt = Instant.now().toString(),
        model = embeddingsModel,
        chunkSize = BUGREPORT_INDEX_CHUNK_SIZE,
        chunkOverlap = BUGREPORT_INDEX_CHUNK_OVERLAP,
        chunks = chunks,
        embeddings = chunkEmbeddings
    )
}

fun saveBugreportIndexToFile(
    index: BugreportIndex,
    indexDir: Path
): Path {
    val json = Json { prettyPrint = false }
    runCatching { Files.createDirectories(indexDir) }

    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())
    val sourceFileName = runCatching { Paths.get(index.bugreportSourcePath).fileName?.toString() }
        .getOrNull()
        .orEmpty()
        .ifBlank { "bugreport" }
    val normalizedSource = sourceFileName
        .replace(".zip", "", ignoreCase = true)
        .replace(".txt", "", ignoreCase = true)
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "bugreport" }

    val fileName = "$normalizedSource-$timestamp-index.json"
    val target = indexDir.resolve(fileName)

    val payload = json.encodeToString(BugreportIndex.serializer(),index)
    Files.writeString(target, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    HistoryLogger.log("[BugreportIndex] Saved bugreport index to $target, chunks=${index.chunks.size}, model=${index.model}")

    return target
}
