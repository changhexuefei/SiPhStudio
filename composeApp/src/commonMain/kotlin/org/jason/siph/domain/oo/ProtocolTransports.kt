package org.jason.siph.domain.oo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class ProtocolExchangeDirection {
    Write,
    Query,
    Response
}

@Serializable
data class ProtocolExchange(
    val index: Int,
    val timestampEpochMs: Long,
    val deviceId: String,
    val direction: ProtocolExchangeDirection,
    val payload: String,
    val durationMs: Long = 0L
) {
    init {
        require(index >= 0)
        require(deviceId.isNotBlank())
        require(payload.isNotEmpty())
        require(durationMs >= 0L)
    }
}

interface TextProtocolTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun write(command: String)
    suspend fun query(command: String): String
}

class RecordingTextProtocolTransport(
    private val deviceId: String,
    private val delegate: TextProtocolTransport,
    private val nowEpochMs: () -> Long,
    private val durationMs: suspend (suspend () -> String) -> Pair<String, Long> = { block ->
        block() to 0L
    }
) : TextProtocolTransport {

    private val mutex = Mutex()
    private val mutableExchanges = mutableListOf<ProtocolExchange>()
    val exchanges: List<ProtocolExchange>
        get() = mutableExchanges.toList()

    override suspend fun connect() = delegate.connect()

    override suspend fun disconnect() = delegate.disconnect()

    override suspend fun write(command: String) {
        require(command.isNotBlank())
        delegate.write(command)
        append(ProtocolExchangeDirection.Write, command)
    }

    override suspend fun query(command: String): String {
        require(command.isNotBlank())
        append(ProtocolExchangeDirection.Query, command)
        val (response, elapsed) = durationMs { delegate.query(command) }
        append(ProtocolExchangeDirection.Response, response, elapsed)
        return response
    }

    private suspend fun append(
        direction: ProtocolExchangeDirection,
        payload: String,
        durationMs: Long = 0L
    ) {
        mutex.withLock {
            mutableExchanges += ProtocolExchange(
                index = mutableExchanges.size,
                timestampEpochMs = nowEpochMs(),
                deviceId = deviceId,
                direction = direction,
                payload = payload,
                durationMs = durationMs
            )
        }
    }
}

class ReplayTextProtocolTransport(
    exchanges: List<ProtocolExchange>,
    private val strict: Boolean = true
) : TextProtocolTransport {

    private val scripted = exchanges
        .filter { it.direction != ProtocolExchangeDirection.Write || it.payload.isNotBlank() }
    private var cursor = 0
    private var connected = false

    override suspend fun connect() {
        connected = true
        cursor = 0
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun write(command: String) {
        ensureConnected()
        val exchange = next(ProtocolExchangeDirection.Write)
        if (strict && exchange.payload != command) {
            error("Replay write mismatch: expected='${exchange.payload}', actual='$command'")
        }
    }

    override suspend fun query(command: String): String {
        ensureConnected()
        val query = next(ProtocolExchangeDirection.Query)
        if (strict && query.payload != command) {
            error("Replay query mismatch: expected='${query.payload}', actual='$command'")
        }
        return next(ProtocolExchangeDirection.Response).payload
    }

    private fun next(direction: ProtocolExchangeDirection): ProtocolExchange {
        val exchange = scripted.getOrNull(cursor)
            ?: error("Replay exhausted while expecting $direction")
        cursor += 1
        if (exchange.direction != direction) {
            error("Replay direction mismatch: expected=$direction, actual=${exchange.direction}")
        }
        return exchange
    }

    private fun ensureConnected() {
        check(connected) { "Replay transport is not connected" }
    }
}

data class ScriptedProtocolStep(
    val command: String,
    val response: String? = null
)

class ScriptedTextProtocolTransport(
    private val steps: List<ScriptedProtocolStep>,
    private val strict: Boolean = true
) : TextProtocolTransport {

    private var cursor = 0
    private var connected = false

    override suspend fun connect() {
        connected = true
        cursor = 0
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun write(command: String) {
        ensureConnected()
        val step = next()
        if (strict && step.command != command) {
            error("Script write mismatch: expected='${step.command}', actual='$command'")
        }
        require(step.response == null) {
            "Script step for write must not define a response"
        }
    }

    override suspend fun query(command: String): String {
        ensureConnected()
        val step = next()
        if (strict && step.command != command) {
            error("Script query mismatch: expected='${step.command}', actual='$command'")
        }
        return requireNotNull(step.response) {
            "Script step for query must define a response"
        }
    }

    private fun next(): ScriptedProtocolStep = steps.getOrNull(cursor++)
        ?: error("Protocol script exhausted")

    private fun ensureConnected() {
        check(connected) { "Scripted transport is not connected" }
    }
}
