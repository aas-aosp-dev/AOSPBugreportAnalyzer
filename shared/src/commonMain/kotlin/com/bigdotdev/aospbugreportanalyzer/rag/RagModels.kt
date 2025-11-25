package com.bigdotdev.aospbugreportanalyzer.rag

import com.bigdotdev.aospbugreportanalyzer.BugreportIndex

/**
 * Чанк bugreport с его эмбеддингом и метаданными для RAG.
 */
data class EmbeddedChunk(
    val id: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
    val embedding: List<Double>
)

/**
 * Индекс эмбеддингов с моделью и измерением.
 */
data class EmbeddingIndex(
    val model: String,
    val dimension: Int,
    val chunks: List<EmbeddedChunk>
)

/**
 * Чанк с оценкой релевантности.
 */
data class ScoredChunk(
    val chunk: EmbeddedChunk,
    val score: Double
)

/**
 * Результат RAG-запроса.
 */
data class RagResult(
    val answer: String,
    val usedChunks: List<ScoredChunk>
)

/**
 * Сравнение ответов с RAG и без.
 */
data class RagComparison(
    val question: String,
    val plainAnswer: String,
    val ragAnswer: String,
    val usedChunks: List<ScoredChunk>
)

/**
 * Режим работы RAG в UI.
 */
enum class RagMode {
    PLAIN,
    RAG_ONLY,
    COMPARE
}

/**
 * Конвертация сохранённого индекса bugreport в EmbeddingIndex для RAG.
 */
fun BugreportIndex.toEmbeddingIndex(): EmbeddingIndex {
    val embeddingMap = embeddings.associateBy { it.chunkId }
    val embeddedChunks = chunks.mapNotNull { chunk ->
        val embedding = embeddingMap[chunk.id]?.embedding ?: return@mapNotNull null
        EmbeddedChunk(
            id = chunk.id.toString(),
            text = chunk.text,
            metadata = mapOf(
                "bugreport" to bugreportSourcePath,
                "range" to "${chunk.startOffset}-${chunk.endOffset}"
            ),
            embedding = embedding
        )
    }
    val dimension = embeddedChunks.firstOrNull()?.embedding?.size ?: 0
    return EmbeddingIndex(
        model = model,
        dimension = dimension,
        chunks = embeddedChunks
    )
}
