package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink

data class LinkLoadingResult(
    val links: List<ExtractorLink>,
    val subs: List<SubtitleData>,
    val syncData: HashMap<String, String>,
)
