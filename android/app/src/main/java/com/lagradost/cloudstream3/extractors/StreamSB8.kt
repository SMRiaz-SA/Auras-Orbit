package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/StreamSB.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

/** Pinned StreamSB8 alias with its own host and inherited request behavior. */
class StreamSB8 : StreamSB() {
    override fun getMainUrl(): String = mainUrl
    private var mainUrl = "https://streamsb.net"
    override fun setMainUrl(value: String) { mainUrl = requireNotNull(value) }
}
