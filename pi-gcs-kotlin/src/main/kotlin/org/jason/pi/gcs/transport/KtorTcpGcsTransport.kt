package org.jason.pi.gcs.transport

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PI GCS TCP/IP 的 Ktor 非阻塞实现。
 *
 * - 不依赖 PI GCS DLL；
 * - 同时支持 Windows、Linux 和 macOS；
 * - TCP_NODELAY 降低小步进闭环控制的命令延迟；
 * - 连接和读取失败时会完整释放 Socket 与 SelectorManager；
 * - 生命周期代次阻止“close 已发生，但迟到的 connect 又把连接安装回来”。
 */
class KtorTcpGcsTransport(
    private val host: String,
    private val port: Int = 50000,
    private val timeout: Duration = 5.seconds,
    private val lineEnding: String = "\n"
) : GcsTransport {

    private val openMutex = Mutex()
    private val resourceLock = Any()

    private var selectorManager: SelectorManager? = null
    private var socket: Socket? = null
    private var receiveChannel: ByteReadChannel? = null
    private var sendChannel: ByteWriteChannel? = null
    private var lifecycleGeneration: Long = 0L

    /**
     * Ktor 3 的 Socket 接口不再稳定暴露 isClosed，因此由传输层维护明确的生命周期标记。
     * 只有 Socket 和读写通道全部安装完成后，该值才会变为 true。
     */
    @Volatile
    private var connectionOpen: Boolean = false

    init {
        require(host.isNotBlank()) { "PI GCS host 不能为空" }
        require(port in 1..65535) { "PI GCS port 无效: $port" }
        require(timeout.isPositive()) { "PI GCS timeout 必须大于 0: $timeout" }
        require(lineEnding.isNotEmpty()) { "PI GCS lineEnding 不能为空" }
    }

    override val isOpen: Boolean
        get() = connectionOpen &&
            socket != null &&
            receiveChannel != null &&
            sendChannel != null

    override suspend fun open() {
        openMutex.withLock {
            if (isOpen) return@withLock

            // 清理上一次失败连接可能留下的部分资源。
            close()
            val openGeneration = synchronized(resourceLock) {
                lifecycleGeneration += 1L
                lifecycleGeneration
            }

            val manager = SelectorManager(Dispatchers.IO)
            var connectedSocket: Socket? = null

            try {
                connectedSocket = withTimeout(timeout.inWholeMilliseconds) {
                    aSocket(manager).tcp().connect(host, port) {
                        keepAlive = true
                        noDelay = true
                        socketTimeout = timeout.inWholeMilliseconds
                    }
                }

                val readChannel = connectedSocket.openReadChannel()
                val writeChannel = connectedSocket.openWriteChannel(autoFlush = true)

                val installed = synchronized(resourceLock) {
                    if (openGeneration != lifecycleGeneration) {
                        false
                    } else {
                        selectorManager = manager
                        socket = connectedSocket
                        receiveChannel = readChannel
                        sendChannel = writeChannel
                        connectionOpen = true
                        true
                    }
                }

                if (!installed) {
                    runCatching { connectedSocket.close() }
                    runCatching { manager.close() }
                    error("PI GCS TCP connection was closed while opening")
                }
            } catch (error: Throwable) {
                synchronized(resourceLock) {
                    if (openGeneration == lifecycleGeneration) {
                        connectionOpen = false
                    }
                }
                runCatching { connectedSocket?.close() }
                runCatching { manager.close() }
                throw error
            }
        }
    }

    override suspend fun writeLine(command: String) {
        require(command.isNotBlank()) { "PI GCS command 不能为空" }
        check(isOpen) { "PI GCS TCP 连接未打开" }
        val channel = sendChannel ?: error("PI GCS TCP 写通道未初始化")

        val normalized = command.trimEnd('\r', '\n')
        withTimeout(timeout.inWholeMilliseconds) {
            channel.writeStringUtf8(normalized)
            channel.writeStringUtf8(lineEnding)
        }
    }

    override suspend fun readLine(): String {
        check(isOpen) { "PI GCS TCP 连接未打开" }
        val channel = receiveChannel ?: error("PI GCS TCP 读通道未初始化")

        return withTimeout(timeout.inWholeMilliseconds) {
            channel.readUTF8Line()
                ?: run {
                    connectionOpen = false
                    error("PI GCS TCP 连接已关闭，未读取到完整响应")
                }
        }
    }

    override fun close() {
        val resources = synchronized(resourceLock) {
            lifecycleGeneration += 1L
            connectionOpen = false

            val currentSocket = socket
            val currentManager = selectorManager

            socket = null
            selectorManager = null
            receiveChannel = null
            sendChannel = null

            currentSocket to currentManager
        }

        runCatching { resources.first?.close() }
        runCatching { resources.second?.close() }
    }
}
