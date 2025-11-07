package com.bigdotdev.aospbugreportanalyzer.app

import com.bigdotdev.aospbugreportanalyzer.domain.ChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class StreamChat {
    operator fun invoke(@Suppress("UNUSED_PARAMETER") request: ChatRequest): Flow<String> = flow {
        throw UnsupportedOperationException("SSE streaming is not yet implemented")
    }
}
