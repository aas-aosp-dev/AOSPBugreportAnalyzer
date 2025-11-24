package com.bigdotdev.aospbugreportanalyzer

import kotlin.math.max
import kotlin.math.min

fun chunkBugreportText(
    fullText: String,
    chunkSizeChars: Int = 1500,
    chunkOverlapChars: Int = 300
): List<BugreportChunk> {
    if (fullText.isEmpty()) return emptyList()

    val step = max(1, chunkSizeChars - chunkOverlapChars)
    val chunks = mutableListOf<BugreportChunk>()
    var start = 0
    var id = 0

    while (start < fullText.length) {
        var end = min(start + chunkSizeChars, fullText.length)

        val searchWindowStart = max(start, end - 200)
        val searchWindowEnd = min(fullText.length, end + 200)
        val windowText = fullText.substring(searchWindowStart, searchWindowEnd)

        val paragraphBoundary = findNearestBoundary(windowText, listOf("\n\n", "------"))
            ?.let { boundaryIndex -> searchWindowStart + boundaryIndex }

        if (paragraphBoundary != null && paragraphBoundary in (start + 1)..fullText.length) {
            end = max(end, paragraphBoundary)
            end = min(end, fullText.length)
        }

        val chunkText = fullText.substring(start, end)
        chunks.add(
            BugreportChunk(
                id = id++,
                startOffset = start,
                endOffset = end,
                text = chunkText
            )
        )

        start += step
    }

    return chunks
}

private fun findNearestBoundary(windowText: String, boundaries: List<String>): Int? {
    return boundaries
        .asSequence()
        .mapNotNull { boundary -> windowText.indexOf(boundary).takeIf { it >= 0 } }
        .minByOrNull { it }
}
