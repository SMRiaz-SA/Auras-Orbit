package com.lagradost.cloudstream3.desktop.sync

import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

/** Receives a single OAuth callback on loopback and validates its state and issuer. */
object OAuthLocalServer {
    private const val TAG = "OAuthLocalServer"
    private const val ACCEPT_POLL_MS = 2_000

    suspend fun authenticate(
        authorizationUrl: (redirectUri: String) -> String,
        expectedState: String,
        callbackPath: String = "/oauth/callback",
        expectedIssuer: String? = null,
        timeoutMillis: Long = 180_000,
    ): String? = withContext(Dispatchers.IO) {
        require(callbackPath.startsWith('/') && !callbackPath.contains('?') && !callbackPath.contains('#'))
        require(expectedState.isNotBlank())

        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = ACCEPT_POLL_MS
                val redirectUri = "http://127.0.0.1:${server.localPort}$callbackPath"
                val url = authorizationUrl(redirectUri)
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    AppLogger.e("$TAG: Browser launch is not supported on this system.")
                    return@withContext null
                }
                Desktop.getDesktop().browse(URI(url))

                val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
                while (System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                    server.soTimeout = minOf(ACCEPT_POLL_MS.toLong(), remainingMs).toInt()
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    socket.use { client ->
                        client.soTimeout = 3_000
                        val input = client.getInputStream()
                        val requestLine = buildString {
                            while (length < 8192) {
                                val byte = input.read()
                                if (byte < 0 || byte == 10) break
                                if (byte != 13) append(byte.toChar())
                            }
                        }
                        if (requestLine.length >= 8192) {
                            respond(client, 400, "OAuth callback is too large.")
                            continue
                        }
                        val parts = requestLine?.split(' ', limit = 3).orEmpty()
                        val target = parts.getOrNull(1)
                        if (parts.firstOrNull() != "GET" || target == null) {
                            respond(client, 400, "Invalid OAuth callback.")
                            return@withContext null
                        }

                        val path = target.substringBefore('?').substringBefore('#')
                        if (path != callbackPath) {
                            respond(client, 404, "Not found.")
                            continue
                        }

                        val query = target.substringAfter('?', "").substringBefore('#')
                        val callbackUrl = "$redirectUri?$query"
                        val parameters = AuthAPI.splitRedirectUrl(callbackUrl)
                        if (parameters["state"] != expectedState) {
                            respond(client, 400, "Sign-in was rejected because the state check failed. Return to the app and try again.")
                            AppLogger.w("$TAG: Rejected OAuth callback with a mismatched state.")
                            return@withContext null
                        }
                        if (expectedIssuer != null && parameters["iss"] != expectedIssuer) {
                            respond(client, 400, "Sign-in was rejected because the issuer could not be verified.")
                            AppLogger.w("$TAG: Rejected OAuth callback from an unexpected issuer.")
                            return@withContext null
                        }
                        if (!parameters["error"].isNullOrBlank()) {
                            respond(client, 200, "Sign-in was cancelled. You can close this tab and return to the app.")
                            return@withContext null
                        }
                        if (parameters["code"].isNullOrBlank()) {
                            respond(client, 400, "The authorization response did not include a code.")
                            return@withContext null
                        }

                        respond(client, 200, "Sign-in received. You can close this tab and return to the app.")
                        return@withContext callbackUrl
                    }
                }
                AppLogger.w("$TAG: Timed out waiting for OAuth callback.")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("$TAG: OAuth sign-in failed.", e)
            null
        }
    }

    private fun respond(socket: Socket, status: Int, message: String) {
        val reason = if (status == 200) {
            "OK"
        } else if (status == 404) {
            "Not Found"
        } else {
            "Bad Request"
        }
        val body = """
            <!doctype html><meta charset="utf-8"><title>Auras Orbit sign-in</title>
            <main style="font:16px system-ui;margin:10vh auto;max-width:34rem;text-align:center">
              <h2>Auras Orbit sign-in</h2><p>$message</p>
            </main>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().use { output ->
            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }
}
