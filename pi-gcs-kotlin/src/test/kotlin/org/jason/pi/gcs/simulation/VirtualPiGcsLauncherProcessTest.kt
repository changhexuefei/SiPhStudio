package org.jason.pi.gcs.simulation

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Forks the actual main() launcher in a separate JVM, unlike the in-process
 * VirtualPiGcsTcpServerTest. No physical controller or network interface is used.
 */
class VirtualPiGcsLauncherProcessTest {
    @Test
    fun standaloneLauncherServesRepeatedReadOnlySessionsAndExitsCleanly() = runBlocking {
        val classpath = requireNotNull(System.getProperty("virtualPiLauncherClasspath")) {
            "Test runtime classpath must be configured by Gradle"
        }
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val executable = File(
            System.getProperty("java.home"),
            "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        )
        val output = Files.createTempFile("virtual-pi-launcher-", ".log").toFile()
        val process = ProcessBuilder(
            executable.absolutePath, "-cp", classpath,
            "org.jason.pi.gcs.simulation.VirtualPiGcsLauncher", port.toString()
        ).redirectErrorStream(true).redirectOutput(output).start()
        val address = InetAddress.getLoopbackAddress().hostAddress
        try {
            withTimeout(12_000) {
                while (true) {
                    check(process.isAlive) { "Launcher exited: " + output.readText() }
                    val connected = runCatching {
                        val client = GcsClient(
                            KtorTcpGcsTransport(address, port), transactionTimeoutMs = 1_000
                        )
                        val device = GcsDevice(client)
                        try {
                            device.connect()
                            assertTrue(device.qIDN().contains("NOT-HARDWARE"))
                            val positions = device.qPOS(PiAxis.HEXAPOD_AXES)
                            assertEquals(6, positions.size)
                            assertTrue(positions.values.all { it == 0.0 })
                        } finally {
                            device.close()
                        }
                    }.isSuccess
                    if (connected) break
                    delay(100)
                }
            }
            // The same independent launcher process must accept a second client.
            val device = GcsDevice(GcsClient(
                KtorTcpGcsTransport(address, port), transactionTimeoutMs = 2_000
            ))
            try {
                device.connect()
                assertTrue(device.qIDN().startsWith("VIRTUAL,"))
                assertEquals(0, device.qERR())
            } finally {
                device.close()
            }
            assertTrue(output.readText().contains("NOT HARDWARE"))
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Launcher must terminate")
            }
            assertFalse(process.isAlive, "Forked simulator must be gone")
            output.delete()
        }
    }
}
