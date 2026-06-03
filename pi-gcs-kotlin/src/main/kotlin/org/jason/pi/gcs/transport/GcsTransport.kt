package org.jason.pi.gcs.transport


/**
 * PI GCS 底层通信接口。
 * 完全基于 Kotlin 协程设计，支持非阻塞的硬件 I/O 吞吐。
 */
interface GcsTransport : AutoCloseable {

    /**
     * 当前连接是否已经打开且可用。
     */
    val isOpen: Boolean

    /**
     * 打开连接（非阻塞挂起）。
     */
    suspend fun open()

    /**
     * 写入一行 GCS 命令（自动补全换行符，不阻塞线程）。
     */
    suspend fun writeLine(command: String)

    /**
     * 读取一行响应（挂起等待，直到网卡接收到换行符）。
     */
    suspend fun readLine(): String
}