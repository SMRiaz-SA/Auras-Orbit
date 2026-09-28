package com.lagradost.player.impl.proxy

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageProxyResponsePolicyTest {
    @Test
    fun `accepts image content with unknown or in-limit declared size only`() {
        assertTrue(ImageProxyResponsePolicy.accepts("image/jpeg", -1))
        assertTrue(ImageProxyResponsePolicy.accepts("IMAGE/WEBP", ImageProxyResponsePolicy.MAX_IMAGE_BYTES.toLong()))
        assertFalse(ImageProxyResponsePolicy.accepts("text/html", 1))
        assertFalse(ImageProxyResponsePolicy.accepts("image/svg+xml", ImageProxyResponsePolicy.MAX_IMAGE_BYTES.toLong() + 1))
    }

    @Test
    fun `image body read stops after one byte beyond the limit`() {
        val acceptedSource = ByteArrayInputStream(ByteArray(ImageProxyResponsePolicy.MAX_IMAGE_BYTES))
        val accepted = ImageProxyResponsePolicy.readBounded(acceptedSource)
        assertEquals(ImageProxyResponsePolicy.MAX_IMAGE_BYTES, accepted.size)
        assertTrue(ImageProxyResponsePolicy.isWithinLimit(accepted))

        val oversizedSource = ByteArrayInputStream(ByteArray(ImageProxyResponsePolicy.MAX_IMAGE_BYTES + 64))
        val oversized = ImageProxyResponsePolicy.readBounded(oversizedSource)
        assertEquals(ImageProxyResponsePolicy.MAX_IMAGE_BYTES + 1, oversized.size)
        assertFalse(ImageProxyResponsePolicy.isWithinLimit(oversized))
        assertEquals(63, oversizedSource.available())
    }
}
