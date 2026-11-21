package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.makeTempM3U8Intent
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText

class VlcNightlyPackage : VlcPackage() {
    override val packageName = "org.videolan.vlc.debug"
    override val appName = UiText.DynamicString("VLC Nightly")
}

open class VlcPackage : OpenInAppAction(
    appName = UiText.DynamicString("VLC"),
    packageName = "org.videolan.vlc",
    intentClass = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        "org.videolan.vlc.gui.video.VideoPlayerActivity"
    } else {
        null
    },
    action = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        "org.videolan.vlc.player.result"
    } else {
        Intent.ACTION_VIEW
    },
) {
    override val oneSource = true

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        if (index == null) {
            makeTempM3U8Intent(context, intent, result)
        } else {
            val link = result.links.getOrNull(index) ?: return
            intent.setDataAndType(link.url.toUri(), "video/*")
        }

        intent.putExtra("from_start", false)
        intent.putExtra("position", video.position.coerceAtLeast(0L))
        intent.putExtra("secure_uri", true)
        intent.putExtra("title", video.name)
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getLongExtra("extra_position", -1L) ?: -1L
        val duration = intent?.getLongExtra("extra_duration", -1L) ?: -1L
        updateDurationAndPosition(position, duration)
    }
}
