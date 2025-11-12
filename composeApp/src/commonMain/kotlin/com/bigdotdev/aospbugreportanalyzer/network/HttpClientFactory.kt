package com.bigdotdev.aospbugreportanalyzer.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.ContentType
import io.ktor.http.contentType

fun createHttpClient(): HttpClient = HttpClient(httpClientEngineFactory()) {
    expectSuccess = false
    install(Logging) {
        level = LogLevel.INFO
    }
}

expect fun httpClientEngineFactory(): HttpClientEngineFactory<*>

fun HttpRequestBuilder.applyJsonHeaders() {
    contentType(ContentType.Application.Json)
}
