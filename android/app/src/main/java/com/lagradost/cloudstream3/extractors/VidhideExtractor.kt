package com.lagradost.cloudstream3.extractors

/** VidHide host alias retained from the pinned stable and pre-release baselines. */
open class VidhideExtractor : VidHidePro() {
    init { initializeMetadata(name = "VidHide", mainUrl = "https://vidhide.com") }

    override fun getRequiresReferer(): Boolean = false
}
