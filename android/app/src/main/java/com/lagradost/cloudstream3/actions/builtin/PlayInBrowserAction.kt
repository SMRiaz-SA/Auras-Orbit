package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.auras.orbit.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

/** Pinned browser action, kept in the stable slot while using Orbit's activity bridge. */
class PlayInBrowserAction : VideoClickAction() {
    override val name = UiText.StringResource(
        R.string.episode_action_play_in_format,
        listOf(UiText.DynamicString("Browser")),
    )
    override val oneSource = true
    override val isPlayer = true
    override val sourceTypes: Set<ExtractorLinkType> = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val selected = result.links.getOrNull(index ?: 0) ?: return
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(selected.url)))
    }
}
