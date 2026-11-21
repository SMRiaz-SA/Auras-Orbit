package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Pinned CloudStream action contract, with lifecycle services supplied by Orbit. */
abstract class VideoClickAction {
    abstract val name: UiText
    open val oneSource: Boolean = false
    open val isPlayer: Boolean = false
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.values().toSet()
    var sourcePlugin: String? = null

    @Throws
    suspend fun <T> uiThread(callable: Callable<T>): T? = suspendCancellableCoroutine { continuation ->
        val activity = OrbitCs3HostServices.get().currentActivity
        if (activity == null) {
            continuation.resumeWithException(IllegalStateException("No foreground Orbit activity"))
        } else {
            activity.runOnUiThread {
                if (continuation.isActive) {
                    try { continuation.resume(callable.call()) }
                    catch (error: Throwable) { continuation.resumeWithException(error) }
                }
            }
        }
    }

    @Throws
    suspend fun launchResult(intent: Intent?, options: ActivityOptionsCompat? = null) {
        if (intent == null) return
        uiThread {
            val activity = OrbitCs3HostServices.get().currentActivity
            val host = activity as? OrbitVideoActionRuntime.ResultHost
                ?: throw IllegalStateException("The current screen cannot receive player results")
            host.launchVideoActionForResult(intent, options?.toBundle())
        }
    }

    @Throws
    suspend fun launch(intent: Intent?, bundle: Bundle? = null) {
        if (intent == null) return
        uiThread {
            val activity = OrbitCs3HostServices.get().currentActivity
                ?: throw IllegalStateException("No foreground Orbit activity")
            activity.startActivity(intent, bundle)
        }
    }

    fun uniqueId(): String = "$sourcePlugin:${this::class.qualifiedName}"
    @Throws
    abstract fun shouldShow(context: Context?, video: ResultEpisode?): Boolean
    fun shouldShowSafe(context: Context?, video: ResultEpisode?): Boolean = try {
        shouldShow(context, video)
    } catch (_: Throwable) { false }

    @Throws
    abstract suspend fun runAction(context: Context?, video: ResultEpisode,
        result: LinkLoadingResult, index: Int?)

    fun runActionSafe(context: Context?, video: ResultEpisode,
        result: LinkLoadingResult, index: Int?): Job = OrbitVideoActionRuntime.run(this) {
        try {
            runAction(context, video, result, index)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ActivityNotFoundException) {
            OrbitVideoActionRuntime.notice(context, "No installed app can open this source.")
        } catch (_: NotImplementedError) {
            OrbitVideoActionRuntime.notice(context, "This action has not been implemented by its provider.")
        } catch (_: Throwable) {
            OrbitVideoActionRuntime.notice(context, "The provider action could not be completed.")
        }
    }
}

/** Tracks active custom actions so plugin unload cancels their in-flight work. */
object OrbitVideoActionRuntime {
    interface ResultHost { fun launchVideoActionForResult(intent: Intent, options: Bundle?) }
    /** Internal player actions reuse Orbit's validated title/source handoff. */
    interface InternalPlayerHost {
        fun playMirror(action: VideoClickAction, video: ResultEpisode,
            result: LinkLoadingResult, index: Int)
        fun choosePlayer(action: VideoClickAction, video: ResultEpisode)
        fun isActionCurrent(action: VideoClickAction, video: ResultEpisode): Boolean
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Job, String>()

    internal fun run(action: VideoClickAction, block: suspend () -> Unit): Job {
        val hostServices = OrbitCs3HostServices.get()
        val owner = hostServices.getVideoClickActionPluginPath(action)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (!hostServices.isVideoClickActionAvailable(action)) {
                throw CancellationException("The action's plugin is no longer active")
            }
            block()
        }
        jobs[job] = owner
        job.invokeOnCompletion { jobs.remove(job) }
        // Register before starting so unload cannot miss a plugin-owned callback
        // that was launched directly through CloudStream's runActionSafe surface.
        // Ownerless Orbit actions keep their existing runtime-only lifecycle.
        if (owner.isNotEmpty()) hostServices.trackVideoActionJob(action, job)
        job.start()
        return job
    }

    @JvmStatic fun cancelPluginActions(pluginPath: String) {
        jobs.entries.filter { it.value == pluginPath }.forEach { it.key.cancel() }
    }

    internal fun notice(context: Context?, message: String) {
        val app = context?.applicationContext ?: return
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }
}
