package com.bigdotdev.aospbugreportanalyzer.rag

import com.bigdotdev.aospbugreportanalyzer.BugreportChunk
import com.bigdotdev.aospbugreportanalyzer.BugreportChunkEmbedding
import com.bigdotdev.aospbugreportanalyzer.BugreportIndex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagLogicTest {
    private fun runSuspendTest(block: suspend () -> Unit) = runTest { block() }

    @Test
    fun cosineSimilarityHandlesZeroVectors() {
        val result = cosineSimilarity(listOf(0.0, 0.0), listOf(0.0, 0.0))
        assertEquals(0.0, result)
    }

    @Test
    fun cosineSimilarityComputesDotProduct() {
        val result = cosineSimilarity(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0))
        assertEquals(1.0, result)
    }

    @Test
    fun retrieveRelevantChunksReturnsTopKSorted() = runSuspendTest {
        RagServiceLocator.embeddingProvider = { _, _ -> listOf(1.0, 0.0) }
        val index = EmbeddingIndex(
            model = "test",
            dimension = 2,
            chunks = listOf(
                EmbeddedChunk("1", "a", embedding = listOf(1.0, 0.0)),
                EmbeddedChunk("2", "b", embedding = listOf(0.0, 1.0))
            )
        )
        val result = retrieveRelevantChunks("question", index, topK = 1)
        assertEquals(1, result.size)
        assertEquals("1", result.first().chunk.id)
        assertTrue(result.first().score > 0.9)
    }

    @Test
    fun buildRagPromptIncludesChunksAndQuestion() {
        val prompt = buildRagPrompt(
            question = "Почему ANR?",
            scoredChunks = listOf(
                ScoredChunk(
                    chunk = EmbeddedChunk("1", "Stacktrace", embedding = listOf(1.0)),
                    score = 0.8
                )
            )
        )
        assertTrue(prompt.contains("Stacktrace"))
        assertTrue(prompt.contains("Почему ANR?"))
    }

    @Test
    fun compareRagAndPlainUsesBothPaths() = runSuspendTest {
        RagServiceLocator.embeddingProvider = { _, _ -> listOf(1.0) }
        RagServiceLocator.llmResponder = { prompt ->
            if (prompt.contains("CONTEXT START")) "rag" else "plain"
        }
        val index = BugreportIndex(
            bugreportSourcePath = "file",
            createdAt = "now",
            model = "m",
            chunkSize = 1,
            chunkOverlap = 0,
            chunks = listOf(BugreportChunk(1, 0, 1, "text")),
            embeddings = listOf(BugreportChunkEmbedding(1, listOf(1.0)))
        ).toEmbeddingIndex()
        val comparison = compareRagAndPlain("q", index, topK = 1)
        assertEquals("plain", comparison.plainAnswer)
        assertEquals("rag", comparison.ragAnswer)
        assertEquals(1, comparison.usedChunks.size)
    }
}
