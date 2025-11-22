package com.bigdotdev.aospbugreportanalyzer.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun JsonObject.unwrapStructuredContent(): JsonObject =
    this["structuredContent"]?.jsonObject ?: this
