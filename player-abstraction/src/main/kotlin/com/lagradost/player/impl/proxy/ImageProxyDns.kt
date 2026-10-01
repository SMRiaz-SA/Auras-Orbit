package com.lagradost.player.impl.proxy

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/** DNS boundary for remote artwork requests. Every redirect is resolved through this policy too. */
internal class ImageProxyDns(private val upstream: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val normalized = hostname.trimEnd('.').lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized == "localhost" ||
            normalized.endsWith(".localhost") || normalized.endsWith(".local")
        ) {
            throw BlockedImageHostException(hostname)
        }

        val addresses = upstream.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw BlockedImageHostException(hostname)
        }
        return addresses
    }

    companion object {
        internal fun isPublicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            if (address is Inet4Address || isIpv4MappedIpv6(bytes)) {
                val ipv4 = if (bytes.size == 4) bytes else bytes.copyOfRange(12, 16)
                return IPV4_NON_PUBLIC.none { it.matches(ipv4) }
            }

            if (address !is Inet6Address || address.scopeId != 0) return false
            // Only global unicast (2000::/3) is accepted. Special-use and transition ranges
            // inside that allocation are excluded because they can encode or route to private IPs.
            return (bytes[0].toInt() and 0xE0) == 0x20 &&
                IPV6_NON_PUBLIC.none { it.matches(bytes) }
        }

        private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean =
            bytes.size == 16 && bytes.sliceArray(0 until 10).all { it == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

        private data class Cidr(val network: ByteArray, val prefixBits: Int) {
            fun matches(address: ByteArray): Boolean {
                if (address.size != network.size) return false
                val fullBytes = prefixBits / 8
                val remainingBits = prefixBits % 8
                for (index in 0 until fullBytes) {
                    if (address[index] != network[index]) return false
                }
                if (remainingBits == 0) return true
                val mask = (0xFF shl (8 - remainingBits)) and 0xFF
                return (address[fullBytes].toInt() and mask) ==
                    (network[fullBytes].toInt() and mask)
            }
        }

        private fun ipv4(a: Int, b: Int, c: Int, d: Int, prefix: Int) =
            Cidr(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()), prefix)

        private fun ipv6(hex: String, prefix: Int): Cidr = Cidr(
            InetAddress.getByName(hex).address,
            prefix,
        )

        private val IPV4_NON_PUBLIC = listOf(
            ipv4(0, 0, 0, 0, 8), // unspecified / this network
            ipv4(10, 0, 0, 0, 8), // private
            ipv4(100, 64, 0, 0, 10), // carrier-grade NAT
            ipv4(127, 0, 0, 0, 8), // loopback
            ipv4(169, 254, 0, 0, 16), // link-local / cloud metadata
            ipv4(172, 16, 0, 0, 12), // private
            ipv4(192, 0, 0, 0, 24), // IETF special-purpose
            ipv4(192, 0, 2, 0, 24), // documentation
            ipv4(192, 88, 99, 0, 24), // 6to4 relay anycast
            ipv4(192, 168, 0, 0, 16), // private
            ipv4(198, 18, 0, 0, 15), // benchmarking
            ipv4(198, 51, 100, 0, 24), // documentation
            ipv4(203, 0, 113, 0, 24), // documentation
            ipv4(224, 0, 0, 0, 4), // multicast
            ipv4(240, 0, 0, 0, 4), // reserved / broadcast
        )

        private val IPV6_NON_PUBLIC = listOf(
            ipv6("2001::", 23), // protocol assignments, including Teredo
            ipv6("2001:db8::", 32), // documentation
            ipv6("2001:10::", 28), // ORCHID
            ipv6("2001:20::", 28), // ORCHIDv2
            ipv6("2002::", 16), // 6to4 transition
            ipv6("3fff::", 20), // documentation
        )
    }
}

internal class BlockedImageHostException(hostname: String) :
    UnknownHostException("Image proxy blocked non-public host: $hostname")

internal object ImageProxyUrlPolicy {
    fun parse(raw: String): HttpUrl? {
        val candidate = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.host.isBlank() ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) {
            return null
        }
        return url
    }
}
