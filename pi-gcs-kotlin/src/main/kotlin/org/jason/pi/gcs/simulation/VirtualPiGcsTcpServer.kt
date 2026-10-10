package org.jason.pi.gcs.simulation

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import org.jason.pi.gcs.hexapod.PiHexapodPose

/**
 * Loopback-only, READ-ONLY GCS test peer.
 * Never connect this endpoint to a physical PI controller or expose it on a LAN.
 * Commands changing servo, reference, position, Stop or recovery are rejected.
 *
 * The virtual pose belongs to an offline model. Its linear coordinates are
 * converted from application micrometres to GCS millimetres for POS? queries.
 */
class VirtualPiGcsTcpServer(
    private val model: VirtualPiHexapodState = VirtualPiHexapodState(),
    port: Int = 0
) : AutoCloseable {
    private val listener = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
    private val clients = mutableSetOf<java.net.Socket>()
    private val clientLock = Any()
    @Volatile private var running = true

    val localPort: Int get() = listener.localPort
    val host: String get() = listener.inetAddress.hostAddress

    private val acceptThread = thread(name = "virtual-pi-gcs-accept", isDaemon = true) {
        while (running) {
            val socket = try { listener.accept() } catch (_: SocketException) { break }
            synchronized(clientLock) { clients.add(socket) }
            thread(name = "virtual-pi-gcs-client", isDaemon = true) {
                try {
                    socket.use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val writer = client.getOutputStream().bufferedWriter()
                        while (running) {
                            val command = reader.readLine() ?: break
                            val response = respond(command.trim()) ?: break
                            writer.write(response)
                            writer.flush()
                        }
                    }
                } catch (_: Exception) {
                    // Isolated test peer; close/disconnect is expected in lifecycle tests.
                } finally {
                    synchronized(clientLock) { clients.remove(socket) }
                }
            }
        }
    }

    private fun respond(command: String): String? = when {
        command == "*IDN?" -> "VIRTUAL,PI-HEXAPOD,LOOPBACK-ONLY,NOT-HARDWARE\n"
        command == "ERR?" -> "0\n"
        command == "POS? X Y Z U V W" -> poseLines(model.pose)
        // No response to any unsupported command: fail closed at the protocol boundary.
        else -> null
    }

    private fun poseLines(p: PiHexapodPose): String = buildString {
        append("X=").append(p.xUm / 1000.0).append('\n')
        append("Y=").append(p.yUm / 1000.0).append('\n')
        append("Z=").append(p.zUm / 1000.0).append('\n')
        append("U=").append(p.uDeg).append('\n')
        append("V=").append(p.vDeg).append('\n')
        append("W=").append(p.wDeg).append('\n')
    }

    override fun close() {
        running = false
        listener.close()
        synchronized(clientLock) { clients.toList().forEach { it.close() } }
        acceptThread.join(2_000)
    }
}
