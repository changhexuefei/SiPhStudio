package org.jason.pi.gcs.simulation

import java.util.concurrent.CountDownLatch

/**
 * Local-only, read-only simulator entry point; never connects to PI equipment.
 * Run with Gradle :pi-gcs-kotlin:runVirtualPiGcs.
 */
object VirtualPiGcsLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: VirtualPiGcsLauncher [port]" }
        val requestedPort = if (args.isEmpty()) 50001 else
            requireNotNull(args[0].toIntOrNull()) { "Port must be numeric" }
        require(requestedPort in 1..65535) { "Port must be 1..65535" }
        val server = VirtualPiGcsTcpServer(port = requestedPort)
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        println("VIRTUAL PI GCS / NOT HARDWARE / READ-ONLY")
        println("Listening on ${server.host}:${server.localPort}")
        println("Only *IDN?, ERR?, POS? X Y Z U V W are supported.")
        println("Press Ctrl+C to stop; no physical device is contacted.")
        CountDownLatch(1).await()
    }
}
