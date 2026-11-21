package com.lagradost.cloudstream3.actions.temp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText

/** Pinned copy action with an explicit Orbit context instead of a global clipboard helper. */
class CopyClipboardAction : VideoClickAction() {
    override val name = UiText.DynamicString("Copy to clipboard")
    override val oneSource = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val hostContext = context ?: return
        val selected = result.links.getOrNull(index ?: return) ?: return
        val clipboard = hostContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(selected.name, selected.url))
    }
}
