package com.bigdotdev.aospbugreportanalyzer.domain

import kotlinx.serialization.json.JsonElement

data class ChatResponse(
    val ok: Boolean,
    val contentType: String,
    val data: JsonElement? = null,
    val text: String? = null,
    val error: String? = null,
    val retryAfterMs: Long? = null
)
