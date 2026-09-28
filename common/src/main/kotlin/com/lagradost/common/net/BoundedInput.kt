package com.lagradost.common.net

import java.io.IOException
import java.io.InputStream

/** Reads at most one byte beyond a budget, including bodies without Content-Length. */
fun InputStream.readBoundedBytes(limit: Int): ByteArray {
    require(limit in 0 until Int.MAX_VALUE)
    val bytes = readNBytes(limit + 1)
    if (bytes.size > limit) throw IOException("Response exceeded its $limit byte budget")
    return bytes
}
