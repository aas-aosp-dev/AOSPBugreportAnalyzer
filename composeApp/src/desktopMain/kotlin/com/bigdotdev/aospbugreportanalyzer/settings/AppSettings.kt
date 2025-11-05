package com.bigdotdev.aospbugreportanalyzer.settings

/**
 * Desktop-specific application settings that control JSON formatting behaviour.
 */
data class AppSettings(
    val strictJsonEnabled: Boolean = false,
    val systemPromptText: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT: String = """Ты — строгий форматтер. Всегда возвращай ТОЛЬКО валидный JSON UTF-8, без Markdown, без комментариев и без пояснений.\nФормат ответа:\n{ \"version\": \"1.0\", \"ok\": <true|false>, \"generated_at\": \"<ISO8601>\", \"items\": [], \"error\": \"<string>\" }"""
    }
}
