package org.jason.pi.gcs.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.transport.GcsTransport

/**
 * 串行化的 PI GCS 客户端。
 *
 * PI GCS 是严格的请求/响应协议。同一连接上不能让多个协程交叉写命令和读响应，
 * 所以这里用一个 Mutex 保护完整事务，而不是只保护单次 write/read。
 */
class GcsClient(
    private val transport: GcsTransport,
    private val checkErrorAfterCommand: Boolean = true
) : AutoCloseable {

    private val transactionMutex = Mutex()

    val isOpen: Boolean
        get() = transport.isOpen

    suspend fun connect() {
        transactionMutex.withLock {
            if (!transport.isOpen) {
                transport.open()
            }
        }
    }

    suspend fun execute(command: GcsCommand): String? {
        return when (command.kind) {
            GcsCommandKind.Query -> query(
                command = command.text,
                expectedResponseLines = command.expectedResponseLines
            )

            GcsCommandKind.Command -> {
                command(
                    command = command.text,
                    checkError = checkErrorAfterCommand && command.shouldCheckError
                )
                null
            }
        }
    }

    /**
     * 执行查询并完整读取预期响应。
     *
     * 多轴 POS?/ONT?/TMN?/TMX?/SVO? 通常每个轴返回一行，所有行会用 '\n'
     * 合并后交给统一解析器处理。
     */
    suspend fun query(
        command: String,
        expectedResponseLines: Int = 1
    ): String {
        require(command.isNotBlank()) { "GCS query 不能为空" }
        require(expectedResponseLines > 0) {
            "expectedResponseLines 必须大于 0，当前值: $expectedResponseLines"
        }

        return transactionMutex.withLock {
            transport.writeLine(command)
            transport.readLines(expectedResponseLines)
                .joinToString(separator = "\n") { line ->
                    line.trimEnd('\r', '\n')
                }
                .trim()
        }
    }

    suspend fun command(
        command: String,
        checkError: Boolean = checkErrorAfterCommand
    ) {
        require(command.isNotBlank()) { "GCS command 不能为空" }

        transactionMutex.withLock {
            transport.writeLine(command)

            if (checkError) {
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
