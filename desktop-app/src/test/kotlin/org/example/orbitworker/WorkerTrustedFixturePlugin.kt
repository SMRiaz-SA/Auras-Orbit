package org.example.orbitworker

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WorkerTrustedFixturePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Provider())
    }

    class Provider : MainAPI() {
        override var name = "Trusted Worker Fixture"
        override var mainUrl = "https://trusted-worker.invalid"

        override suspend fun search(query: String): List<SearchResponse> =
            listOf(newMovieSearchResponse("Trusted worker result", "$mainUrl/$query"))

        @Suppress("unused")
        fun executorReferenceForSecurityRegression(): ExecutorService = Executors.newSingleThreadExecutor()
    }
}
