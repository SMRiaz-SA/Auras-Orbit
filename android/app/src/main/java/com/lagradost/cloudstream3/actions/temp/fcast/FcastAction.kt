package com.lagradost.cloudstream3.actions.temp.fcast

import android.content.Context
import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.OrbitVideoActionRuntime
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Discovered-device sender using the pinned CloudStream FCast packet contract. */
class FcastAction : VideoClickAction() {
    override val name = UiText.DynamicString("Fcast to device")
    override val oneSource = true
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?) =
        context is Activity && context is OrbitVideoActionRuntime.InternalPlayerHost &&
            FcastManager.currentDevices.isNotEmpty()

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        val activity = context as? Activity ?: return
        val link = result.links.getOrNull(index ?: 0) ?: return
        val uri = URI(link.url)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null) {
            "FCast requires a direct HTTP stream"
        }
        require(link.url.length <= 16_384 && link.headers.size <= 64)
        val devices = FcastManager.currentDevices
        val device = chooseDevice(activity, devices) ?: return
        if (uiThread { (activity as? OrbitVideoActionRuntime.InternalPlayerHost)
                ?.isActionCurrent(this, video) == true } != true) return
        // A lost/replaced receiver is not a valid destination for this selection.
        if (FcastManager.currentDevices.none { it.rawName == device.rawName && it.host == device.host && it.port == device.port }) return
        val headers = linkedMapOf("referer" to link.referer, "user-agent" to MainAPIKt.USER_AGENT)
        headers.putAll(link.headers)
        require(headers.all { (name, value) -> name.length in 1..256 && value.length <= 8192 &&
            !name.contains('\r') && !name.contains('\n') && !value.contains('\r') && !value.contains('\n') })
        if (link.type !in sourceTypes) return
        val mime = link.type.getMimeType()
        withContext(Dispatchers.IO) {
            FcastSession(device.host ?: return@withContext, device.port).use { session ->
                session.sendMessage(Opcode.Play, PlayMessage(mime, link.url,
                    time = video.position.coerceAtLeast(0L) / 1000.0, headers = headers))
            }
        }
    }

    private suspend fun chooseDevice(activity: Activity, devices: List<PublicDeviceInfo>): PublicDeviceInfo? =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var dialog: AlertDialog? = null
            continuation.invokeOnCancellation { main.post { dialog?.dismiss() } }
            main.post {
                if (!continuation.isActive) return@post
                if (activity.isFinishing || activity.isDestroyed || devices.isEmpty()) {
                    continuation.resume(null)
                    return@post
                }
                dialog = AlertDialog.Builder(activity).setTitle("Choose cast device")
                    .setItems(devices.map { it.name }.toTypedArray()) { _, index ->
                        if (continuation.isActive) continuation.resume(devices.getOrNull(index))
                    }
                    .setNegativeButton("Cancel") { _, _ -> if (continuation.isActive) continuation.resume(null) }
                    .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                    .setOnDismissListener { if (continuation.isActive) continuation.resume(null) }
                    .create()
                dialog?.show()
            }
        }
}
