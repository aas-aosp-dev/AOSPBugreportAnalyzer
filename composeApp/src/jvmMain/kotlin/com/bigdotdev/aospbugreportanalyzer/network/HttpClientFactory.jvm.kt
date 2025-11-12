package com.bigdotdev.aospbugreportanalyzer.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.java.Java

actual fun httpClientEngineFactory(): HttpClientEngineFactory<*> = Java
