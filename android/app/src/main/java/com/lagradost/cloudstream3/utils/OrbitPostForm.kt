@file:JvmName("OrbitPostForm")

package com.lagradost.cloudstream3.utils

import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.MainActivityKt
import kotlinx.coroutines.delay

/**
 * Orbit-owned implementation of the pinned post-form behavior at
 * recloudstream/cloudstream 5024ace2922a148d38df2a816b483b529789cf58.
 * It uses the shared NiceHttp client only when invoked and does not copy the
 * upstream implementation body; upstream source is GPL-3.0.
 */
suspend fun getPostForm(requestUrl: String, html: String): String? {
    val inputs = Ksoup.parse(html).select("Form > input")
    if (inputs.size < 4) return null

    val extracted = HashMap<String, String>()
    val expectedNames = setOf("op", "id", "mode", "hash")
    for (input in inputs) {
        val fieldName = input.attr("name")
        if (fieldName in expectedNames) {
            extracted[fieldName] = input.attr("value")
        }
    }
    if (extracted.size != expectedNames.size) return null

    val formData = linkedMapOf(
        "op" to extracted.getValue("op"),
        "id" to extracted.getValue("id"),
        "mode" to extracted.getValue("mode"),
        "hash" to extracted.getValue("hash"),
    )

    delay(5_000L)
    return MainActivityKt.getApp().post(
        requestUrl,
        headers = linkedMapOf(
            "content-type" to "application/x-www-form-urlencoded",
            "referer" to requestUrl,
            "user-agent" to MainAPIKt.USER_AGENT,
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        ),
        data = formData,
    ).text
}
