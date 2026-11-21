package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.Context
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.lagradost.cloudstream3.actions.temp.BiglyBTPackage
import com.lagradost.cloudstream3.actions.temp.CopyClipboardAction
import com.lagradost.cloudstream3.actions.temp.JustPlayerPackage
import com.lagradost.cloudstream3.actions.temp.LibreTorrentPackage
import com.lagradost.cloudstream3.actions.temp.MpvExPackage
import com.lagradost.cloudstream3.actions.temp.MpvKtPackage
import com.lagradost.cloudstream3.actions.temp.MpvKtPreviewPackage
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.MpvRxPackage
import com.lagradost.cloudstream3.actions.temp.MpvYTDLPackage
import com.lagradost.cloudstream3.actions.temp.NextPlayerPackage
import com.lagradost.cloudstream3.actions.temp.OnlyPlayer
import com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction
import com.lagradost.cloudstream3.actions.temp.PlayMirrorAction
import com.lagradost.cloudstream3.actions.temp.ViewM3U8Action
import com.lagradost.cloudstream3.actions.temp.VlcNightlyPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.cloudstream3.actions.temp.WebVideoCastPackage
import com.lagradost.cloudstream3.actions.temp.fcast.FcastAction
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AtomicMutableList
import com.lagradost.cloudstream3.utils.UiText
import java.util.Collections
import java.util.IdentityHashMap

object VideoClickActionHolder {
    val allVideoClickActions: AtomicMutableList<VideoClickAction> = OrbitCs3HostServices.newVideoActionRegistry()
    private const val ACTION_ID_OFFSET = 1000
    private val orbitBuiltinActionIdentities = Collections.newSetFromMap(
        IdentityHashMap<VideoClickAction, Boolean>()
    )

    init {
        // This declaration order matches CloudStream commit 5024ace2922a148d38df2a816b483b529789cf58.
        // Keep hidden or unsupported entries in place: the numeric IDs are part of the plugin ABI.
        val builtins = listOf(
            PlayInBrowserAction(),
            CopyClipboardAction(),
            ViewM3U8Action(),
            PlayMirrorAction(),
            VlcPackage(),
            MpvPackage(),
            MpvExPackage(),
            NextPlayerPackage(),
            JustPlayerPackage(),
            FcastAction(),
            LibreTorrentPackage(),
            BiglyBTPackage(),
            VlcNightlyPackage(),
            WebVideoCastPackage(),
            MpvYTDLPackage(),
            MpvKtPackage(),
            MpvKtPreviewPackage(),
            OnlyPlayer(),
            MpvRxPackage(),
            AlwaysAskAction(),
        )
        allVideoClickActions.addAll(builtins)
        synchronized(allVideoClickActions) {
            orbitBuiltinActionIdentities.addAll(builtins)
        }
    }

    private fun snapshot(): List<VideoClickAction> = synchronized(allVideoClickActions) {
        allVideoClickActions.toList()
    }
    fun makeOptionMap(activity: Activity?, video: ResultEpisode): List<Pair<UiText, Int>> =
        snapshot().mapIndexed { index, action -> action to index + ACTION_ID_OFFSET }
            .filter { it.first.shouldShowSafe(activity, video) }.map { it.first.name to it.second }
    fun getActionById(id: Int): VideoClickAction? = snapshot().getOrNull(id - ACTION_ID_OFFSET)
    fun getByUniqueId(uniqueId: String): VideoClickAction? = snapshot().firstOrNull { it.uniqueId() == uniqueId }
    fun uniqueIdToId(uniqueId: String?): Int? = if (uniqueId == null) null else
        snapshot().indexOfFirst { it.uniqueId() == uniqueId }.takeIf { it >= 0 }?.plus(ACTION_ID_OFFSET)
    fun getPlayers(context: Context? = null): List<VideoClickAction> =
        snapshot().filter { it.isPlayer && it.shouldShowSafe(context, null) }

    /** Ownerless result callbacks may run only for these exact Orbit-created instances. */
    @JvmStatic
    fun isOrbitOwnedBuiltinAction(action: VideoClickAction?): Boolean {
        if (action == null || action.sourcePlugin != null
            || action.javaClass.classLoader !== VideoClickAction::class.java.classLoader) return false
        return synchronized(allVideoClickActions) { orbitBuiltinActionIdentities.contains(action) }
    }
}
