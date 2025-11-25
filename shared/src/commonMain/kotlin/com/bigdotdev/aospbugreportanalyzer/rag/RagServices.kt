package com.bigdotdev.aospbugreportanalyzer.rag

/**
 * Провайдеры платформенных зависимостей для RAG.
 */
object RagServiceLocator {
    var embeddingProvider: suspend (text: String, model: String) -> List<Double> = { _, _ ->
        error("Embedding provider is not configured")
    }

    var llmResponder: suspend (prompt: String) -> String = {
        error("LLM responder is not configured")
    }
}

suspend fun embedQuestionForRag(text: String, model: String): List<Double> =
    RagServiceLocator.embeddingProvider(text, model)
