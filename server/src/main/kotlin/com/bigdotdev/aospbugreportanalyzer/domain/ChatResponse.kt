package com.bigdotdev.aospbugreportanalyzer.domain

import kotlinx.serialization.json.JsonObject

data class ChatResponse(
    val ok: Boolean,
    val contentType: String,
    val data: JsonObject? = null,
    val text: String? = null,
    val raw: String? = null,
    val error: String? = null
)
