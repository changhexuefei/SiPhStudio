package org.jason.pi.gcs.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PI GCS TCP/IP 通信实现。
 *
 * 常见 PI 控制器 TCP 端口通常为 50000，
 * 但实际端口需要以你的控制器设置为准。
 */
class TcpGcsTransport(
    private val host: String,
    private val port: Int = 50000,
    private val timeout: Duration = 5.seconds,
    private val charset: Charset = Charsets.US_ASCII,
    private val lineEnding: String = "\n"
) : GcsTransport {

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override val isOpen: Boolean
        get() {
            val s = socket ?: return false
            return s.isConnected && !s.isClosed
        }

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            if (isOpen) return@withContext

            val s = Socket()
            val timeoutMs = timeout.inWholeMilliseconds.toInt()

            s.soTimeout = timeoutMs
            s.connect(
                InetSocketAddress(host, port),
                timeoutMs
            )

            socket = s
            reader = BufferedReader(
                InputStreamReader(s.getInputStream(), charset)
            )
            writer = BufferedWriter(
                OutputStreamWriter(s.getOutputStream(), charset)
            )
        }
    }

    override suspend fun writeLine(command: String) {
        withContext(Dispatchers.IO) {
            val w = writer ?: error("PI GCS TCP 连接未打开")

            w.write(command.trim())
            w.write(lineEnding)
            w.flush()
        }
    }

    override suspend fun readLine(): String {
        return withContext(Dispatchers.IO) {
            val r = reader ?: error("PI GCS TCP 连接未打开")

            r.readLine()
                ?: error("PI GCS TCP 连接已关闭，未读取到响应")
        }
    }

    override fun close() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }

        reader = null
        writer = null
        socket = null
    }
}