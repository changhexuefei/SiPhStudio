package org.jason.pi.gcs.simulation

import kotlinx.coroutines.runBlocking
import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.hexapod.PiAxis
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualPiGcsTcpServerTest {
    @Test
    fun readOnlySixAxisQueriesUseRealLoopbackTcpAndVirtualUnits() = runBlocking {
        val model = VirtualPiHexapodState()
        model.connect()
        model.moveTo(PiHexapodPose(1500.0, -2000.0, 500.0, 1.5, -2.0, 0.25))
        model.tick(3.0)
        VirtualPiGcsTcpServer(model).use { server ->
            val device = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort),
                transactionTimeoutMs = 2_000
            ))
            try {
                device.connect()
                assertTrue(device.qIDN().contains("NOT-HARDWARE"))
                val pos = device.qPOS(PiAxis.HEXAPOD_AXES)
                assertEquals(1.5, pos[PiAxis.X])
                assertEquals(-2.0, pos[PiAxis.Y])
                assertEquals(0.5, pos[PiAxis.Z])
                assertEquals(1.5, pos[PiAxis.U])
                assertEquals(-2.0, pos[PiAxis.V])
                assertEquals(0.25, pos[PiAxis.W])
            } finally {
                device.close()
            }
        }
    }

    @Test
    fun rejectsWriteCapableCommandsWithoutUpdatingVirtualPose() = runBlocking {
        val model = VirtualPiHexapodState()
        model.connect()
        val before = model.pose
        VirtualPiGcsTcpServer(model).use { server ->
            val device = GcsDevice(GcsClient(
                KtorTcpGcsTransport(server.host, server.localPort),
                transactionTimeoutMs = 800
            ))
            try {
                device.connect()
                val failure = runCatching { device.execute(
                    org.jason.pi.gcs.core.GcsCommand.rawCommand("MOV X 1")
                ) }.exceptionOrNull()
                assertTrue(failure != null, "Write-capable commands must fail closed")
                assertEquals(before, model.pose)
            } finally {
                device.close()
            }
        }
    }
}
