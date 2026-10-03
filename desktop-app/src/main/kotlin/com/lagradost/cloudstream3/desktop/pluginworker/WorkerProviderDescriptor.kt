package com.lagradost.cloudstream3.desktop.pluginworker

import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.syncproviders.SyncIdName

/** Serializable host-facing metadata for a provider owned by a plugin worker process. */
internal data class WorkerProviderDescriptor(
    val className: String,
    val name: String,
    val mainUrl: String,
    val storedCredentials: String?,
    val canBeOverridden: Boolean,
    val sequentialMainPage: Boolean,
    val sequentialMainPageDelay: Long,
    val sequentialMainPageScrollDelay: Long,
    val lang: String,
    val instantLinkLoading: Boolean,
    val hasChromecastSupport: Boolean,
    val hasDownloadSupport: Boolean,
    val usesWebView: Boolean,
    val hasMainPage: Boolean,
    val hasQuickSearch: Boolean,
    val loadLinksTimeoutMs: Long?,
    val getMainPageTimeoutMs: Long?,
    val searchTimeoutMs: Long?,
    val quickSearchTimeoutMs: Long?,
    val loadTimeoutMs: Long?,
    val supportedSyncNames: Set<SyncIdName>,
    val supportedTypes: Set<TvType>,
    val vpnStatus: VPNStatus,
    val providerType: ProviderType,
    val mainPage: List<MainPageData>,
)

/** Stable source identity returned to desktop settings without exposing plugin classes. */
internal data class WorkerPluginSourceDescriptor(
    val id: String,
    val name: String,
)
