package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.OrbitVideoActionRuntime
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

/** Pinned single-source action backed by Orbit's existing validated player route. */
class PlayMirrorAction : VideoClickAction() {
    override val name = UiText.DynamicString("Play mirror")
    override val oneSource = true
    override val isPlayer = true
    // Match the pinned source-type contract. Orbit's validated mirror bridge only exposes
    // links that it can resolve to a native playback candidate.
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
        ExtractorLinkType.TORRENT,
        ExtractorLinkType.MAGNET,
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?) =
        context is OrbitVideoActionRuntime.InternalPlayerHost

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val selected = index ?: return
        if (selected !in result.links.indices) return
        uiThread {
            val host = context as? OrbitVideoActionRuntime.InternalPlayerHost
                ?: throw IllegalStateException("The current screen cannot play this mirror")
            host.playMirror(this, video, result, selected)
        }
    }
}
