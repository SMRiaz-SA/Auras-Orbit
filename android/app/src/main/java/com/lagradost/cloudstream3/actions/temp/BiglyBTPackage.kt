package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

class BiglyBTPackage : OpenInAppAction(
    appName = UiText.DynamicString("BiglyBT"),
    packageName = "com.biglybt.android.client",
    intentClass = "com.biglybt.android.client.activity.IntentHandler",
) {
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT)

    override val oneSource: Boolean = true

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val link = result.links.getOrNull(index ?: return) ?: return
        intent.data = link.url.toUri()
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
