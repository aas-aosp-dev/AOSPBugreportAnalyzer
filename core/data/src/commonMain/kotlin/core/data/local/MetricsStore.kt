package core.data.local

import core.domain.chat.ProviderUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MetricsStore {
    private val _usage = MutableStateFlow<Map<String, ProviderUsage>>(emptyMap())
    val usage: StateFlow<Map<String, ProviderUsage>> = _usage.asStateFlow()

    fun put(messageId: String, metrics: ProviderUsage) {
        _usage.value = _usage.value + (messageId to metrics)
    }

    fun get(messageId: String): ProviderUsage? = _usage.value[messageId]
}
