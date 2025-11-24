package com.bigdotdev.aospbugreportanalyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Локальные "эмбеддинги" без внешних API.
 *
 * Для каждого текста строим вектор фиксированной длины (по умолчанию 128)
 * по схеме hash-bag-of-words:
 *  - разбиваем текст на слова
 *  - для каждого слова считаем hashCode()
 *  - бакет = (hash & Int.MAX_VALUE) % dim
 *  - увеличиваем счётчик в этом бакете
 *
 * Это не семантические эмбеддинги, но для демонстрации RAG-индекса
 * (День 15: чанки + векторы + JSON) этого достаточно.
 */
suspend fun callOpenRouterEmbeddings(
    texts: List<String>,
    model: String,              // игнорируем, но оставляем для совместимости
    apiKeyOverride: String? = null
): Result<List<List<Double>>> = withContext(Dispatchers.Default) {

    if (texts.isEmpty()) {
        return@withContext Result.success(emptyList())
    }

    // Размер вектора можно потом вынести в конфиг
    val dim = 128

    try {
        val embeddings = texts.map { text ->
            val vector = DoubleArray(dim) { 0.0 }

            // очень простое разбиение, нам хватает
            val words = text.split(Regex("\\s+"))
                .filter { it.isNotBlank() }

            for (word in words) {
                val h = word.hashCode()
                val idx = (h and Int.MAX_VALUE) % dim
                vector[idx] += 1.0
            }

            vector.toList()
        }

        Result.success(embeddings)
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
