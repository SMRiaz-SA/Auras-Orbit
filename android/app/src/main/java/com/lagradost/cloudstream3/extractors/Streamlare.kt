package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Streamlare.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

/** Pinned Streamlare host alias for the shared Slmaxed request flow. */
class Streamlare : Slmaxed() {
    override fun getMainUrl(): String = "https://streamlare.com/"
}
