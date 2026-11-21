package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText

class OnlyPlayer : OpenInAppAction(
    UiText.DynamicString("Only Player"),
    "one.only.player",
    intentClass = "one.only.player.feature.player.PlayerActivity",
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
        intent.data = link.url.toUri()
        intent.putExtra("headers", Bundle().apply {
            link.headers.forEach { (key, value) -> putString(key, value) }
        })
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
