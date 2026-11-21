package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.OrbitVideoActionRuntime
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText

/** Like upstream, this is a caller policy and is hidden from video action lists. */
class AlwaysAskAction : VideoClickAction() {
    override val name = UiText.DynamicString("Always ask")
    override val isPlayer = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) =
        video == null && context is OrbitVideoActionRuntime.InternalPlayerHost

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        uiThread {
            val host = context as? OrbitVideoActionRuntime.InternalPlayerHost
                ?: throw IllegalStateException("The current screen cannot choose a player")
            host.choosePlayer(this, video)
        }
    }
}
