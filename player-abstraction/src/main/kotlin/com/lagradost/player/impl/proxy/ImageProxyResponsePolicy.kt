package com.lagradost.player.impl.proxy

import java.io.InputStream

internal object ImageProxyResponsePolicy {
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    fun accepts(contentType: String?, declaredLength: Long): Boolean =
        contentType?.startsWith("image/", ignoreCase = true) == true && declaredLength <= MAX_IMAGE_BYTES

    fun readBounded(input: InputStream): ByteArray = input.readNBytes(MAX_IMAGE_BYTES + 1)

    fun isWithinLimit(bytes: ByteArray): Boolean = bytes.size <= MAX_IMAGE_BYTES
}
