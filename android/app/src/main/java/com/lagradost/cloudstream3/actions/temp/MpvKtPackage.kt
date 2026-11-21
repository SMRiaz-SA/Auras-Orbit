package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

class MpvKtPreviewPackage : MpvKtPackage(
    appName = "mpvKt Preview",
    packageName = "live.mehiz.mpvkt.preview",
)

open class MpvKtPackage(
    appName: String = "mpvKt",
    packageName: String = "live.mehiz.mpvkt",
) : OpenInAppAction(
    appName = UiText.DynamicString(appName),
    packageName = packageName,
    intentClass = "live.mehiz.mpvkt.ui.player.PlayerActivity",
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
        intent.putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
        intent.setDataAndType(link.url.toUri(), "video/*")
        intent.putExtra("position", video.position.coerceAtLeast(0L).toInt())
        intent.putExtra("secure_uri", true)
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        updateDurationAndPosition(position.toLong(), duration.toLong())
    }
}
