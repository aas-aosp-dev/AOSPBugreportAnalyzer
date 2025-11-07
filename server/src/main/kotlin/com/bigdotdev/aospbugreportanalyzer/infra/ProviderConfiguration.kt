package com.bigdotdev.aospbugreportanalyzer.infra

data class ProviderConfiguration(
    val baseUrl: String,
    val openRouterApiKey: String?,
    val openAiApiKey: String?,
    val groqApiKey: String?,
    val referer: String?,
    val title: String?
) {
    companion object {
        private const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        private const val DEFAULT_REFERER = "https://github.com/aas-aosp-dev/AOSPBugreportAnalyzer"
        private const val DEFAULT_TITLE = "AOSP Bugreport Analyzer"

        fun fromEnvironment(): ProviderConfiguration = ProviderConfiguration(
            baseUrl = System.getenv("OPENROUTER_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_OPENROUTER_BASE_URL,
            openRouterApiKey = System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() },
            openAiApiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() },
            groqApiKey = System.getenv("GROQ_API_KEY")?.takeIf { it.isNotBlank() },
            referer = System.getenv("OPENROUTER_REFERER")?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERER,
            title = System.getenv("OPENROUTER_TITLE")?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
        )
    }
}
