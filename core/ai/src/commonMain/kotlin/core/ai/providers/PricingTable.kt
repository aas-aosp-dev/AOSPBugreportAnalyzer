package core.ai.providers

/** Pricing table per 1K tokens for known models. */
object PricingTable {
    data class Entry(
        val inputCostPer1K: Double?,
        val outputCostPer1K: Double?,
    )

    private val entries: Map<String, Entry> = mapOf(
        "mistralai/mixtral-8x7b-instruct" to Entry(
            inputCostPer1K = 0.0006,
            outputCostPer1K = 0.0008,
        ),
        "anthropic/claude-3.5-sonnet" to Entry(
            inputCostPer1K = 0.003,
            outputCostPer1K = 0.015,
        ),
    )

    fun find(model: String): Entry? = entries[model]
}
