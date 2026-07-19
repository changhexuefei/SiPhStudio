package org.jason.pi.gcs.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.transport.GcsTransport

/**
 * Serialized PI GCS client.
 *
 * A PI controller uses a request/response stream. Every write and all response
 * lines that belong to that write therefore have to stay inside one critical
 * section; otherwise a second coroutine can consume the first command's reply.
 */
class GcsClient(
    private val transport: GcsTransport,
    private val checkErrorAfterCommand: Boolean = true
) : AutoCloseable {

    private val mutex = Mutex()

    val isOpen: Boolean
        get() = transport.isOpen

    suspend fun connect() {
        mutex.withLock {
            if (!transport.isOpen) {
                transport.open()
            }
        }
    }

    suspend fun execute(command: GcsCommand): String? {
        return when (command.kind) {
            GcsCommandKind.Query -> query(command.text)
            GcsCommandKind.Command -> {
                command(command.text)
                null
            }
        }
    }

    /**
     * Executes a normal one-line query.
     */
    suspend fun query(command: String): String {
        return query(
            command = command,
            maxResponseLines = 1,
            isResponseComplete = { true }
        )
    }

    /**
     * Executes a query whose response may contain multiple lines.
     *
     * PI multi-axis queries commonly return one axis/value pair per line. Some
     * controller/firmware combinations return several pairs in one line. The
     * completion predicate supports both forms: it is evaluated after every
     * received line and reading stops as soon as the response is complete.
     *
     * The whole operation is serialized so no other command can steal one of
     * the response lines.
     */
    suspend fun query(
        command: String,
        maxResponseLines: Int,
        isResponseComplete: (String) -> Boolean
    ): String {
        require(command.isNotBlank()) { "PI GCS query must not be blank" }
        require(maxResponseLines > 0) { "maxResponseLines must be > 0" }

        return mutex.withLock {
            ensureOpen()
            transport.writeLine(command)

            val response = StringBuilder()

            repeat(maxResponseLines) {
                val line = transport.readLine().trim()
                if (response.isNotEmpty()) {
                    response.append('\n')
                }
                response.append(line)

                val current = response.toString()
                if (isResponseComplete(current)) {
                    return@withLock current
                }
            }

            error(
                "Incomplete PI GCS response: command=$command, " +
                    "maxResponseLines=$maxResponseLines, response=$response"
            )
        }
    }

    suspend fun command(command: String) {
        require(command.isNotBlank()) { "PI GCS command must not be blank" }

        mutex.withLock {
            ensureOpen()
            transport.writeLine(command)

            if (checkErrorAfterCommand) {
                transport.writeLine("ERR?")
                val errorText = transport.readLine().trim()
                val errorCode = GcsResponseParser.parseErrorCode(errorText)

                if (errorCode != 0) {
                    throw PiGcsCommandException(
                        command = command,
                        errorCode = errorCode
                    )
                }
            }
        }
    }

    suspend fun qERR(): Int {
        val response = query("ERR?")
        return GcsResponseParser.parseErrorCode(response)
    }

    override fun close() {
        transport.close()
    }

    private fun ensureOpen() {
        check(transport.isOpen) {
            "PI GCS transport is not open"
        }
    }
}
