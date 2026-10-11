package org.jason.pi.gcs.simulation

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    port: Int = 0,
    private val fault: VirtualGcsFault = VirtualGcsFault.None,
    val diagnostics: VirtualGcsDiagnosticEvidence = VirtualGcsDiagnosticEvidence()
) : AutoCloseable {
    private val faultTriggered = AtomicBoolean(false)
    private val connectionIds = AtomicLong()
    private val listener = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
    private val clients = mutableSetOf<java.net.Socket>()
    private val clientLock = Any()
    private val workerThreads = mutableListOf<Thread>()
    @Volatile private var running = true

    val localPort: Int get() = listener.localPort
    val host: String get() = listener.inetAddress.hostAddress

    /** Inspection only; never exposes hardware control. */
    val activeClientCount: Int get() = synchronized(clientLock) { clients.size }
    val liveWorkerCount: Int get() = synchronized(clientLock) { workerThreads.count { it.isAlive } }

    private val acceptThread = thread(name = "virtual-pi-gcs-accept", isDaemon = true) {
        while (running) {
            val socket = try { listener.accept() } catch (_: SocketException) { break }
            val connectionId = connectionIds.incrementAndGet()
            synchronized(clientLock) { clients.add(socket) }
            diagnostics.record(connectionId, "CONNECTED")
            val worker = thread(start = false, name = "virtual-pi-gcs-client", isDaemon = true) {
                try {
                    socket.use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val writer = client.getOutputStream().bufferedWriter()
                        while (running) {
                            val command = reader.readLine() ?: break
                            val readOnlyCommand = command.trim()
                            val eligible = when (fault) {
                                VirtualGcsFault.MalformedPosition, VirtualGcsFault.TruncatedPosition -> readOnlyCommand == "POS? X Y Z U V W"
                                VirtualGcsFault.Disconnect, VirtualGcsFault.Timeout -> readOnlyCommand in setOf("*IDN?", "POS? X Y Z U V W", "ERR?")
                                VirtualGcsFault.None -> false
                            }
                            val injected = if (eligible && faultTriggered.compareAndSet(false, true)) fault else VirtualGcsFault.None
                            if (injected == VirtualGcsFault.Disconnect) {
                                diagnostics.record(connectionId, "QUERY", readOnlyCommand, "FAULT_DISCONNECT", injected.name)
                                break
                            }
                            if (injected == VirtualGcsFault.Timeout) {
                                diagnostics.record(connectionId, "QUERY", readOnlyCommand, "FAULT_TIMEOUT", injected.name)
                                // No reply. Wait for the timed-out client to retire its socket.
                                // A fresh session remains free to connect and query normally.
                                reader.readLine()
                                break
                            }
                            val response = when (injected) {
                                VirtualGcsFault.MalformedPosition -> if (readOnlyCommand == "POS? X Y Z U V W") "X=bad\\n" else respond(readOnlyCommand)
                                VirtualGcsFault.TruncatedPosition -> if (readOnlyCommand == "POS? X Y Z U V W") "X=0\\nY=0\\n" else respond(readOnlyCommand)
                                else -> respond(readOnlyCommand)
                            }
                            if (response == null) {
                                diagnostics.record(connectionId, "QUERY", "REJECTED", "UNSUPPORTED")
                                break
                            }
                            diagnostics.record(connectionId, "QUERY", readOnlyCommand, if (injected == VirtualGcsFault.None) "REPLIED" else "FAULT_REPLY", if (injected == VirtualGcsFault.None) null else injected.name)
                            writer.write(response)
                            writer.flush()
                            if (injected == VirtualGcsFault.TruncatedPosition) break
                        }
                    }
                } catch (_: Exception) {
                    // Isolated test peer; close/disconnect is expected in lifecycle tests.
                } finally {
                    synchronized(clientLock) { clients.remove(socket) }
                    diagnostics.record(connectionId, "DISCONNECTED")
                }
            }
            synchronized(clientLock) { workerThreads.add(worker) }
            worker.start()
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
        // Drain sockets accepted immediately before listener shutdown as well.
        synchronized(clientLock) { clients.toList().forEach { it.close() } }
        val workers = synchronized(clientLock) { workerThreads.toList() }
        workers.forEach { it.join(2_000) }
        check(workers.none { it.isAlive }) { "Virtual GCS client threads did not terminate" }
        check(!acceptThread.isAlive) { "Virtual GCS accept thread did not terminate" }
        diagnostics.record(0, "SERVER_CLOSED")
    }
}

/** One-shot deterministic failures for read-only local simulator tests. */
enum class VirtualGcsFault { None, Disconnect, Timeout, MalformedPosition, TruncatedPosition }
