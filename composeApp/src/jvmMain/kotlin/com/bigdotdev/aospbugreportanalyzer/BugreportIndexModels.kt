package com.bigdotdev.aospbugreportanalyzer

import kotlinx.serialization.Serializable

@Serializable
data class BugreportChunk(
    val id: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String
)

@Serializable
data class BugreportChunkEmbedding(
    val chunkId: Int,
    val embedding: List<Double>
)

@Serializable
data class BugreportIndex(
    val bugreportSourcePath: String,
    val createdAt: String,
    val model: String,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val chunks: List<BugreportChunk>,
    val embeddings: List<BugreportChunkEmbedding>
)
