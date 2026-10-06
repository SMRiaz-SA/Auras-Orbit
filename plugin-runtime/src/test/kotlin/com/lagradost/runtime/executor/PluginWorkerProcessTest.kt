package com.lagradost.runtime.executor

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun fixtureCommand(mode: String, vararg extraArgs: String): List<String> {
    val executable = File(System.getProperty("java.home"), "bin/java${if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""}")
    val classPath = System.getProperty("java.class.path")
    return listOf(executable.absolutePath, "-cp", classPath, PluginWorkerFixture::class.java.name, mode) + extraArgs
}

class PluginWorkerProcessTest {
    @Test
    fun eightInstalledWorkersShareFourResidentSlotsAndRestartAfterEviction() = runBlocking {
        val workers = (1..8).map { index ->
            RestartablePluginWorker(command = { fixtureCommand("framed-echo") }, workerKey = "resident-$index")
        }
        try {
            repeat(2) { round ->
                workers.forEachIndexed { index, worker ->
                    val payload = "$round:$index".toByteArray(UTF_8)
                    assertContentEquals(payload, worker.request(payload, 5_000))
                    assertTrue(workers.count { it.isAlive } <= 4)
                }
            }
            assertTrue(workers.all { it.workerStarts == 2L })
        } finally {
            workers.forEach { it.close() }
        }
    }

    @Test
    fun admissionIsIncludedInTheRequestDeadline() = runBlocking {
        val sessions = (1..4).map { index ->
            PluginWorkerSession.launch(fixtureCommand("framed-echo"), workerKey = "reserved-$index")
        }
        val pending = RestartablePluginWorker(command = { fixtureCommand("framed-echo") }, workerKey = "pending")
        try {
            val started = System.nanoTime()
            assertFailsWith<PluginWorkerTimeoutException> { pending.request(byteArrayOf(1), 200) }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
            assertEquals(0, pending.workerStarts)
        } finally {
            pending.close()
            sessions.forEach { it.close() }
        }
    }

    @Test
    fun exchangesBoundedInputAndOutputWithoutShellParsing() = runBlocking {
        val payload = "spaces & symbols; ${'$'}HOME \"quoted\"".toByteArray(UTF_8)
        val result = PluginWorkerProcess.execute(
            command = fixtureCommand("echo"),
            request = payload,
            timeoutMs = 5_000,
        )

        assertEquals(0, result.exitCode)
        assertContentEquals(payload, result.stdout)
        assertEquals("fixture stderr", result.stderr.toString(UTF_8))
    }

    @Test
    fun killsAChildThatIgnoresInterruptsWhenItsDeadlineExpires() = runBlocking {
        val started = System.nanoTime()
        val failure = assertFailsWith<PluginWorkerTimeoutException> {
            PluginWorkerProcess.execute(
                command = fixtureCommand("spin"),
                timeoutMs = 150,
            )
        }

        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        val worker = ProcessHandle.of(failure.processId).orElse(null)
        assertTrue(worker == null || !worker.isAlive)
    }

