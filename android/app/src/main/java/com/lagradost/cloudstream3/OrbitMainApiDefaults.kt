package com.lagradost.cloudstream3

/** Default provider behavior copied from the pinned CloudStream MainAPI contract. */
internal object OrbitMainApiDefaults {
    /** Paginated search delegates to the legacy search overload and marks it non-paginated. */
    @JvmStatic
    suspend fun searchPage(api: MainAPI, query: String): SearchResponseList? {
        val results = api.search(query) ?: return null
        return SearchResponseList(results, false)
    }
}
