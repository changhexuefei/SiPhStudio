package org.jason.pi.gcs.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*


/**
 * PI GCS TCP/IP 通信的 Ktor 异步非阻塞实现。
 * * 优势：
 * 1. 零 C/C++ 依赖，jar 包直接跨平台通吃（信创、ARM Linux、Windows 等）。
 * 2. 纯协程挂起，高频寻光时绝不卡死主线程或 Compose UI 渲染。
 * 3. 关闭 Nagle 算法，提供极佳的硅光闭环低延迟。
 */
class KtorTcpGcsTransport(
    private val host: String,
    private val port: Int = 50000,
    private val timeout: Duration = 5.seconds,
    private val lineEnding: String = "\n"
) : GcsTransport {

    private var selectorManager: SelectorManager? = null
    private var socket: Socket? = null
    private var receiveChannel: ByteReadChannel? = null
    private var sendChannel: ByteWriteChannel? = null

    override val isOpen: Boolean
        get() = socket?.isClosed == false

    override suspend fun open() {
        if (isOpen) return

        withContext(Dispatchers.IO) {
            // 1. 创建 Ktor 异步 I/O 选择器
            val manager = SelectorManager(Dispatchers.IO)
            selectorManager = manager

            // 2. 异步连接控制器，配置高频硅光对准所需的低延迟策略
            val s = aSocket(manager).tcp().connect(host, port) {
                keepAlive = true
                noDelay = true // 💡 硅光核心优化：禁用 Nagle 算法，报文瞬间发出，不等缓冲区填满
                socketTimeout = timeout.inWholeMilliseconds
            }
            socket = s

            // 3. 打开异步非阻塞读写通道
            receiveChannel = s.openReadChannel()
            sendChannel = s.openWriteChannel(autoFlush = true) // 💡 自动 Flush 确保运动指令毫无滞留
        }
    }

    override suspend fun writeLine(command: String) {
        val channel = sendChannel ?: error("PI GCS TCP 连接未打开")

        // 组装标准 GCS 报文
        val trimmed = command.trim()
        val fullCommand = if (trimmed.endsWith(lineEnding)) trimmed else "$trimmed$lineEnding"

        // 🚀 Ktor 异步写入：若发送缓冲区满，则挂起协程，绝不阻塞当前线程
        channel.writeStringUtf8(fullCommand)
    }

    override suspend fun readLine(): String {
        val channel = receiveChannel ?: error("PI GCS TCP 连接未打开")

        // 🚀 Ktor 异步行读取：当控制器数据未到达时，协程挂起，释放 CPU 资源去画 Compose UI 波形
        return channel.readUTF8Line()
            ?: error("PI GCS TCP 连接已关闭，未读取到完整响应")
    }

    override fun close() {
        // 使用 runCatching 确保各组件安全释放，绝不向上层抛出收尾异常
        runCatching { socket?.close() }
        runCatching { selectorManager?.close() }

        socket = null
        selectorManager = null
        receiveChannel = null
        sendChannel = null
    }
}