package com.auras.orbit.providers

import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState

/** Ephemeral action payload. Never persisted in Orbit's library or archives. */
data class Cs3ActionContext(
    val pluginId: String,
    val profileId: String,
    val video: ResultEpisode,
    val result: LinkLoadingResult,
) {
    fun withProgress(positionMs: Long, durationMs: Long, watched: Boolean): Cs3ActionContext = copy(
        video = video.copy(position = positionMs.coerceAtLeast(0),
            duration = durationMs.takeIf { it > 0 } ?: video.duration,
            videoWatchState = if (watched) VideoWatchState.Watched else VideoWatchState.None),
    )
}
