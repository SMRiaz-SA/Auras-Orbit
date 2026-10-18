package com.lagradost.player.impl

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VlcRcSessionTest {
    @Test
    fun readsPlaybackMetricsAndSendsControlCommands() {
        FakeVlcRcServer().use { server ->
            val session = VlcRcSession(server.port, socketTimeoutMillis = 500)
            try {
                assertTrue(session.connect(timeoutMillis = 1_000))
                assertEquals(
                    VlcPlaybackSnapshot(positionMs = 41_000L, durationMs = 120_000L),
                    session.readPlaybackSnapshot(),
                )
                assertTrue(session.pause())
                assertTrue(session.resume())
                assertTrue(session.seek(12_999L))
                assertTrue(session.seek(-1L))
                // Control notifications have no per-command acknowledgement. They arrive later,
                // mixed with the next query's output, and must not be mistaken for query values.
                assertEquals(
                    VlcPlaybackSnapshot(positionMs = 41_000L, durationMs = 120_000L),
                    session.readPlaybackSnapshot(),
                )

                assertEquals(
                    listOf(
                        "get_time",
                        "get_length",
                        "pause",
                        "pause",
                        "seek 12",
                        "seek 0",
                        "get_time",
                        "get_length",
                    ),
                    server.commands,
                )
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun failedHandshakeCanRetryAndClosedSessionDoesNotReconnect() {
        val port = allocateVlcRcPort()
        val session = VlcRcSession(port, socketTimeoutMillis = 100)
        try {
            assertFalse(session.connect(timeoutMillis = 150))
            FakeVlcRcServer(port).use {
                assertTrue(session.connect(timeoutMillis = 1_000))
                session.close()
                assertFalse(session.connect(timeoutMillis = 1_000))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun allocatedPortIsBindableOnIpv4Loopback() {
        val port = allocateVlcRcPort()
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            assertEquals(port, socket.localPort)
        }
    }

    private class FakeVlcRcServer(port: Int? = null) : Closeable {
        private val server = ServerSocket().apply {
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port ?: 0))
        }
        private val commandsReceived = CopyOnWriteArrayList<String>()
        private var activeSocket: Socket? = null
        private val worker = thread(isDaemon = true, name = "fake-vlc-rc") {
            server.accept().use { socket ->
                activeSocket = socket
                socket.soTimeout = 2_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                val pendingStatuses = mutableListOf<String>()

                while (true) {
                    val command = reader.readLine() ?: break
                    commandsReceived.add(command)
                    when (command) {
                        "get_time" -> {
                            pendingStatuses.forEach { writer.write("$it\r\n") }
                            pendingStatuses.clear()
                            writer.write("status change: ( time state: 0 ): playing\r\n41\r\n")
                            writer.flush()
                        }
                        "get_length" -> {
                            writer.write("status change: ( length state: 0 ): playing\r\n120\r\n")
                            writer.flush()
                        }
                        "pause" -> pendingStatuses.add("status change: ( pause state: 4 ): paused")
                        "seek 12", "seek 0" -> pendingStatuses.add("status change: ( seek state: 3 ): playing")
                        else -> {
                            writer.write("Unknown command\r\n")
                            writer.flush()
                        }
                    }
                }
            }
        }

        val port: Int get() = server.localPort
        val commands: List<String> get() = commandsReceived.toList()

        override fun close() {
            try {
                activeSocket?.close()
            } catch (_: Exception) {
            }
            try {
                server.close()
            } catch (_: Exception) {
            }
            worker.join(1_000)
        }
    }
}
