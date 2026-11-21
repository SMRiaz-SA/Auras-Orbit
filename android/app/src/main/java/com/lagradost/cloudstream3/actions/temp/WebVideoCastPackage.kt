package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

class WebVideoCastPackage : OpenInAppAction(
    UiText.DynamicString("Web Video Cast"),
    "com.instantbits.cast.webvideo",
) {
    override val oneSource = true

    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
    )

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val link = result.links.getOrNull(index ?: 0) ?: return
        intent.setDataAndType(link.url.toUri(), "video/*")
        intent.putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
        intent.putExtra("title", video.name ?: video.headerName)
        video.poster?.let { intent.putExtra("poster", it) }

        val headers = Bundle().apply {
            if (link.referer.isNotBlank()) putString("Referer", link.referer)
            putString("User-Agent", MainAPIKt.USER_AGENT)
            link.headers.forEach { (key, value) -> putString(key, value) }
        }
        intent.putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
        intent.putExtra("secure_uri", true)
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
