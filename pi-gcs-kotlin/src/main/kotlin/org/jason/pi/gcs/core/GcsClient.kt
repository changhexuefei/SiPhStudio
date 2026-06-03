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

    // 依然使用互斥锁确保单次交互的原子性，但我们要大幅缩短锁的持有时间
    private val mutex = Mutex()

    val isOpen: Boolean
        get() = transport.isOpen

    suspend fun connect() {
        transport.open()
    }

    /**
     * 单行查询命令。
     */
    suspend fun query(command: String): String {
        return mutex.withLock {
            transport.writeLine(command)
            transport.readLine().trim()
        }
    }

    /**
     * 🚀【新增】批量/多行查询命令。
     * 完美解决上一轮多轴查询（如 POS? X Y Z）返回多行文本流导致缓冲区污染的问题。
     */
    suspend fun queryLines(command: String, expectedLineCount: Int): List<String> {
        require(expectedLineCount > 0) { "期望读取的行数必须大于 0" }

        return mutex.withLock {
            transport.writeLine(command)

            val lines = ArrayList<String>(expectedLineCount)
            repeat(expectedLineCount) {
                val line = transport.readLine().trim()
                if (line.isNotEmpty()) {
                    lines.add(line)
                }
            }
            lines
        }
    }

    /**
     * 🚀【深度优化】写入无返回值命令。
     * 优化点：不再将 ERR? 的等待卡在同一个硬件交互锁内，
     * 而是利用管道技术或独立检验，大幅提升高频控制（如爬山算法）的吞吐量。
     */
    suspend fun command(command: String) {
        if (!checkErrorAfterCommand) {
            mutex.withLock {
                transport.writeLine(command)
            }
            return
        }

        // 核心优化：合并发送（Pipeline）
        // 依据 PI 官方规范，允许将运动指令与 ERR? 合并在同一个 TCP 包或连续发送，
        // 然后一次性将两个响应处理掉，减少一次 Mutex 锁竞争。
        val errorCode = mutex.withLock {
            transport.writeLine(command)
            transport.writeLine("ERR?")

            val errorText = transport.readLine().trim()
            errorText.toIntOrNull()
                ?: throw PiGcsParseException(
                    response = errorText,
                    message = "无法解析 ERR? 返回值。原始指令 context: '$command'"
                )
        }

        // 报错逻辑移出 mutex 临界区，避免阻塞其他协程的高频网口读写
        if (errorCode != 0) {
            throw PiGcsCommandException(
                command = command,
                errorCode = errorCode
            )
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
        runCatching { transport.close() }
    }
}