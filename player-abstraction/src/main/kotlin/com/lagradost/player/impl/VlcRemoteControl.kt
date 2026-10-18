package com.lagradost.player.impl

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

internal data class VlcPlaybackSnapshot(
    val positionMs: Long?,
    val durationMs: Long?,
)

internal interface VlcRemoteControl : Closeable {
    /** Connects to the RC endpoint, retrying until the timeout expires. */
    fun connect(timeoutMillis: Long): Boolean

    fun readPlaybackSnapshot(): VlcPlaybackSnapshot?
    fun pause(): Boolean
    fun resume(): Boolean
    fun seek(positionMs: Long): Boolean
}

internal fun interface VlcRemoteControlFactory {
    fun create(port: Int): VlcRemoteControl
}

/**
 * VLC's oldrc interface is a line-oriented TCP protocol. It sends CRLF-terminated lines, with
 * asynchronous status messages mixed into command output and no prompt framing. Every session is
 * pointed at a loopback-only, per-process port by [VlcPlayer].
 */
internal class VlcRcSession(
    private val port: Int,
    private val socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MS,
) : VlcRemoteControl {
    private val lock = Any()

    @Volatile
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var closed = false

    override fun connect(timeoutMillis: Long): Boolean = synchronized(lock) {
        connectLocked(timeoutMillis)
    }

    override fun readPlaybackSnapshot(): VlcPlaybackSnapshot? = synchronized(lock) {
        val position = querySecondsLocked("get_time")
        val duration = querySecondsLocked("get_length")
        if (position == null && duration == null) {
            null
        } else {
            VlcPlaybackSnapshot(
                positionMs = position?.let(::secondsToMillis),
                durationMs = duration?.let(::secondsToMillis),
            )
        }
    }

    // oldrc's pause command is a toggle. VlcPlayer calls the matching action from tracked state.
    override fun pause(): Boolean = writeCommand("pause")
    override fun resume(): Boolean = writeCommand("pause")

    override fun seek(positionMs: Long): Boolean {
        val seconds = positionMs.coerceAtLeast(0L) / 1_000L
        return writeCommand("seek $seconds")
    }

    override fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        synchronized(lock) {
            closeSocketLocked()
        }
    }

    private fun writeCommand(command: String): Boolean = synchronized(lock) {
        if (!isSafeCommand(command) || !connectLocked(COMMAND_CONNECT_TIMEOUT_MS)) return@synchronized false
        try {
            val activeWriter = writer ?: return@synchronized false
            activeWriter.write(command)
            activeWriter.newLine()
            activeWriter.flush()
            true
        } catch (_: Exception) {
            closeSocketLocked()
            false
        }
    }

    private fun querySecondsLocked(command: String): Long? {
        if (!isSafeCommand(command) || !connectLocked(COMMAND_CONNECT_TIMEOUT_MS)) return null

        return try {
            val activeWriter = writer ?: return null
            activeWriter.write(command)
            activeWriter.newLine()
            activeWriter.flush()

            val deadline = System.nanoTime() + socketTimeoutMillis.coerceAtLeast(1).toLong() * 1_000_000L
            while (System.nanoTime() < deadline) {
                val activeSocket = socket ?: return null
                val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L)
                    .coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                activeSocket.soTimeout = remainingMillis.toInt()

                val line = reader?.readLine() ?: run {
                    closeSocketLocked()
                    return null
                }
                line.trim().toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
                // Ignore oldrc's unsolicited state changes and any non-numeric command output.
            }
            closeSocketLocked()
            null
        } catch (_: SocketTimeoutException) {
            // Drop the stream after a missed reply so a late numeric line cannot be mistaken for a
            // later query's result.
            closeSocketLocked()
            null
        } catch (_: Exception) {
            closeSocketLocked()
            null
        }
    }

    private fun connectLocked(timeoutMillis: Long): Boolean {
        if (closed) return false
        if (socket?.isConnected == true && socket?.isClosed == false) return true

        closeSocketLocked()
        val timeoutNanos = timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        val deadline = System.nanoTime() + timeoutNanos
        var firstAttempt = true
        while (firstAttempt || System.nanoTime() < deadline) {
            if (closed) return false
            firstAttempt = false
            val candidate = Socket()
            socket = candidate
            try {
                candidate.tcpNoDelay = true
                candidate.soTimeout = socketTimeoutMillis
                val remainingMillis = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                candidate.connect(
                    InetSocketAddress(LOOPBACK, port),
                    minOf(CONNECT_ATTEMPT_TIMEOUT_MS.toLong(), remainingMillis).toInt(),
                )
                if (closed) throw java.io.IOException("VLC RC session closed during connect")
                reader = BufferedReader(InputStreamReader(candidate.getInputStream(), StandardCharsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8))
                return true
            } catch (_: Exception) {
                try {
                    candidate.close()
                } catch (_: Exception) {
                }
                if (socket === candidate) closeSocketLocked()
                if (closed || System.nanoTime() >= deadline) break
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return false
    }

    private fun closeSocketLocked() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        writer = null
    }

    private fun isSafeCommand(command: String): Boolean =
        command.isNotBlank() && !command.contains('\n') && !command.contains('\r')

    private fun secondsToMillis(seconds: Long): Long =
        if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val DEFAULT_SOCKET_TIMEOUT_MS = 1_500
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 250
        const val CONNECT_RETRY_DELAY_MS = 75L
        const val COMMAND_CONNECT_TIMEOUT_MS = 750L
    }
}

/** Reserve an unused IPv4 loopback port for one VLC process. VLC binds it after launch. */
internal fun allocateVlcRcPort(): Int = ServerSocket().use { server ->
    server.reuseAddress = false
    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    server.localPort
}
