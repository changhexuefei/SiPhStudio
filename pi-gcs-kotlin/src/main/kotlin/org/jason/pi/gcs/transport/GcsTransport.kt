package org.jason.pi.gcs.transport

/**
 * PI GCS 底层通信接口。
 *
 * 传输层只负责可靠地发送和接收 GCS 文本，不解析具体命令语义。
 * 所有访问由上层 [org.jason.pi.gcs.core.GcsClient] 串行化。
 */
interface GcsTransport : AutoCloseable {

    /** 当前连接是否已经打开且可用。 */
    val isOpen: Boolean

    /** 打开连接。重复调用应保持幂等。 */
    suspend fun open()

    /** 写入一条 GCS 命令，由实现补充协议行结束符。 */
    suspend fun writeLine(command: String)

    /** 读取一行 GCS 响应，不包含行结束符。 */
    suspend fun readLine(): String

    /**
     * 读取固定数量的响应行。
     *
     * PI GCS 的多轴查询通常为每个轴返回一行，例如：
     *
     * X=1.0
     * Y=2.0
     * Z=3.0
     *
     * 默认实现连续调用 [readLine]，仿真传输、串口传输和 DLL 传输
     * 可以按需覆盖该方法实现更高效的批量读取。
     */
    suspend fun readLines(expectedLineCount: Int): List<String> {
        require(expectedLineCount > 0) {
            "expectedLineCount 必须大于 0，当前值: $expectedLineCount"
        }

        return buildList(expectedLineCount) {
            repeat(expectedLineCount) {
                add(readLine())
            }
        }
    }
}
