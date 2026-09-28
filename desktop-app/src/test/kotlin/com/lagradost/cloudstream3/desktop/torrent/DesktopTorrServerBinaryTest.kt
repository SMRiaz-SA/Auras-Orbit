package com.lagradost.cloudstream3.desktop.torrent

import com.lagradost.common.storage.DesktopDataStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTorrServerBinaryTest {
    @Test
    fun cancelledStartupTerminatesOwnedProcessAndAllowsRestart() = runBlocking {
        val runtime = FakeRuntime()
        val server = DesktopTorrServerBinary(runtime)

        val firstStart = async { server.start() }
        val firstProcess = withTimeout(3_000L) { runtime.launched.receive() }
        firstStart.cancelAndJoin()

        assertFalse(firstProcess.isAlive)
        assertEquals(1, runtime.launchCount.get())

        runtime.healthCheck = { runtime.lastProcess?.isAlive == true }
        val restarted = async { server.start() }
        val secondProcess = withTimeout(3_000L) { runtime.launched.receive() }
        restarted.await()

        assertTrue(secondProcess.isAlive)
        assertEquals(2, runtime.launchCount.get())
        server.stop()
        assertFalse(secondProcess.isAlive)
    }

    @Test
    fun stopDuringStartupTerminatesChildAndFailsTheStartCall() = runBlocking {
        supervisorScope {
            val runtime = FakeRuntime()
            val server = DesktopTorrServerBinary(runtime)
            val starting = async { server.start() }
            val process = withTimeout(3_000L) { runtime.launched.receive() }

            server.stop()
            val failure = assertFailsWith<IllegalStateException> { starting.await() }

            assertTrue(failure.message.orEmpty().contains("stopped"))
            assertFalse(process.isAlive)
        }
    }

    @Test
    fun prematureProcessExitFailsStartupAndClearsOwnedProcess() = runBlocking {
        val runtime = FakeRuntime()
        runtime.healthCheck = {
            runtime.lastProcess?.exit()
            false
        }
        val server = DesktopTorrServerBinary(runtime)

        val failure = assertFailsWith<IllegalStateException> { server.start() }

        assertTrue(failure.message.orEmpty().contains("exited prematurely"))
        assertFalse(runtime.lastProcess!!.isAlive)
        server.stop()
        assertEquals(1, runtime.launchCount.get())
    }

    @Test
    fun startRejectsAnUnownedHealthyServiceAndStopLeavesItRunning() = runBlocking {
        val fixture = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fixture.createContext("/echo") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        fixture.start()
        val previousPort = DesktopDataStore.getKey<Int>(DesktopDataStore.PREF_P2P_PORT)
        val fixturePort = fixture.address.port
        DesktopDataStore.setKey(DesktopDataStore.PREF_P2P_PORT, fixturePort)

        try {
            val server = DesktopTorrServerBinary()
            val failure = assertFailsWith<IllegalStateException> { server.start() }
            assertTrue(failure.message.orEmpty().contains("already occupied"))

            server.stop()
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$fixturePort/echo")).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(200, response.statusCode())
        } finally {
            fixture.stop(0)
            if (previousPort == null) {
                DesktopDataStore.removeKey(DesktopDataStore.PREF_P2P_PORT)
            } else {
                DesktopDataStore.setKey(DesktopDataStore.PREF_P2P_PORT, previousPort)
            }
        }
    }

    private class FakeRuntime : TorrServerRuntime {
        val launchCount = AtomicInteger()
        val launched = Channel<FakeProcess>(Channel.UNLIMITED)

        @Volatile var lastProcess: FakeProcess? = null

        @Volatile var healthCheck: () -> Boolean = { false }

        override suspend fun resolveBinary(fallback: suspend () -> File): File =
            File(System.getProperty("java.io.tmpdir"), "fake-torrserver/TorrServer")

        override fun launch(command: List<String>, workingDirectory: File, logFile: File): Process {
            val process = FakeProcess()
            lastProcess = process
            launchCount.incrementAndGet()
            launched.trySend(process)
            return process
        }

        override fun isHealthy(baseUrl: String): Boolean = healthCheck()
    }

    private class FakeProcess : Process() {
        @Volatile private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = exitValue()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("Process is still running")
            return 23
        }
        override fun destroy() {
            alive = false
        }
        override fun destroyForcibly(): Process {
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive

        fun exit() {
            alive = false
        }
    }
}
