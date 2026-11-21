package com.lagradost.cloudstream3.actions.temp.fcast

import android.os.Looper
import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded FCast v1 sender. Writes finish before use/close returns. */
class FcastSession @JvmOverloads constructor(
    private val hostAddress: String,
    private val port: Int = FcastManager.TCP_PORT,
) : AutoCloseable {
    val tag = "FcastSession"
    @Volatile private var socket: Socket? = null

    @Throws(IOException::class)
    @WorkerThread
    @Synchronized
    fun open(): Socket {
        check(Looper.myLooper() != Looper.getMainLooper()) { "FCast connections require a worker thread" }
        socket?.takeIf { it.isConnected && !it.isClosed }?.let { return it }
        require(port in 1..65535) { "Invalid FCast receiver port" }
        // Discovery provides a numeric address; never resolve arbitrary hostnames here.
        val address = numericAddress(hostAddress)
        require(FcastManager.isLocalReceiver(address)) { "FCast requires a discovered local receiver" }
        val connected = Socket()
        try {
            connected.connect(InetSocketAddress(address, port), 5_000)
            connected.soTimeout = 5_000
            socket = connected
            return connected
        } catch (error: Throwable) {
            try { connected.close() } catch (_: IOException) { }
            throw error
        }
    }

    override fun close() {
        val current = socket
        socket = null
        try { current?.close() } catch (_: IOException) { }
    }

    fun ping() { sendMessage<Any?>(Opcode.Ping, null) }

    @Throws(IOException::class)
    @WorkerThread
    fun <T> sendMessage(opcode: Opcode, message: T) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "FCast writes require a worker thread" }
        val content = message?.toJson()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(content.size <= 65_535) { "FCast message exceeds the frame limit" }
        val current = open()
        val timeout = deadlines.schedule({ try { current.close() } catch (_: IOException) { } },
            10, TimeUnit.SECONDS)
        try {
            val size = content.size + 1
            val output = current.getOutputStream()
            output.write(ByteArray(4) { shift -> (size ushr (8 * shift)).toByte() })
            output.write(opcode.value.toInt())
            output.write(content)
            output.flush()
        } finally { timeout.cancel(false) }
    }

    private fun numericAddress(value: String): InetAddress {
        if (value.contains(':') && value.matches(Regex("[0-9A-Fa-f:]+(?:%[A-Za-z0-9_.-]+)?"))) {
            return InetAddress.getByName(value)
        }
        val octets = value.split('.')
        require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) {
            "FCast receiver address must be numeric"
        }
        return InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
    }

    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "orbit-fcast-deadline").apply { isDaemon = true }
        }
    }
}
