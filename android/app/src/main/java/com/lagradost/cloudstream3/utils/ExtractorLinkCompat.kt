@file:JvmName("ExtractorLinkCompat")

package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.MainActivityKt
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/** Kotlin bridge for the suspend HEAD request used by the Java ABI facade. */
suspend fun getOrbitVideoSize(link: ExtractorLink, timeoutSeconds: Long): Long? {
    if (link.type != ExtractorLinkType.VIDEO) return null
    return try {
        val response = MainActivityKt.getApp().head(
            link.url,
            headers = link.headers,
            referer = link.referer,
            timeout = timeoutSeconds,
        )
        response.headers["Content-Length"]?.toLong()
    } catch (_: Exception) {
        null
    }
}
