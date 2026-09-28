package com.lagradost.player.impl.proxy

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageProxyDnsTest {
    @Test
    fun allowsOnlyGloballyRoutableAddressRanges() {
        listOf(
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.0.0.8", "192.0.2.1", "192.168.1.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "::", "::1",
            "fe80::1", "fc00::1", "2001:db8::1", "2002:0808:0808::1", "3fff::1",
        ).forEach { address ->
            assertFalse(ImageProxyDns.isPublicAddress(InetAddress.getByName(address)), "$address must be blocked")
        }

        listOf("1.1.1.1", "93.184.216.34", "2606:4700:4700::1111", "2001:4860:4860::8888")
            .forEach { address ->
                assertTrue(ImageProxyDns.isPublicAddress(InetAddress.getByName(address)), "$address must be allowed")
            }
    }

    @Test
    fun rejectsPrivateOrMixedDnsAnswersAndLocalNames() {
        val privateDns = ImageProxyDns(Dns { listOf(InetAddress.getByName("10.0.0.5")) })
        assertFailsWith<UnknownHostException> { privateDns.lookup("art.example") }

        val mixedDns = ImageProxyDns(
            Dns {
                listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
            },
        )
        assertFailsWith<UnknownHostException> { mixedDns.lookup("art.example") }

        val neverQueryDns = ImageProxyDns(Dns { error("Local hostname should be rejected before DNS") })
        assertFailsWith<UnknownHostException> { neverQueryDns.lookup("service.localhost") }
        assertFailsWith<UnknownHostException> { neverQueryDns.lookup("printer.local") }
    }

    @Test
    fun validatesImageUrlsAndKeepsProtocolRelativeCompatibility() {
        assertEquals("https://cdn.example/image.jpg", ImageProxyUrlPolicy.parse("//cdn.example/image.jpg").toString())
        assertEquals("https://cdn.example/image.jpg", ImageProxyUrlPolicy.parse("https://cdn.example/image.jpg").toString())
        assertNotNull(ImageProxyUrlPolicy.parse("http://cdn.example/image.jpg"))
        assertNull(ImageProxyUrlPolicy.parse("file:///etc/passwd"))
        assertNull(ImageProxyUrlPolicy.parse("https://user:secret@cdn.example/image.jpg"))
        assertNull(ImageProxyUrlPolicy.parse("not a URL"))
    }
}
