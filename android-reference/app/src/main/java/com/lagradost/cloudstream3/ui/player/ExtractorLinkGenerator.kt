package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.ui.result.ResultEpisode

class ExtractorLinkGenerator(
    private val links: List<ExtractorLink>,
    private val subtitles: List<SubtitleData>,
    private val episode: ResultEpisode? = null,
    private val nextEpisode: ResultEpisode? = null,
) : VideoGenerator<ResultEpisode>(listOfNotNull(episode)) {
    override val hasCache = false
    override val canSkipLoading = false
    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id
    override fun getNextMeta(index: Int): Any? = if (index == 0) nextEpisode else null

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        subtitles.forEach(subtitleCallback)
        links.forEach {
            if(sourceTypes.contains(it.type)) {
                callback.invoke(it to null)
            }
        }

        return true
    }
}
