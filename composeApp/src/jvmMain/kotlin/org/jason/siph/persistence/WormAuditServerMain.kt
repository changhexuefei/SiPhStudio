package org.jason.siph.persistence

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

object WormAuditServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = property("siph.audit.server.directory")
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".siphstudio", "worm-audit")
        val host = property("siph.audit.server.host") ?: "127.0.0.1"
        val port = property("siph.audit.server.port")?.toIntOrNull() ?: 8088
        require(port in 1..65535)
        val token = property("siph.audit.server.bearerToken")
        require(!token.isNullOrBlank()) {
            "Set -Dsiph.audit.server.bearerToken=<secret>; anonymous audit append is not allowed by the standalone server"
        }

        val archive = FileSystemWormAuditArchive(directory)
        val server = JvmWormAuditHttpServer(
            archive = archive,
            bindAddress = InetSocketAddress(host, port),
            bearerToken = token
        )
        val shutdown = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.close()
                shutdown.countDown()
            }
        )
        server.start()
        println("SiPhStudio WORM audit service listening on http://$host:$port")
        println("Audit directory: ${directory.toAbsolutePath()}")
        shutdown.await()
    }

    private fun property(name: String): String? = System.getProperty(name)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}
