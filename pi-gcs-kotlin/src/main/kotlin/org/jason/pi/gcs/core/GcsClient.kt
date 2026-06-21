package org.jason.pi.gcs.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.transport.GcsTransport

/**
 * Serialized PI GCS client.
 *
 * Responsibilities:
 * - serialize all transport access
 * - separate command and query traffic
 * - optionally run ERR? after every command, matching PIPython's errcheck style
 */
class GcsClient(
    private val transport: GcsTransport,
    private val checkErrorAfterCommand: Boolean = true
) : AutoCloseable {

    private val mutex = Mutex()

    val isOpen: Boolean
        get() = transport.isOpen

    suspend fun connect() {
        transport.open()
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

    suspend fun query(command: String): String {
        return mutex.withLock {
            transport.writeLine(command)
            transport.readLine().trim()
        }
    }

    suspend fun command(command: String) {
        mutex.withLock {
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
}
