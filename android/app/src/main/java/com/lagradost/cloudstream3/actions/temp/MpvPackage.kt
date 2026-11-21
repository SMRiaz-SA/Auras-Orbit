package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.makeTempM3U8Intent
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

class MpvExPackage : MpvPackage(
    "mpvEx",
    "app.marlboroadvance.mpvex",
    "app.marlboroadvance.mpvex.ui.player.PlayerActivity",
)

class MpvYTDLPackage : MpvPackage("MPV YTDL", "is.xyz.mpv.ytdl") {
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
    )
}

open class MpvPackage(
    appName: String = "MPV",
    packageName: String = "is.xyz.mpv",
    intentClass: String = "is.xyz.mpv.MPVActivity",
) : OpenInAppAction(UiText.DynamicString(appName), packageName, intentClass) {
    override val oneSource = true

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        intent.putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
        intent.putExtra("title", video.name)

        if (index != null) {
            val link = result.links.getOrNull(index) ?: return
            intent.setDataAndType(link.url.toUri(), "video/*")
        } else {
            makeTempM3U8Intent(context, intent, result)
        }

        intent.putExtra("position", video.position.coerceAtLeast(0L).toInt())
        intent.putExtra("secure_uri", true)
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        updateDurationAndPosition(position.toLong(), duration.toLong())
    }
}
