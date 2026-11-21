package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText

class MpvRxPackage : OpenInAppAction(
    appName = UiText.DynamicString("mpvRx"),
    packageName = "app.gyrolet.mpvrx",
    intentClass = "app.gyrolet.mpvrx.ui.player.PlayerActivity",
) {
    override val oneSource = true

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val link = result.links.getOrNull(index ?: return) ?: return
        intent.putExtra("title", video.name)
        intent.data = link.url.toUri()

        if (link.headers.isNotEmpty()) {
            val flatHeaders = link.headers.entries
                .flatMap { (key, value) -> listOf(key, value) }
                .toTypedArray()
            intent.putExtra("headers", flatHeaders)
        }

        // mpvRx's intro skip metadata only applies to an episode with a known season.
        if (video.season != null) {
            intent.putExtra("introdb_season", video.season)
            intent.putExtra("introdb_episode", video.episode)
        }
        intent.putExtra("position", video.position.coerceAtLeast(0L).toInt())
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        updateDurationAndPosition(position.toLong(), duration.toLong())
    }
}
