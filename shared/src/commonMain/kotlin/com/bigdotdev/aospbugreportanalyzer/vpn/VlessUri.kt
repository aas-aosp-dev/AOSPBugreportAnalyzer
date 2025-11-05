package com.bigdotdev.aospbugreportanalyzer.vpn

import kotlinx.serialization.Serializable

/**
 * Represents a parsed VLESS URI. The URI is typically delivered in the following format:
 *
 * ```
 * vless://<uuid>@<host>:<port>?encryption=none&security=reality#DisplayName
 * ```
 */
@Serializable
data class VlessUri(
    val userId: String,
    val host: String,
    val port: Int,
    val parameters: Map<String, String> = emptyMap(),
    val displayName: String? = null,
) {
    init {
        require(userId.isNotBlank()) { "VLESS user id must not be blank" }
        require(host.isNotBlank()) { "VLESS host must not be blank" }
        require(port in 1..65535) { "VLESS port must be in range 1..65535" }
    }

    val security: String?
        get() = parameters["security"]

    val transport: String?
        get() = parameters["type"] ?: parameters["transport"]

    companion object {
        private const val SCHEME = "vless://"

        fun parse(raw: String): VlessUri {
            val trimmed = raw.trim()
            require(trimmed.startsWith(SCHEME, ignoreCase = true)) {
                "VLESS key must start with \"$SCHEME\""
            }

            val withoutScheme = trimmed.substring(SCHEME.length)
            val (preFragment, fragment) = withoutScheme.split('#', limit = 2).let {
                it.first() to it.getOrNull(1)?.takeIf { fragment -> fragment.isNotBlank() }
            }

            val (authority, queryString) = preFragment.split('?', limit = 2).let {
                it.first() to it.getOrNull(1)
            }

            val (userId, hostPort) = authority.split('@', limit = 2).let {
                require(it.size == 2) { "VLESS key must contain user id and host separator '@'" }
                it[0] to it[1]
            }

            val (host, port) = parseHostAndPort(hostPort)
            val parameters = queryString?.let(::parseQueryParams).orEmpty()

            return VlessUri(
                userId = userId,
                host = host,
                port = port,
                parameters = parameters,
                displayName = fragment?.let(::decodeComponent),
            )
        }

        private fun parseHostAndPort(authority: String): Pair<String, Int> {
            if (authority.startsWith("[")) {
                val closingIndex = authority.indexOf(']')
                require(closingIndex > 0) { "Invalid IPv6 address in VLESS key" }
                val host = authority.substring(1, closingIndex)
                val portPart = authority.substring(closingIndex + 1)
                require(portPart.startsWith(':')) { "VLESS IPv6 address must be followed by port" }
                val port = portPart.substring(1).toIntOrNull()
                    ?: throw IllegalArgumentException("VLESS port must be numeric")
                return host to port
            }

            val delimiterIndex = authority.lastIndexOf(':')
            require(delimiterIndex != -1 && delimiterIndex < authority.lastIndex) {
                "VLESS key must include host and port"
            }
            val host = authority.substring(0, delimiterIndex)
            val port = authority.substring(delimiterIndex + 1).toIntOrNull()
                ?: throw IllegalArgumentException("VLESS port must be numeric")
            return host to port
        }

        private fun parseQueryParams(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val result = mutableMapOf<String, String>()
            query.split('&').forEach { entry ->
                if (entry.isBlank()) return@forEach
                val (key, value) = entry.split('=', limit = 2).let {
                    val keyPart = it.first()
                    val valuePart = it.getOrNull(1)
                    decodeComponent(keyPart) to decodeComponent(valuePart ?: "")
                }
                result[key] = value
            }
            return result
        }

        private fun decodeComponent(value: String): String {
            if (value.isEmpty()) return ""
            val builder = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                when (ch) {
                    '%' -> {
                        require(index + 2 < value.length) { "Invalid percent encoding in VLESS key" }
                        val hex = value.substring(index + 1, index + 3)
                        val decoded = hex.toIntOrNull(16)
                            ?: throw IllegalArgumentException("Invalid percent encoding in VLESS key")
                        builder.append(decoded.toChar())
                        index += 3
                    }
                    '+' -> {
                        builder.append(' ')
                        index += 1
                    }
                    else -> {
                        builder.append(ch)
                        index += 1
                    }
                }
            }
            return builder.toString()
        }
    }
}
