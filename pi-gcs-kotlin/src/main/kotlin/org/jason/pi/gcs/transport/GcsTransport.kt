package org.jason.pi.gcs.transport

/**
 * PI GCS 底层通信接口。
 *
 * 这一层只负责：
 * - 打开连接
 * - 写入一行命令
 * - 读取一行响应
 * - 关闭连接
 *
 * 不关心具体 GCS 命令含义。
 */
interface GcsTransport : AutoCloseable {

    /**
     * 当前连接是否已经打开。
     */
    val isOpen: Boolean

    /**
     * 打开连接。
     */
    suspend fun open()

    /**
     * 写入一行 GCS 命令。
     *
     * 注意：
     * 这里不需要调用者手动添加换行符。
     */
    suspend fun writeLine(command: String)

    /**
     * 读取一行响应。
     */
    suspend fun readLine(): String
}