package org.jason.siph.persistence

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jason.siph.domain.production.AuditEvent
import org.jason.siph.domain.production.WormAuditArchive
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors

class FileSystemWormAuditArchive(
    private val directory: Path,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }
) : WormAuditArchive {
    private val mutex = Mutex()
    private var loaded = false
    private val events = mutableListOf<AuditEvent>()
    private val byId = linkedMapOf<String, AuditEvent>()

    override suspend fun append(event: AuditEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            byId[event.id]?.let { existing ->
                require(existing.eventHash == event.eventHash) {
                    "WORM archive rejects mutation of audit event ${event.id}"
                }
                return@withLock
            }
            val expectedPrevious = events.lastOrNull()?.eventHash
            require(event.previousHash == expectedPrevious) {
                "WORM audit chain mismatch: expected=$expectedPrevious, actual=${event.previousHash}"
            }
            val sequence = events.size + 1L
            val safeId = event.id.map { if (it.isLetterOrDigit() || it in "-_") it else '_' }.joinToString("")
            val path = directory.resolve("${sequence.toString().padStart(16, '0')}-$safeId.json")
            val bytes = json.encodeToString(event).toByteArray(StandardCharsets.UTF_8)
            Files.newByteChannel(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(bytes))
            }
            events += event
            byId[event.id] = event
        }
    }

    override suspend fun latest(): AuditEvent? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            events.lastOrNull()
        }
    }

    override suspend fun list(limit: Int): List<AuditEvent> = withContext(Dispatchers.IO) {
        require(limit > 0)
        mutex.withLock {
            ensureLoaded()
            events.takeLast(limit).reversed()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        Files.createDirectories(directory)
        val files = Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
        files.forEach { path ->
            val event = json.decodeFromString<AuditEvent>(Files.readString(path))
            require(byId.putIfAbsent(event.id, event) == null) {
                "Duplicate audit event ID in WORM archive: ${event.id}"
            }
            val expectedPrevious = events.lastOrNull()?.eventHash
            require(event.previousHash == expectedPrevious) {
                "Persisted WORM audit chain is broken at ${event.id}"
            }
            events += event
        }
        loaded = true
    }
}

class JvmWormAuditHttpServer(
    private val archive: WormAuditArchive,
    bindAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val bearerToken: String? = null,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
) : AutoCloseable {
    private val server = HttpServer.create(bindAddress, 64).apply {
        executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "siph-worm-audit-http").apply { isDaemon = true }
        }
        createContext("/health", ::handleHealth)
        createContext("/api/v1/audit/events", ::handleAppend)
    }

    val endpoint: java.net.URI
        get() = java.net.URI.create("http://127.0.0.1:${server.address.port}/api/v1/audit/events")

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(1)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handleHealth(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.respond(405, "method not allowed")
            return
        }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.respond(200, "{\"status\":\"ok\"}")
    }

    private fun handleAppend(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.respond(405, "method not allowed")
            return
        }
        if (!authorized(exchange)) {
            exchange.respond(401, "unauthorized")
            return
        }
        val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val event = runCatching { json.decodeFromString<AuditEvent>(body) }.getOrElse {
            exchange.respond(400, "invalid audit event: ${it.message}")
            return
        }
        runCatching { runBlocking { archive.append(event) } }
            .onSuccess {
                exchange.responseHeaders.add("X-Audit-Receipt", event.eventHash)
                exchange.respond(201, "{\"accepted\":true}")
            }
            .onFailure {
                exchange.respond(409, "audit append rejected: ${it.message}")
            }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val expected = bearerToken ?: return true
        val actual = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return constantTimeEquals("Bearer $expected", actual)
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        var difference = expected.length xor actual.length
        val maximum = maxOf(expected.length, actual.length)
        repeat(maximum) { index ->
            val left = expected.getOrNull(index)?.code ?: 0
            val right = actual.getOrNull(index)?.code ?: 0
            difference = difference or (left xor right)
        }
        return difference == 0
    }

    private fun HttpExchange.respond(status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
