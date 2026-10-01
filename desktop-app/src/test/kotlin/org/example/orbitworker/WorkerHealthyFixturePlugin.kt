package org.example.orbitworker

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin

class WorkerHealthyFixturePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Provider())
    }

    class Provider : MainAPI() {
        override var name = "Healthy Worker Fixture"
        override var mainUrl = "https://healthy-worker.invalid"

        override suspend fun search(query: String): List<SearchResponse> =
            listOf(newMovieSearchResponse("Healthy worker result", "$mainUrl/$query"))
    }
}
