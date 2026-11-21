package com.lagradost.cloudstream3

open class MainAPI {
    open val name: String? = "Provider"
    open val hasMainPage: Boolean = true
    open val hasQuickSearch: Boolean = true
    open val supportedTypes: Set<TvType>? = setOf(TvType.Movie)
}

enum class TvType { Movie, TvSeries, Anime }
