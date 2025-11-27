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
        appendLine("Ты — ассистент, который отвечает на вопросы только на основе приведённых ниже источников (фрагментов багрепорта).")
        appendLine("Тебе дан вопрос пользователя и список источников в формате с номерами.")
        appendLine()
        appendLine("Правила ответа:")
        appendLine("- Отвечай только фактами из источников.")
        appendLine("- После каждого важного утверждения ставь ссылку на источник в виде [1], [2], [3] и т.д.")
        appendLine("- Если утверждение опирается на несколько источников, можно написать [1, 3].")
        appendLine("- Не выдумывай источники и номера — используй только те номера, которые есть в списке ниже.")
        appendLine("- Если в источниках нет ответа, прямо скажи об этом и укажи, на какие источники опирался (например: [1–3]).")
        appendLine()
        appendLine("Источники (chunks):")
        appendLine()
        scoredChunks.forEachIndexed { index, scored ->
            val sourceNumber = index + 1
            val bugreport = scored.chunk.metadata["bugreport"] ?: "unknown"
            val range = scored.chunk.metadata["range"] ?: "unknown"
            appendLine("[${sourceNumber}] bugreport=${bugreport}, range=${range}, score=${"%.4f".format(scored.score)}")
            appendLine("Текст:")
            appendLine("\"\"\"")
            appendLine(scored.chunk.text)
            appendLine("\"\"\"")
            appendLine()
        }
        appendLine("Формат ответа:")
        appendLine("- Свободный текст (можно использовать абзацы и пункты).")
        appendLine("- Обязательно должны присутствовать ссылки вида [N] в тексте.")
        appendLine("- Отвечай лаконично на русском языке.")
        appendLine()
        appendLine("Вопрос пользователя:")
        appendLine("\"$question\"")
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
    val citationRegex = "\\[\\d+]".toRegex()
    if (!citationRegex.containsMatchIn(answer)) {
        println("[RAG] Warning: LLM answer without citations for question '${question.take(80)}'")
    }
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
