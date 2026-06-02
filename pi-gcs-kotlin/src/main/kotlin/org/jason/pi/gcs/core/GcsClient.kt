package org.jason.pi.gcs.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.transport.GcsTransport

/**
 * GCS 通信客户端。
 *
 * 负责：
 * - 串行化所有命令，避免多个协程同时写 socket
 * - query / command 区分
 * - command 后自动 ERR? 检查
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

    /**
     * 查询命令。
     *
     * 例如：
     * - *IDN?
     * - POS? X
     * - ONT? X
     */
    suspend fun query(command: String): String {
        return mutex.withLock {
            transport.writeLine(command)
            transport.readLine().trim()
        }
    }

    /**
     * 写入无返回值命令。
     *
     * 例如：
     * - MOV X 1.0
     * - SVO X 1
     * - STP
     */
    suspend fun command(command: String) {
        mutex.withLock {
            transport.writeLine(command)

            if (checkErrorAfterCommand) {
                transport.writeLine("ERR?")
                val errorText = transport.readLine().trim()
                val errorCode = errorText.toIntOrNull()
                    ?: throw PiGcsParseException(
                        response = errorText,
                        message = "无法解析 ERR? 返回值"
                    )

                if (errorCode != 0) {
                    throw PiGcsCommandException(
                        command = command,
                        errorCode = errorCode
                    )
                }
            }
        }
    }

    /**
     * 手动读取错误码。
     */
    suspend fun qERR(): Int {
        val response = query("ERR?")
        return response.toIntOrNull()
            ?: throw PiGcsParseException(
                response = response,
                message = "无法解析 ERR? 返回值"
            )
    }

    override fun close() {
        transport.close()
    }
}