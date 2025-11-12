package com.bigdotdev.aospbugreportanalyzer.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun httpClientEngineFactory(): HttpClientEngineFactory<*> = Darwin
