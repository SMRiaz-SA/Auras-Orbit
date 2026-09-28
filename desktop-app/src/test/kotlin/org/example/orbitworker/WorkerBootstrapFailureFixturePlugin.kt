package org.example.orbitworker

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin

class WorkerBootstrapFailureFixturePlugin : Plugin() {
    override fun load(context: Context) {
        throw IllegalStateException("worker bootstrap fixture failed")
    }
}
