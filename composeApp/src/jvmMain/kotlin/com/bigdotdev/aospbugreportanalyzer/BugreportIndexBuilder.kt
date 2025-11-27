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

private const val BUGREPORT_INDEX_CHUNK_SIZE = 800
private const val BUGREPORT_INDEX_CHUNK_OVERLAP = 200
const val DEFAULT_EMBEDDINGS_MODEL = "nomic-embed-text"

suspend fun buildBugreportIndex(
    bugreportText: String,
    bugreportSourcePath: String,
    embeddingsModel: String,
    apiKey: String
): BugreportIndex {
    val chunks = chunkBugreportText(
        fullText = bugreportText,
        chunkSizeChars = BUGREPORT_INDEX_CHUNK_SIZE,
        chunkOverlapChars = BUGREPORT_INDEX_CHUNK_OVERLAP
    )

    val normalizedChunks = mutableListOf<BugreportChunk>()
    val chunkEmbeddings = mutableListOf<BugreportChunkEmbedding>()
    var nextChunkId = 0
    var failedChunks = 0

    chunks.forEachIndexed { index, chunk ->
        val embeddings = embedChunkRobust(
            chunkText = chunk.text,
            model = embeddingsModel,
            apiKey = apiKey
        )

        if (embeddings.isEmpty()) {
            failedChunks++
            HistoryLogger.log(
                "Skipping chunk #$index for embeddings: failed after splits, length=${chunk.text.length}"
            )
            return@forEachIndexed
        }

        embeddings.forEachIndexed { subIndex, embedding ->
            val chunkId = nextChunkId++
            val textForEmbedding = if (embeddings.size == 1) chunk.text else chunk.text.take(BUGREPORT_EMBEDDING_CHUNK_LIMIT_CHARS)

            normalizedChunks.add(
                BugreportChunk(
                    id = chunkId,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset,
                    text = textForEmbedding
                )
            )

            chunkEmbeddings.add(
                BugreportChunkEmbedding(
                    chunkId = chunkId,
                    embedding = embedding
                )
            )
        }
    }

    if (failedChunks > 50) {
        HistoryLogger.log("Warning: many embedding chunks failed, index may be incomplete.")
    }

    return BugreportIndex(
        bugreportSourcePath = bugreportSourcePath,
        createdAt = Instant.now().toString(),
        model = embeddingsModel,
        chunkSize = BUGREPORT_INDEX_CHUNK_SIZE,
        chunkOverlap = BUGREPORT_INDEX_CHUNK_OVERLAP,
        chunks = normalizedChunks.ifEmpty { chunks },
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
