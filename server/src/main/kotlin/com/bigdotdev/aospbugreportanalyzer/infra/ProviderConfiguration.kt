package com.bigdotdev.aospbugreportanalyzer.infra

data class ProviderConfiguration(
    val baseUrl: String,
    val apiKey: String?,
    val referer: String? = null,
    val title: String? = null
)
