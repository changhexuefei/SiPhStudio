package org.jason.pi.gcs.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.pi.gcs.transport.GcsTransport

/**
 * 串行化的 PI GCS 客户端。
 *
 * PI GCS 是严格的请求/响应协议。同一连接上不能让多个协程交叉写命令和读响应，
 * 所以这里用一个 Mutex 保护完整事务，而不是只保护单次 write/read。
 * 连接与断开也使用同一个事务锁，避免查询尚未读完时 TCP Socket 被关闭。
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

    /**
     * 等待正在执行的 GCS 请求/响应事务结束后再关闭传输层。
     *
     * 正常的协程生命周期必须调用这个方法，而不是直接调用 [close]。
     */
    suspend fun disconnect() {
        transactionMutex.withLock {
            if (transport.isOpen) {
                transport.close()
            }
        }
    }

    /**
     * 执行类型化命令。
     *
     * [forceErrorCheck] 主要用于控制器能力探测：查询响应读取完成后，仍在同一个
     * 串行事务中执行 ERR?，避免把“不支持的查询”误判为成功。
     */
    suspend fun execute(
        command: GcsCommand,
        forceErrorCheck: Boolean = false
    ): String? {
        return when (command.kind) {
            GcsCommandKind.Query -> query(
                command = command.text,
                expectedResponseLines = command.expectedResponseLines,
                checkError = forceErrorCheck
            )

            GcsCommandKind.Command -> {
                command(
                    command = command.text,
                    checkError = forceErrorCheck ||
                        (checkErrorAfterCommand && command.shouldCheckError)
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
        expectedResponseLines: Int = 1,
        checkError: Boolean = false
    ): String {
        require(command.isNotBlank()) { "GCS query 不能为空" }
        require(expectedResponseLines > 0) {
            "expectedResponseLines 必须大于 0，当前值: $expectedResponseLines"
        }

        return transactionMutex.withLock {
            check(transport.isOpen) { "PI GCS transport 未连接" }
            transport.writeLine(command)
            val response = transport.readLines(expectedResponseLines)
                .joinToString(separator = "\n") { line ->
                    line.trimEnd('\r', '\n')
                }
                .trim()

            if (checkError) {
                checkControllerError(command)
            }

            response
        }
    }

    suspend fun command(
        command: String,
        checkError: Boolean = checkErrorAfterCommand
    ) {
        require(command.isNotBlank()) { "GCS command 不能为空" }

        transactionMutex.withLock {
            check(transport.isOpen) { "PI GCS transport 未连接" }
            transport.writeLine(command)

            if (checkError) {
                checkControllerError(command)
            }
        }
    }

    suspend fun qERR(): Int {
        val response = query("ERR?")
        return GcsResponseParser.parseErrorCode(response)
    }

    /**
     * AutoCloseable 的同步兜底入口。
     *
     * 为避免同步 close 与正在挂起的事务发生竞争，这里只在当前没有活动事务时关闭。
     * 协程代码应始终调用 [disconnect]，它会等待活动事务完成。
     */
    override fun close() {
        check(transactionMutex.tryLock()) {
            "PI GCS transaction is active; call suspend disconnect() instead of close()"
        }

        try {
            transport.close()
        } finally {
            transactionMutex.unlock()
        }
    }

    /** Must only be called while [transactionMutex] is held. */
    private suspend fun checkControllerError(command: String) {
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
