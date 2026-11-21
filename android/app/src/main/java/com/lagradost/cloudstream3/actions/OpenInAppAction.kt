package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.auras.orbit.R
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText
import java.io.File

/** Profile-scoped progress sink available only during one validated external-player callback. */
interface OpenInAppResultProgressContext {
    fun isStillCurrent(): Boolean
    fun record(position: Long, duration: Long)
}

/** Pinned CloudStream external-player action surface with Orbit-owned result persistence. */
abstract class OpenInAppAction(
    open val appName: UiText,
    open val packageName: String,
    private val intentClass: String? = null,
    private val action: String = Intent.ACTION_VIEW,
) : VideoClickAction() {
    override val name: UiText
        get() = UiText.StringResource(R.string.episode_action_play_in_format, listOf(appName))

    override val isPlayer: Boolean = true

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean =
        context?.let {
            try {
                it.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        } != false

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        if (context == null) return
        val intent = Intent(action).apply {
            setPackage(packageName)
            if (intentClass != null) component = ComponentName(packageName, intentClass)
        }
        putExtra(context, intent, video, result, index)
        launchResult(intent)
    }

    @Throws(Throwable::class)
    abstract suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    )

    @Throws(Throwable::class)
    abstract fun onResult(activity: Activity, intent: Intent?)

    /** Contains extension callback failures at the host boundary. */
    fun onResultSafe(activity: Activity, intent: Intent?) {
        try {
            onResult(activity, intent)
        } catch (error: Throwable) {
            Log.e(TAG, "External video-action result callback failed", error)
        }
    }

    companion object {
        private const val TAG = "OpenInAppAction"
        private val activeProgressContext = ThreadLocal<OpenInAppResultProgressContext?>()

        /** Dispatches only after Orbit has validated and leased the exact active owner generation. */
        @JvmStatic
        fun dispatchValidatedResult(
            action: OpenInAppAction,
            activity: Activity,
            intent: Intent?,
            progressContext: OpenInAppResultProgressContext,
        ) {
            val previous = activeProgressContext.get()
            activeProgressContext.set(progressContext)
            try {
                action.onResultSafe(activity, intent)
            } finally {
                if (previous == null) activeProgressContext.remove()
                else activeProgressContext.set(previous)
            }
        }

        internal fun updateProgressInCurrentCallback(position: Long, duration: Long) {
            if (position <= 0L || duration <= 0L) return
            val context = activeProgressContext.get() ?: return
            if (!context.isStillCurrent()) return
            context.record(position, duration)
        }
    }
}

/** Official helper for external players that accept a single direct link or an M3U8 playlist. */
fun makeTempM3U8Intent(context: Context, intent: Intent, result: LinkLoadingResult) {
    if (result.links.size == 1) {
        intent.setDataAndType(Uri.parse(result.links.first().url), "video/*")
        return
    }

    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    val outputFile = File.createTempFile("mirrorlist", ".m3u8", context.cacheDir)
    val text = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3")
        result.links.forEach { link ->
            append("\n#EXTINF:0,").append(link.name)
            append('\n').append(link.url)
        }
        append("\n#EXT-X-ENDLIST")
    }
    outputFile.writeText(text)
    intent.setDataAndType(
        FileProvider.getUriForFile(
            context,
            context.applicationContext.packageName + ".provider",
            outputFile,
        ),
        "application/x-mpegURL",
    )
}

/** Global signature retained for plugin ABI; Orbit writes only through a validated callback scope. */
fun updateDurationAndPosition(position: Long, duration: Long) {
    OpenInAppAction.updateProgressInCurrentCallback(position, duration)
}
