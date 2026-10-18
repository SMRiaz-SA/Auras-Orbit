package com.lagradost.player.impl

import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlcPlayerLifecycleTest {
    @Test
    fun stopDuringLaunchPreventsLateProcessFromBecomingActive() = runBlocking {
        val launcherEntered = CountDownLatch(1)
        val allowLauncherToReturn = CountDownLatch(1)
        val process = FakeProcess()
        val player = newPlayer { _ ->
            launcherEntered.countDown()
            check(allowLauncherToReturn.await(3, TimeUnit.SECONDS))
            process
        }

        try {
            val playResult = async(Dispatchers.IO) { player.play(link(), "Test", emptyList(), 0L) }
            assertTrue(launcherEntered.await(3, TimeUnit.SECONDS), "VLC launch should reach the process seam")

            player.stop()
            allowLauncherToReturn.countDown()

            assertTrue(withTimeout(3_000) { playResult.await() }.isFailure)
            assertTrue(process.destroyed, "the process returned after stop should be destroyed")
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isLoading)
            assertNull(player.state.value.currentUrl)
        } finally {
            allowLauncherToReturn.countDown()
            player.destroy()
        }
    }

    @Test
    fun stopInvalidatesAPlayQueuedBehindAnInFlightLaunch() = runBlocking {
        val launcherEntered = CountDownLatch(1)
        val allowLauncherToReturn = CountDownLatch(1)
        val firstProcess = FakeProcess()
        val launches = AtomicInteger()
        val player = newPlayer { _ ->
            check(launches.incrementAndGet() == 1) { "queued play must not launch after stop" }
            launcherEntered.countDown()
            check(allowLauncherToReturn.await(3, TimeUnit.SECONDS))
            firstProcess
        }

        try {
            val firstPlay = async(Dispatchers.IO) { player.play(link("/first.mp4"), "First", emptyList(), 0L) }
            assertTrue(launcherEntered.await(3, TimeUnit.SECONDS), "first VLC launch should block at the process seam")

            val queuedPlay = async(start = CoroutineStart.UNDISPATCHED) {
                player.play(link("/queued.mp4"), "Queued", emptyList(), 0L)
            }
            player.stop()
            allowLauncherToReturn.countDown()

            assertTrue(withTimeout(3_000) { firstPlay.await() }.isFailure)
            assertTrue(withTimeout(3_000) { queuedPlay.await() }.isFailure)
            assertEquals(1, launches.get())
            assertTrue(firstProcess.destroyed)
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isLoading)
            assertNull(player.state.value.currentUrl)
        } finally {
            allowLauncherToReturn.countDown()
            player.destroy()
        }
    }

    @Test
    fun failedReplacementDoesNotLeaveTheOldProcessAsPlaying() = runBlocking {
        val oldProcess = FakeProcess()
        val launches = AtomicInteger()
        val player = newPlayer { _ ->
            if (launches.incrementAndGet() == 1) {
                oldProcess
            } else {
                throw IllegalStateException("replacement failed")
            }
        }

        try {
            assertTrue(player.play(link("/first.mp4"), "First", emptyList(), 0L).isSuccess)
            assertTrue(player.state.value.isPlaying)

            val replacement = player.play(link("/replacement.mp4"), "Replacement", emptyList(), 0L)

            assertTrue(replacement.isFailure)
            assertTrue(oldProcess.destroyed)
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isPaused)
            assertFalse(player.state.value.isFinished)
            assertFalse(player.state.value.isLoading)
            assertNull(player.state.value.currentUrl)
            assertEquals("replacement failed", player.state.value.error)
        } finally {
            player.destroy()
        }
    }

    @Test
    fun naturalProcessExitMarksPlaybackFinished() = runBlocking {
        val process = FakeProcess()
        val player = newPlayer { process }

        try {
            assertTrue(player.play(link(), "Test", emptyList(), 0L).isSuccess)
            assertTrue(player.state.value.isPlaying)

            process.finishNaturally()

            awaitCondition { player.state.value.isFinished }
            assertFalse(player.state.value.isPlaying)
            assertFalse(player.state.value.isPaused)
            assertNotNull(player.state.value.currentUrl)
            Unit
        } finally {
            player.destroy()
        }
    }

    @Test
    fun pauseResumeAndSeekKeepPlaybackPositionUntilVlcReportsANewSnapshot() = runBlocking {
        val process = FakeProcess()
        val remoteControl = FakeRemoteControl()
        val player = newPlayer(remoteControl) { process }
        val startingPosition = 23_456L

        try {
            assertTrue(player.play(link(), "Test", emptyList(), startingPosition).isSuccess)
            awaitCondition { remoteControl.connectAttempts.get() > 0 }

            player.pause()
            assertTrue(remoteControl.pauseCalled.await(3, TimeUnit.SECONDS))
            assertTrue(player.state.value.isPlaying)
            assertTrue(player.state.value.isPaused)
            assertEquals(startingPosition, player.state.value.position)

            player.resume()
            assertTrue(remoteControl.resumeCalled.await(3, TimeUnit.SECONDS))
            assertTrue(player.state.value.isPlaying)
            assertFalse(player.state.value.isPaused)

            player.seek(61_999L)
            assertTrue(remoteControl.seekCalled.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(61_999L), remoteControl.seeks)
            assertEquals(startingPosition, player.state.value.position)
        } finally {
            player.destroy()
        }
    }

    private fun newPlayer(
        remoteControl: FakeRemoteControl = FakeRemoteControl(),
        launcher: (List<String>) -> Process,
    ): VlcPlayer = VlcPlayer(
        remoteControlFactory = VlcRemoteControlFactory { remoteControl },
        executableLocator = { "test-vlc" },
        processLauncher = launcher,
    )

    private suspend fun link(url: String = "/video.mp4") = newExtractorLink(
        source = "LifecycleTest",
        name = "Lifecycle test video",
        url = url,
    )

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met before timeout")
    }

    private class FakeProcess : Process() {
        private val ended = CountDownLatch(1)

        @Volatile private var status = 0

        @Volatile var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int {
            ended.await()
            return status
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)

        override fun exitValue(): Int {
            if (ended.count != 0L) throw IllegalThreadStateException("process is still running")
            return status
        }

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            finish(137)
            return this
        }

        override fun isAlive(): Boolean = ended.count != 0L

        fun finishNaturally() = finish(0)

        private fun finish(exitStatus: Int) {
            status = exitStatus
            ended.countDown()
        }
    }

    private class FakeRemoteControl : VlcRemoteControl {
        val connectAttempts = AtomicInteger()
        val pauseCalled = CountDownLatch(1)
        val resumeCalled = CountDownLatch(1)
        val seekCalled = CountDownLatch(1)
        val seeks = java.util.concurrent.CopyOnWriteArrayList<Long>()

        override fun connect(timeoutMillis: Long): Boolean {
            connectAttempts.incrementAndGet()
            return false
        }

        override fun readPlaybackSnapshot(): VlcPlaybackSnapshot? = null

        override fun pause(): Boolean {
            pauseCalled.countDown()
            return true
        }

        override fun resume(): Boolean {
            resumeCalled.countDown()
            return true
        }

        override fun seek(positionMs: Long): Boolean {
            seeks.add(positionMs)
            seekCalled.countDown()
            return true
        }

        override fun close() = Unit
    }
}
