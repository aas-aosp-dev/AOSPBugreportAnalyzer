package com.bigdotdev.aospbugreportanalyzer.rag

import kotlin.math.sqrt

fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
    require(a.size == b.size) { "Vector sizes must match: ${a.size} vs ${b.size}" }
    if (a.isEmpty()) return 0.0
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        val va = a[i]
        val vb = b[i]
        dot += va * vb
        normA += va * va
        normB += vb * vb
    }
    if (normA == 0.0 || normB == 0.0) return 0.0
    return dot / (sqrt(normA) * sqrt(normB))
}

suspend fun retrieveRelevantChunks(
    question: String,
    index: EmbeddingIndex,
    topK: Int = 5
): List<ScoredChunk> {
    val questionEmbedding = embedQuestionForRag(question, index.model)
    val scored = index.chunks.map { chunk ->
        val score = cosineSimilarity(questionEmbedding, chunk.embedding)
        ScoredChunk(chunk, score)
    }
    return scored.sortedByDescending { it.score }.take(topK)
}

fun buildRagPrompt(
    question: String,
    scoredChunks: List<ScoredChunk>
): String {
    return buildString {
        appendLine("You are an assistant that analyzes Android bugreports.")
        appendLine("You are given relevant excerpts (\"chunks\") from a bugreport and a question.")
        appendLine()
        appendLine("Use ONLY the provided context to answer the question.")
        appendLine("If the context does not contain the answer, say that it is not clearly present.")
        appendLine()
        appendLine("===== CONTEXT START =====")
        scoredChunks.forEachIndexed { index, scored ->
            appendLine("Chunk #${index + 1}")
            appendLine("Chunk ID: ${scored.chunk.id}")
            if (scored.chunk.metadata.isNotEmpty()) {
                val metaText = scored.chunk.metadata.entries.joinToString { "${it.key}=${it.value}" }
                appendLine("Meta: $metaText")
            }
            appendLine("Score: ${"%.4f".format(scored.score)}")
            appendLine()
            appendLine(scored.chunk.text)
            appendLine("---")
        }
        appendLine("===== CONTEXT END =====")
        appendLine()
        appendLine("Question: $question")
        appendLine()
        appendLine("Answer in concise technical Russian.")
    }
}

suspend fun askLlmPlain(question: String): String = RagServiceLocator.llmResponder(question)

suspend fun askLlmWithRag(
    question: String,
    index: EmbeddingIndex,
    topK: Int = 5
): RagResult {
    val scoredChunks = retrieveRelevantChunks(question, index, topK)
    val prompt = buildRagPrompt(question, scoredChunks)
    val answer = RagServiceLocator.llmResponder(prompt)
    return RagResult(answer = answer, usedChunks = scoredChunks)
}

suspend fun compareRagAndPlain(
    question: String,
    index: EmbeddingIndex,
    topK: Int = 5
): RagComparison {
    val plain = askLlmPlain(question)
    val rag = askLlmWithRag(question, index, topK)
    return RagComparison(
        question = question,
        plainAnswer = plain,
        ragAnswer = rag.answer,
        usedChunks = rag.usedChunks
    )
}
