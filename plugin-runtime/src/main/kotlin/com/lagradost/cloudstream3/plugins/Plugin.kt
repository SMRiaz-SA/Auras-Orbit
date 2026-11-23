package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import kotlin.Throws

abstract class Plugin : BasePlugin() {
    companion object {
        /**
         * Binary compatibility with Cloudstream Android's Compose-compiled Plugin API.
         * Recent plugin bytecode can read this compiler-generated stability field even
         * though the desktop host does not run Compose's compiler on this class.
         */
        @JvmField
        val `$stable`: Int = 8
    }

    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /**
     * Used to register VideoClickAction instances
     * @param element VideoClickAction you want to register
     */
    fun registerVideoClickAction(element: VideoClickAction) {
        Log.i(PLUGIN_TAG, "Adding ${element.name} VideoClickAction")
        element.sourcePlugin = this.filename
        synchronized(VideoClickActionHolder.allVideoClickActions) {
            VideoClickActionHolder.allVideoClickActions.add(element)
        }
    }

    /**
     * This will contain your resources if you specified requiresResources in gradle
     */
    var resources: Resources? = null

    /**
     * This will add a button in the settings allowing you to add custom settings
     */
    var openSettings: ((context: Context) -> Unit)? = null
}
