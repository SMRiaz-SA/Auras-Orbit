package com.auras.orbit.player

import com.lagradost.cloudstream3.utils.lookupPublicPlaybackAddresses
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Per-factory lifetime cap; pins are never evicted, and new hostnames fail closed at the cap.
private const val MAX_PINNED_PLAYBACK_HOSTS = 64

/** Creates a DNS resolver whose successful answers stay fixed for one player factory session. */
object PlaybackDnsPinning {
    @JvmStatic
    fun newDns(allowTrustedTorrentLoopback: Boolean): Dns {
        val pinnedAnswers = ConcurrentHashMap<String, List<InetAddress>>()
        return Dns { hostname ->
            val host = hostname.trim('[', ']').lowercase(Locale.ROOT)
            if (allowTrustedTorrentLoopback && host == "127.0.0.1") {
                return@Dns listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }

            try {
                val alreadyPinned = pinnedAnswers[host]
                if (alreadyPinned != null) return@Dns alreadyPinned
                synchronized(pinnedAnswers) {
                    val pinned = pinnedAnswers[host]
                    if (pinned != null) {
                        pinned
                    } else {
                        if (pinnedAnswers.size >= MAX_PINNED_PLAYBACK_HOSTS) {
                            throw IllegalStateException(
                                "Playback factory reached its $MAX_PINNED_PLAYBACK_HOSTS-host DNS pin limit"
                            )
                        }
                        val addresses = runBlocking {
                            lookupPublicPlaybackAddresses(host)
                        }
                        Collections.unmodifiableList(ArrayList(addresses)).also {
                            pinnedAnswers[host] = it
                        }
                    }
                }
            } catch (failure: Exception) {
                throw UnknownHostException("Playback host has no validated public DNS answer: $host")
                    .apply { initCause(failure) }
            }
        }
    }
}
