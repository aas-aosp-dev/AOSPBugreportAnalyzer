package com.bigdotdev.aospbugreportanalyzer.infra

class RateLimiter {
    suspend fun <T> withPermit(block: suspend () -> T): T = block()
}
