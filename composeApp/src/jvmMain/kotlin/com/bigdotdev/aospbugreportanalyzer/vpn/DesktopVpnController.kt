package com.bigdotdev.aospbugreportanalyzer.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Starts and stops a local sing-box or Xray process based on a provided VLESS link.
 * The implementation is heavily inspired by how the Hiddify project orchestrates sing-box
 * configurations for VLESS connections.
 */
class DesktopVpnController(
    private val environment: Map<String, String> = System.getenv(),
) {
    private val lock = Any()
    private var activeSession: DesktopVpnSession? = null

    /** Connect to VPN using the provided [vlessKey]. */
    fun connect(vlessKey: String): DesktopVpnSession {
        val trimmed = vlessKey.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("VLESS ключ не должен быть пустым")
        }

        val parsed = VlessUri.parse(trimmed)
        val resolvedBinary = resolveBinary()
        val sessionId = UUID.randomUUID().toString()
        val localProxyPort = allocateFreePort()

        synchronized(lock) {
            if (activeSession != null) {
                throw IllegalStateException("Уже есть активное VPN-подключение")
            }
        }

        val workingDir = Files.createTempDirectory("aosp-vpn-$sessionId")
        val configPath = workingDir.resolve("config.json")
        val logPath = workingDir.resolve("vpn.log")

        val configContent = when (resolvedBinary.engine) {
            VpnEngine.SING_BOX -> buildSingBoxConfig(parsed, sessionId, localProxyPort)
            VpnEngine.XRAY -> buildXrayConfig(parsed, sessionId, localProxyPort)
        }

        configPath.writeText(configContent, charset = StandardCharsets.UTF_8)

        val command = when (resolvedBinary.engine) {
            VpnEngine.SING_BOX -> listOf(resolvedBinary.path, "run", "-c", configPath.toString())
            VpnEngine.XRAY -> listOf(resolvedBinary.path, "run", "-config", configPath.toString())
        }

        val process = try {
            ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (failure: IOException) {
            cleanupDirectory(workingDir)
            throw IllegalStateException("Не удалось запустить ${resolvedBinary.engine.displayName}: ${failure.message}", failure)
        }

        val pumpThread = startLogPump(process, logPath)
        val shutdownHook = Thread {
            runCatching { stopProcess(process, pumpThread) }
            cleanupDirectory(workingDir)
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        val exitedEarly = process.waitFor(1, TimeUnit.SECONDS)
        if (exitedEarly) {
            val exitCode = process.exitValue()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            pumpThread.join(200)
            val errorTail = runCatching { Files.readString(logPath) }.getOrElse { "" }
            cleanupDirectory(workingDir)
            throw IllegalStateException(
                "${resolvedBinary.engine.displayName} завершился с кодом $exitCode. ${errorTail.takeLast(400)}"
            )
        }

        val session = DesktopVpnSession(
            id = sessionId,
            vlessUri = parsed,
            engine = resolvedBinary.engine,
            binaryPath = resolvedBinary.path,
            workingDirectory = workingDir,
            configPath = configPath,
            logPath = logPath,
            process = process,
            logThread = pumpThread,
            shutdownHook = shutdownHook,
            createdAtMillis = Instant.now().toEpochMilli(),
            localProxyPort = localProxyPort,
        )

        synchronized(lock) {
            activeSession = session
        }
        return session
    }

    /** Disconnects currently active session. */
    fun disconnect(): DesktopVpnSession {
        val session = synchronized(lock) {
            val current = activeSession ?: throw IllegalStateException("Нет активного VPN-подключения")
            activeSession = null
            current
        }
        session.stop()
        return session
    }

    fun currentSession(): DesktopVpnSession? = synchronized(lock) { activeSession }

    private fun resolveBinary(): ResolvedVpnBinary {
        val candidates = listOf(
            environment["VPN_SING_BOX_PATH"],
            environment["SING_BOX_PATH"],
            environment["SING_BOX_BINARY"],
            environment["SING_BOX_EXECUTABLE"],
            environment["VPN_XRAY_PATH"],
            environment["XRAY_EXECUTABLE"],
            environment["XRAY_BINARY"],
            "sing-box",
            "sing-box.exe",
            "xray",
            "xray.exe",
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }

        for (candidate in candidates) {
            val resolvedPath = resolveExecutable(candidate) ?: continue
            val engine = when {
                resolvedPath.name.lowercase().contains("sing-box") -> VpnEngine.SING_BOX
                resolvedPath.name.lowercase().contains("xray") -> VpnEngine.XRAY
                else -> null
            } ?: continue
            return ResolvedVpnBinary(engine, resolvedPath.toString())
        }

        throw IllegalStateException(
            "Не найден исполняемый файл sing-box или xray. Установите его и укажите путь через переменную окружения VPN_SING_BOX_PATH или XRAY_EXECUTABLE."
        )
    }

    private fun resolveExecutable(candidate: String): Path? {
        val path = Paths.get(candidate)
        if (path.isAbsolute || candidate.contains('/') || candidate.contains('\')) {
            return if (Files.isRegularFile(path) && Files.isExecutable(path)) path.normalize() else null
        }

        val pathVar = environment["PATH"] ?: return null
        val separator = System.getProperty("path.separator")
        pathVar.split(separator)
            .asSequence()
            .mapNotNull { dir ->
                val resolved = runCatching { Paths.get(dir).resolve(candidate) }.getOrNull()
                resolved?.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
            }
            .firstOrNull()?.let { return it.normalize() }
        return null
    }

    private fun allocateFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startLogPump(process: Process, logPath: Path): Thread {
        logPath.parent?.takeIf { !it.exists() }?.createDirectories()
        return thread(name = "vpn-log-${System.identityHashCode(process)}", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { sequence ->
                Files.newBufferedWriter(logPath, StandardCharsets.UTF_8).use { writer ->
                    sequence.forEach { line ->
                        writer.appendLine(line)
                        writer.flush()
                    }
                }
            }
        }
    }

    private fun stopProcess(process: Process, logThread: Thread) {
        if (!process.isAlive) {
            logThread.join(500)
            return
        }
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        logThread.join(1000)
    }

    private fun cleanupDirectory(dir: Path) {
        if (!dir.exists() || !dir.isDirectory()) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun buildSingBoxConfig(uri: VlessUri, sessionId: String, localProxyPort: Int): String {
        val params = uri.parameters
        val security = params["security"]?.lowercase()
        val flow = params["flow"]?.ifBlank { null }
        val sni = params["sni"]?.ifBlank { null } ?: params["host"]?.ifBlank { null } ?: uri.host
        val fingerprint = params["fp"]?.ifBlank { null }
        val pbk = params["pbk"]?.ifBlank { null }
        val sid = params["sid"]?.ifBlank { null }
        val alpn = params["alpn"]?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        val transportObject = buildTransportObject(params)

        val tlsObject = if (security == "reality" || security == "tls") {
            buildJsonObject {
                put("enabled", true)
                put("server_name", sni)
                fingerprint?.let {
                    put(
                        "utls",
                        buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", it)
                        }
                    )
                }
                alpn?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("alpn", JsonArray(list.map(::JsonPrimitive)))
                }
                if (security == "reality") {
                    put(
                        "reality",
                        buildJsonObject {
                            put("enabled", true)
                            pbk?.let { put("public_key", it) }
                            sid?.let { put("short_id", it) }
                        }
                    )
                }
            }
        } else {
            JsonNull
        }

        val outbound = buildJsonObject {
            put("type", "vless")
            put("tag", "vless-out")
            put("server", uri.host)
            put("server_port", uri.port)
            put("uuid", uri.userId)
            flow?.let { put("flow", it) }
            if (params["packetEncoding"]?.lowercase() != "none") {
                put("packet_encoding", "xudp")
            }
            if (tlsObject !is JsonNull) {
                put("tls", tlsObject)
            }
            transportObject?.let { put("transport", it) }
        }

        val root = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("level", "info")
                }
            )
            put(
                "dns",
                buildJsonObject {
                    put("strategy", "prefer_ipv4")
                    put(
                        "servers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "cloudflare")
                                    put("address", "1.1.1.1")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("tag", "local")
                                    put("address", "223.5.5.5")
                                    put("detour", "direct")
                                }
                            )
                        }
                    )
                }
            )
            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "tun")
                            put("tag", "tun-in")
                            put("mtu", 1500)
                            put("auto_route", true)
                            put("strict_route", false)
                            put("endpoint_independent_nat", true)
                            put("interface_name", "aosp-vpn-$sessionId")
                            put("inet4_address", "172.19.0.1/30")
                            put("inet6_address", "fdfe:dcba:9876::1/126")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "mixed")
                            put("tag", "mixed-in")
                            put("listen", "127.0.0.1")
                            put("listen_port", localProxyPort)
                            put("tcp_fast_open", true)
                            put("udp_fragment", true)
                        }
                    )
                }
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(outbound)
                    add(
                        buildJsonObject {
                            put("type", "direct")
                            put("tag", "direct")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("type", "block")
                            put("tag", "block")
                        }
                    )
                }
            )
            put(
                "route",
                buildJsonObject {
                    put("auto_route", true)
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("protocol", "dns")
                                    put("outbound", "direct")
                                }
                            )
                        }
                    )
                }
            )
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun buildTransportObject(params: Map<String, String>): JsonObject? {
        val type = params["type"]?.lowercase() ?: params["transport"]?.lowercase() ?: "tcp"
        return when (type) {
            "ws" -> {
                buildJsonObject {
                    put("type", "ws")
                    put("path", params["path"] ?: "/")
                    val hostHeader = params["host"]?.ifBlank { null } ?: params["sni"]?.ifBlank { null }
                    hostHeader?.let {
                        put(
                            "headers",
                            buildJsonObject {
                                put("Host", it)
                            }
                        )
                    }
                }
            }

            "grpc" -> {
                buildJsonObject {
                    put("type", "grpc")
                    put("service_name", params["serviceName"] ?: params["path"] ?: "")
                }
            }

            "tcp" -> {
                val headerType = params["headerType"]?.lowercase()
                if (headerType == null || headerType == "none") {
                    buildJsonObject {
                        put("type", "tcp")
                    }
                } else {
                    buildJsonObject {
                        put("type", "tcp")
                        put(
                            "header",
                            buildJsonObject {
                                put("type", headerType)
                                params["host"]?.ifBlank { null }?.let { put("host", it) }
                                params["path"]?.ifBlank { null }?.let { put("path", it) }
                            }
                        )
                    }
                }
            }

            else -> buildJsonObject { put("type", type) }
        }
    }

    private fun buildXrayConfig(uri: VlessUri, sessionId: String, localProxyPort: Int): String {
        val params = uri.parameters
        val flow = params["flow"]?.ifBlank { null }
        val security = params["security"]?.lowercase() ?: "none"
        val sni = params["sni"]?.ifBlank { null } ?: params["host"]?.ifBlank { null }
        val fingerprint = params["fp"]?.ifBlank { null }
        val pbk = params["pbk"]?.ifBlank { null }
        val sid = params["sid"]?.ifBlank { null }
        val path = params["path"]?.ifBlank { null }
        val host = params["host"]?.ifBlank { null }
        val headerType = params["headerType"]?.ifBlank { null }
        val network = params["type"]?.ifBlank { null } ?: params["transport"]?.ifBlank { null } ?: "tcp"

        val streamSettings = buildJsonObject {
            put("network", network)
            put("security", if (security == "none") "none" else "tls")
            if (security == "reality") {
                put(
                    "realitySettings",
                    buildJsonObject {
                        put("show", false)
                        put("publicKey", pbk ?: "")
                        sid?.let { put("shortId", it) }
                        put("spiderX", path ?: "")
                    }
                )
            }
            if (security != "none") {
                put(
                    "tlsSettings",
                    buildJsonObject {
                        sni?.let { put("serverName", it) }
                        fingerprint?.let { put("fingerprint", it) }
                        if (security == "reality") {
                            put("allowInsecure", false)
                        }
                    }
                )
            }
            when (network) {
                "ws" -> {
                    put(
                        "wsSettings",
                        buildJsonObject {
                            put("path", path ?: "/")
                            host?.let {
                                put(
                                    "headers",
                                    buildJsonObject { put("Host", it) }
                                )
                            }
                        }
                    )
                }

                "grpc" -> {
                    put(
                        "grpcSettings",
                        buildJsonObject { put("serviceName", path ?: "") }
                    )
                }

                "tcp" -> {
                    headerType?.let { header ->
                        put(
                            "tcpSettings",
                            buildJsonObject {
                                put(
                                    "header",
                                    buildJsonObject {
                                        put("type", header)
                                        host?.let { put("host", it) }
                                        path?.let { put("path", it) }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        val outbound = buildJsonObject {
            put("protocol", "vless")
            put("tag", "vless-out")
            put(
                "settings",
                buildJsonObject {
                    put(
                        "vnext",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("address", uri.host)
                                    put("port", uri.port)
                                    put(
                                        "users",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", uri.userId)
                                                    put("encryption", "none")
                                                    flow?.let { put("flow", it) }
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            put("streamSettings", streamSettings)
        }

        val root = buildJsonObject {
            put(
                "log",
                buildJsonObject { put("loglevel", "warning") }
            )
            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("tag", "tun-in")
                            put("protocol", "dokodemo-door")
                            put("listen", "127.0.0.1")
                            put("port", localProxyPort)
                            put(
                                "settings",
                                buildJsonObject {
                                    put("network", "tcp,udp")
                                    put("followRedirect", true)
                                }
                            )
                            put(
                                "sniffing",
                                buildJsonObject {
                                    put("enabled", true)
                                    put(
                                        "destOverride",
                                        buildJsonArray {
                                            add(JsonPrimitive("http"))
                                            add(JsonPrimitive("tls"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(outbound)
                    add(buildJsonObject { put("protocol", "freedom"); put("tag", "direct") })
                    add(buildJsonObject { put("protocol", "blackhole"); put("tag", "block") })
                }
            )
            put(
                "routing",
                buildJsonObject {
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "field")
                                    put(
                                        "domain",
                                        buildJsonArray { add(JsonPrimitive("geosite:category-ads")) }
                                    )
                                    put("outboundTag", "block")
                                }
                            )
                        }
                    )
                }
            )
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    companion object {
        private val prettyJson = Json {
            prettyPrint = true
            encodeDefaults = false
        }
    }
}

/** Information about the running VPN session. */
class DesktopVpnSession internal constructor(
    val id: String,
    val vlessUri: VlessUri,
    val engine: VpnEngine,
    val binaryPath: String,
    val workingDirectory: Path,
    val configPath: Path,
    val logPath: Path,
    private val process: Process,
    private val logThread: Thread,
    private val shutdownHook: Thread,
    val createdAtMillis: Long,
    val localProxyPort: Int,
) {
    @Volatile
    private var stopped = false

    fun isAlive(): Boolean = process.isAlive && !stopped

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        logThread.join(1000)
        runCatching { cleanupDirectory(workingDirectory) }
    }

    private fun cleanupDirectory(dir: Path) {
        if (!dir.exists() || !dir.isDirectory()) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }
}

data class ResolvedVpnBinary(
    val engine: VpnEngine,
    val path: String,
)

enum class VpnEngine(val displayName: String) {
    SING_BOX("sing-box"),
    XRAY("xray"),
}
