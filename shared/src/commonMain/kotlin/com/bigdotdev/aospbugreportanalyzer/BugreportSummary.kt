package com.bigdotdev.aospbugreportanalyzer

const val BUGREPORT_SUMMARY_CHUNK_LIMIT = 16_000
const val BUGREPORT_SUMMARY_MAX_CHUNKS = 10

data class BugreportSummarySettings(
    val chunkLimit: Int = BUGREPORT_SUMMARY_CHUNK_LIMIT,
    val maxChunks: Int = BUGREPORT_SUMMARY_MAX_CHUNKS
)

fun splitBugreportForSummary(
    bugreportText: String,
    chunkLimit: Int = BUGREPORT_SUMMARY_CHUNK_LIMIT,
    maxChunks: Int = BUGREPORT_SUMMARY_MAX_CHUNKS
): List<String> {
    if (bugreportText.isBlank()) return emptyList()

    val lines = bugreportText.split('\n')
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    for ((index, line) in lines.withIndex()) {
        val candidateLength = current.length + line.length + 1 // +1 for the newline we will re-add
        if (candidateLength > chunkLimit && current.isNotEmpty()) {
            chunks.add(current.toString())
            current.clear()
        }

        current.appendLine(line)

        if (index == lines.lastIndex && current.isNotEmpty()) {
            chunks.add(current.toString())
        }
    }

    if (chunks.size > maxChunks) {
        val limited = chunks.take(maxChunks - 1).toMutableList()
        val tail = chunks.drop(maxChunks - 1)
        if (tail.isNotEmpty()) {
            // TODO: implement smarter tail merging when exceeding max chunk count
            limited.add(tail.joinToString(separator = "\n"))
        }
        return limited
    }

    return chunks
}

suspend fun summarizeBugreportChunk(
    chunkText: String,
    settings: BugreportSummarySettings,
    chunkSummarizer: suspend (String, BugreportSummarySettings) -> String?
): String? = chunkSummarizer(chunkText, settings)

suspend fun mergeBugreportChunkSummaries(
    chunkSummaries: List<String>,
    settings: BugreportSummarySettings,
    merger: suspend (List<String>, BugreportSummarySettings) -> String?
): String? = merger(chunkSummaries, settings)

suspend fun summarizeBugreportMultiStage(
    fullBugreportText: String,
    settings: BugreportSummarySettings = BugreportSummarySettings(),
    summarizeChunk: suspend (String, BugreportSummarySettings) -> String?,
    mergeSummaries: suspend (List<String>, BugreportSummarySettings) -> String?,
    onChunkError: (Int, Throwable) -> Unit = { _, _ -> }
): String? {
    val chunks = splitBugreportForSummary(
        bugreportText = fullBugreportText,
        chunkLimit = settings.chunkLimit,
        maxChunks = settings.maxChunks
    )

    if (chunks.isEmpty()) return null

    if (chunks.size == 1) {
        return summarizeBugreportChunk(chunks.first(), settings, summarizeChunk)
    }

    val chunkSummaries = mutableListOf<String>()
    chunks.forEachIndexed { index, chunk ->
        val summary = runCatching { summarizeBugreportChunk(chunk, settings, summarizeChunk) }
            .onFailure { onChunkError(index, it) }
            .getOrNull()
        if (!summary.isNullOrBlank()) {
            chunkSummaries.add(summary)
        }
    }

    if (chunkSummaries.isEmpty()) return null

    return mergeBugreportChunkSummaries(chunkSummaries, settings, mergeSummaries)
}