    @Test
    fun killsGrandchildrenSpawnedByANonCooperativeWorker() = runBlocking {
        val pidFile = Files.createTempFile("orbit worker process tree ", ".pids")
        try {
            val started = System.nanoTime()
            assertFailsWith<PluginWorkerTimeoutException> {
                PluginWorkerProcess.execute(
                    command = fixtureCommand("spawn-tree", pidFile.toString()),
                    timeoutMs = 8_000,
                )
            }

            assertTrue((System.nanoTime() - started) / 1_000_000 < 12_000)
            val pids = Files.readString(pidFile).trim().split(',').mapNotNull(String::toLongOrNull)
            assertEquals(2, pids.size, "fixture must report its child and grandchild process IDs")
            pids.forEach { pid ->
                val process = ProcessHandle.of(pid).orElse(null)
                assertTrue(process == null || !process.isAlive, "worker descendant $pid leaked past the deadline")
            }
        } finally {
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun rejectsExcessiveCapturedOutput() = runBlocking {
        val failure = assertFailsWith<PluginWorkerOutputLimitException> {
            PluginWorkerProcess.execute(
                command = fixtureCommand("output"),
                timeoutMs = 5_000,
                maxStdoutBytes = 64,
            )
        }

        assertEquals("stdout", failure.streamName)
        assertEquals(64, failure.limitBytes)
    }

    @Test
    fun terminatesAWorkerAsSoonAsItExceedsItsOutputBudget() = runBlocking {
        val started = System.nanoTime()
        val failure = assertFailsWith<PluginWorkerOutputLimitException> {
            PluginWorkerProcess.execute(
                command = fixtureCommand("flood"),
                request = ByteArray(1024 * 1024),
                timeoutMs = 5_000,
                maxStdoutBytes = 64,
            )
        }

        assertEquals("stdout", failure.streamName)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        val worker = ProcessHandle.of(failure.processId).orElse(null)
        assertTrue(worker == null || !worker.isAlive)
    }

    @Test
    fun workerSessionExchangesMultipleBoundedFramesUntilClosed() = runBlocking {
        val session = PluginWorkerSession.launch(fixtureCommand("framed-echo"))
        val processId = session.processId
        try {
            val first = session.request("first request".toByteArray(UTF_8), timeoutMs = 5_000)
            val second = session.request("second request".toByteArray(UTF_8), timeoutMs = 5_000)

            assertEquals("first request", first.toString(UTF_8))
            assertEquals("second request", second.toString(UTF_8))
            assertEquals(processId, session.processId)
            assertTrue(session.isAlive)
        } finally {
            session.close()
        }
        assertTrue(ProcessHandle.of(processId).map { !it.isAlive }.orElse(true))
    }

    @Test
    fun onePluginCannotOccupyTheSlotReservedForOtherWorkers() = runBlocking {
        val sessions = (1..3).map {
            PluginWorkerSession.launch(fixtureCommand("framed-echo"), workerKey = "busy-plugin")
        }
        try {
            val response = withTimeout(15_000) {
                PluginWorkerProcess.execute(
                    command = fixtureCommand("echo"),
                    request = "other plugin".toByteArray(UTF_8),
                    timeoutMs = 10_000,
                    workerKey = "independent-plugin",
                )
            }
            assertEquals("other plugin", response.stdout.toString(UTF_8))
        } finally {
            sessions.forEach(PluginWorkerSession::close)
        }
    }

    @Test
    fun aPluginCannotExceedItsWorkerLimit() = runBlocking {
        val sessions = (1..3).map {
            PluginWorkerSession.launch(fixtureCommand("framed-echo"), workerKey = "limited-plugin")
        }
        val pending = async {
            PluginWorkerSession.launch(fixtureCommand("framed-echo"), workerKey = "limited-plugin")
        }
        try {
            delay(150)
            assertTrue(!pending.isCompleted, "fourth same-plugin worker should wait at admission")
            sessions.first().close()
            val admitted = withTimeout(3_000) { pending.await() }
            admitted.close()
        } finally {
            sessions.forEach(PluginWorkerSession::close)
            if (pending.isCompleted && !pending.isCancelled) pending.await().close()
            pending.cancel()
        }
    }

    @Test
    fun sessionDeadlineKillsTheWorkerAndItsProcessTree() = runBlocking {
        val session = PluginWorkerSession.launch(fixtureCommand("framed-spin"))
        val processId = session.processId
        try {
            val started = System.nanoTime()
            assertFailsWith<PluginWorkerTimeoutException> {
                session.request(byteArrayOf(1), timeoutMs = 150)
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
            assertTrue(ProcessHandle.of(processId).map { !it.isAlive }.orElse(true))
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionRejectsAnOversizedFrameBeforeAllocatingItsPayload() = runBlocking {
        val session = PluginWorkerSession.launch(fixtureCommand("framed-oversize"), maxResponseBytes = 64)
        try {
            val failure = assertFailsWith<PluginWorkerOutputLimitException> {
                session.request(byteArrayOf(1), timeoutMs = 5_000)
            }
            assertEquals("stdout", failure.streamName)
            assertEquals(64, failure.limitBytes)
            assertTrue(!session.isAlive)
        } finally {
            session.close()
        }
    }

    @Test
    fun restartableWorkerReplacesAProcessThatTimesOut() = runBlocking {
        val commandCount = AtomicInteger()
        val worker = RestartablePluginWorker(
            command = {
                if (commandCount.getAndIncrement() == 0) fixtureCommand("framed-spin") else fixtureCommand("framed-echo")
            },
        )
        try {
            assertFailsWith<PluginWorkerTimeoutException> {
                worker.request(byteArrayOf(1), timeoutMs = 150)
            }
            val killedProcessId = worker.lastStartedProcessId

            val response = worker.request("recovered".toByteArray(UTF_8), timeoutMs = 5_000)

            assertEquals("recovered", response.toString(UTF_8))
            assertEquals(2, worker.workerStarts)
            assertEquals(2, commandCount.get())
            assertTrue(killedProcessId == null || ProcessHandle.of(killedProcessId).map { !it.isAlive }.orElse(true))
        } finally {
            worker.close()
        }
    }
}

/** Child process used to prove the supervisor's protocol, output cap, and hard deadline. */
object PluginWorkerFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "echo" -> {
                System.`in`.copyTo(System.out)
                System.err.print("fixture stderr")
            }
            "spin" -> {
                var counter = 0L
                while (true) {
                    counter++
                    if (counter == Long.MIN_VALUE) System.err.println(counter)
                }
            }
            "spawn-tree" -> {
                val pidFile = File(args[1])
                val childCommand = fixtureCommand("spawn-child", pidFile.absolutePath)
                val childBuilder = ProcessBuilder(childCommand)
                childBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                childBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
                childBuilder.start()
                val deadline = System.nanoTime() + 7_000_000_000L
                while ((!pidFile.isFile || pidFile.length() == 0L) && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                while (true) Thread.sleep(1000)
            }
            "spawn-child" -> {
                val pidFile = File(args[1])
                val grandchildCommand = fixtureCommand("spin")
                val grandchildBuilder = ProcessBuilder(grandchildCommand)
                grandchildBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                grandchildBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)
                val grandchild = grandchildBuilder.start()
                Files.writeString(pidFile.toPath(), "${ProcessHandle.current().pid()},${grandchild.pid()}")
                while (true) Thread.sleep(1000)
            }
            "flood" -> {
                val output = ByteArray(64 * 1024) { 0x5A }
                while (true) System.out.write(output)
            }
            "output" -> System.out.write(ByteArray(1024) { 0x5A })
            "framed-echo" -> {
                val input = java.io.DataInputStream(System.`in`)
                val output = java.io.DataOutputStream(System.out)
                while (true) {
                    val size = try {
                        input.readInt()
                    } catch (_: java.io.EOFException) {
                        return
                    }
                    val request = ByteArray(size)
                    input.readFully(request)
                    output.writeInt(request.size)
                    output.write(request)
                    output.flush()
                }
            }
            "framed-spin" -> {
                val input = java.io.DataInputStream(System.`in`)
                val size = input.readInt()
                input.readNBytes(size)
                var counter = 0L
                while (true) {
                    counter++
                    if (counter == Long.MIN_VALUE) System.err.println(counter)
                }
            }
            "framed-oversize" -> {
                val input = java.io.DataInputStream(System.`in`)
                val size = input.readInt()
                input.readNBytes(size)
                System.out.write(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(1024).array())
                System.out.flush()
                while (true) Thread.sleep(1000)
            }
            else -> error("Unknown fixture mode")
        }
    }
}
