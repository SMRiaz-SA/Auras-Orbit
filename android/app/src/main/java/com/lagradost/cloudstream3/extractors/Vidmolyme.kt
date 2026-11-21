package com.lagradost.cloudstream3.extractors

// Behavior/API reference: CloudStream library/src/commonMain/kotlin/com/lagradost/cloudstream3/extractors/Vidmoly.kt at 5024ace2922a148d38df2a816b483b529789cf58 (GPL-3.0).

/** Pinned Vidmoly alias with its own service origin. */
class Vidmolyme : Vidmoly() {
    override fun getMainUrl(): String = "https://vidmoly.me"
}
