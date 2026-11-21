package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.content.Intent
import com.auras.orbit.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.makeTempM3U8Intent
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText

/** Pinned playlist handoff action; the FileProvider is restricted to app cache. */
class ViewM3U8Action : VideoClickAction() {
    override val name = UiText.StringResource(
        R.string.episode_action_play_in_format,
        listOf(UiText.DynamicString("m3u8 player")),
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val hostContext = context ?: return
        val intent = Intent(Intent.ACTION_VIEW)
        makeTempM3U8Intent(hostContext, intent, result)
        launch(intent)
    }
}
