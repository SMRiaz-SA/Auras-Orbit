package com.lagradost.cloudstream3.ui

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus.REPEAT_MODE_REPEAT_OFF
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.media.uicontroller.UIController
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.sortUrls
import com.lagradost.cloudstream3.ui.player.LOADTYPE_CHROMECAST
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.ChromecastSubtitlesFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CastHelper
import com.lagradost.cloudstream3.utils.CastHelper.awaitLinks
import com.lagradost.cloudstream3.utils.CastHelper.getMediaInfo
import com.lagradost.cloudstream3.utils.CastHelper.toCastMetadataWindow
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONObject

private fun RemoteMediaClient.getItemIndex(): Int? {
    return try {
        val index = this.mediaQueue.itemIds.indexOf(this.currentItem?.itemId ?: 0)
        if (index < 0) null else index
    } catch (_: Exception) {
        null
    }
}

private fun RemoteMediaClient.currentQueueItemIdentity(): CastQueueItemIdentity? =
    currentItem?.let { item ->
        val media = item.media
        CastQueueItemIdentity(
            itemId = item.itemId,
            contentId = media?.contentId,
            customData = media?.customData?.toString(),
        )
    }

class SkipNextEpisodeController(val view: ImageView) : UIController() {
    init {
        view.setImageResource(R.drawable.ic_baseline_skip_next_24)
        view.setOnClickListener {
            remoteMediaClient?.let {
                it.queueNext(JSONObject())
                view.visibility = GONE // TO PREVENT MULTI CLICK
            }
        }
    }

    override fun onMediaStatusUpdated() {
        super.onMediaStatusUpdated()
        view.visibility = GONE
        val currentIdIndex = remoteMediaClient?.getItemIndex() ?: return
        val itemCount = remoteMediaClient?.mediaQueue?.itemCount ?: return
        if (itemCount - currentIdIndex > 1 && remoteMediaClient?.isLoadingNextItem == false) {
            view.visibility = VISIBLE
        }
    }
}

@Serializable
data class MetadataHolder(
    @JsonProperty("apiName") @SerialName("apiName") val apiName: String,
    @JsonProperty("isMovie") @SerialName("isMovie") val isMovie: Boolean,
    @JsonProperty("title") @SerialName("title") val title: String?,
    @JsonProperty("poster") @SerialName("poster") val poster: String?,
    @JsonProperty("currentEpisodeIndex") @SerialName("currentEpisodeIndex") val currentEpisodeIndex: Int,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: List<ResultEpisode>,
    @JsonProperty("currentLinks") @SerialName("currentLinks") val currentLinks: List<ExtractorLink>,
    @JsonProperty("currentSubtitles") @SerialName("currentSubtitles") val currentSubtitles: List<SubtitleData>,
    @JsonProperty("episodeSequenceId") @SerialName("episodeSequenceId") val episodeSequenceId: String? = null,
    @JsonProperty("episodeOffset") @SerialName("episodeOffset") val episodeOffset: Int = 0,
)

class SelectSourceController(val view: ImageView, val activity: ControllerActivity) : UIController() {
    init {
        view.setImageResource(R.drawable.ic_baseline_playlist_play_24)
        view.setOnClickListener {
            val holder = getCurrentMetaData()
            if (holder != null) {
                val items = holder.currentLinks
                if (items.isNotEmpty() && remoteMediaClient?.currentItem != null) {
                    val subTracks = remoteMediaClient?.mediaInfo?.mediaTracks?.filter { it.type == MediaTrack.TYPE_TEXT }
                        ?: ArrayList()

                    val bottomSheetDialogBuilder = AlertDialog.Builder(view.context, R.style.AlertDialogCustomBlack)
                    bottomSheetDialogBuilder.setView(R.layout.sort_bottom_sheet)

                    val bottomSheetDialog = bottomSheetDialogBuilder.create()
                    bottomSheetDialog.show()

                    val providerList = bottomSheetDialog.findViewById<ListView>(R.id.sort_providers)!!
                    val subtitleList = bottomSheetDialog.findViewById<ListView>(R.id.sort_subtitles)!!
                    if (subTracks.isEmpty()) {
                        bottomSheetDialog.findViewById<LinearLayout>(R.id.sort_subtitles_holder)?.visibility = GONE
                    } else {
                        val arrayAdapter = ArrayAdapter<String>(view.context, R.layout.sort_bottom_single_choice)
                        arrayAdapter.add(view.context.getString(R.string.no_subtitles))
                        arrayAdapter.addAll(subTracks.mapNotNull { it.name })

                        subtitleList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                        subtitleList.adapter = arrayAdapter

                        val currentTracks = remoteMediaClient?.mediaStatus?.activeTrackIds
                        val subtitleIndex = if (currentTracks == null) 0 else subTracks.map { it.id }
                            .indexOfFirst { currentTracks.contains(it) } + 1

                        subtitleList.setSelection(subtitleIndex)
                        subtitleList.setItemChecked(subtitleIndex, true)

                        subtitleList.setOnItemClickListener { _, _, which, _ ->
                            if (which == 0) {
                                remoteMediaClient?.setActiveMediaTracks(longArrayOf()) // NO SUBS
                            } else {
                                ChromecastSubtitlesFragment.getCurrentSavedStyle().apply {
                                    val font = TextTrackStyle()
                                    font.setFontFamily(fontFamily ?: "Google Sans")
                                    fontGenericFamily?.let { font.fontGenericFamily = it }
                                    font.windowColor = windowColor
                                    font.backgroundColor = backgroundColor

                                    font.edgeColor = edgeColor
                                    font.edgeType = edgeType
                                    font.foregroundColor = foregroundColor
                                    font.fontScale = fontScale

                                    remoteMediaClient?.setTextTrackStyle(font)
                                }

                                remoteMediaClient?.setActiveMediaTracks(longArrayOf(subTracks[which - 1].id))
                                    ?.setResultCallback {
                                        if (!it.status.isSuccess) {
                                            Log.e(
                                                "CHROMECAST", "Failed with status code:" +
                                                    it.status.statusCode + " > " + it.status.statusMessage
                                            )
                                        }
                                    }
                            }
                            bottomSheetDialog.dismissSafe(activity)
                        }
                    }

                    // https://developers.google.com/cast/docs/reference/web_receiver/cast.framework.messages.MediaInformation
                    val contentUrl = (remoteMediaClient?.currentItem?.media?.contentUrl
                        ?: remoteMediaClient?.currentItem?.media?.contentId)

                    val sortingMethods = items.map { "${it.name} ${Qualities.getStringByInt(it.quality)}" }
                        .toTypedArray()
                    val sotringIndex = items.indexOfFirst { it.url == contentUrl }

                    val arrayAdapter = ArrayAdapter<String>(view.context, R.layout.sort_bottom_single_choice)
                    arrayAdapter.addAll(sortingMethods.toMutableList())

                    providerList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                    providerList.adapter = arrayAdapter
                    providerList.setSelection(sotringIndex)
                    providerList.setItemChecked(sotringIndex, true)

                    providerList.setOnItemClickListener { _, _, which, _ ->
                        val epData = holder.episodes[holder.currentEpisodeIndex]
                        fun loadMirror(index: Int) {
                            if (holder.currentLinks.size <= index) return
                            val mediaItem = getMediaInfo(
                                epData,
                                holder,
                                index,
                                remoteMediaClient?.mediaInfo?.customData,
                                holder.currentSubtitles,
                            )

                            val startAt = remoteMediaClient?.approximateStreamPosition ?: 0
                            try { // THIS IS VERY IMPORTANT BECAUSE WE NEVER WANT TO AUTOLOAD THE NEXT EPISODE
                                val currentIdIndex = remoteMediaClient?.getItemIndex()
                                val nextId = remoteMediaClient?.mediaQueue?.itemIds?.get(
                                    currentIdIndex?.plus(1) ?: 0
                                )

                                if (currentIdIndex == null && nextId != null) {
                                    awaitLinks(
                                        remoteMediaClient?.queueInsertAndPlayItem(
                                            MediaQueueItem.Builder(mediaItem).build(),
                                            nextId,
                                            startAt,
                                            JSONObject(),
                                        )
                                    ) { loadMirror(index + 1) }
                                } else {
                                    val mediaLoadOptions =
                                        MediaLoadOptions.Builder()
                                            .setPlayPosition(startAt)
                                            .setAutoplay(true)
                                            .build()
                                    awaitLinks(
                                        remoteMediaClient?.load(
                                            mediaItem,
                                            mediaLoadOptions
                                        )
                                    ) { loadMirror(index + 1) }
                                }
                            } catch (_: Exception) {
                                val mediaLoadOptions =
                                    MediaLoadOptions.Builder()
                                        .setPlayPosition(startAt)
                                        .setAutoplay(true)
                                        .build()
                                awaitLinks(remoteMediaClient?.load(mediaItem, mediaLoadOptions)) {
                                    loadMirror(index + 1)
                                }
                            }
                        }

                        loadMirror(which)
                        bottomSheetDialog.dismissSafe(activity)
                    }
                }
            }
        }
    }

    private val pendingSequenceRestores = mutableSetOf<String>()
    private val failedSequenceRestores = mutableSetOf<String>()

    private fun getCurrentMetaData(): MetadataHolder? {
        return try {
            val data = remoteMediaClient?.mediaInfo?.customData?.toString() ?: return null
            val parsed = parseJson<MetadataHolder>(data)
            val accountId = DataStoreHelper.currentAccount
            val profileToken = DataStoreHelper.getCurrentAccountProfileToken()
            // Unbound legacy metadata has no trustworthy profile owner, so it must not
            // hydrate provider headers or write resume progress under the selected profile.
            val sequenceId = parsed.episodeSequenceId ?: return null
            val knownOwner = CastHelper.getCastEpisodeSequenceProfileBinding(sequenceId)
            if (knownOwner != null && !CastHelper.isCastEpisodeSequenceProfileAllowed(knownOwner, accountId, profileToken)) {
                return null
            }
            // Ownership is checked directly each time, so switching back from profile B
            // to owner A immediately makes this sequence eligible again.
            val fullSequence = CastHelper.getCachedCastEpisodeSequence(sequenceId, accountId, profileToken)
            if (fullSequence == null) {
                val failureKey = "$sequenceId/$accountId/$profileToken"
                if (failureKey in failedSequenceRestores) return null

                if (sequenceId !in pendingSequenceRestores) {
                    pendingSequenceRestores.add(sequenceId)
                    activity.ioSafe {
                        val restored = CastHelper.restoreCastEpisodeSequence(
                            context = activity,
                            accountId = accountId,
                            profileToken = profileToken,
                            apiName = parsed.apiName,
                            sequenceId = sequenceId,
                            protectedParentIds = parsed.episodes
                                .asSequence()
                                .drop(parsed.currentEpisodeIndex.coerceAtLeast(0))
                                .map { it.parentId }
                                .distinct()
                                .take(16)
                                .toList(),
                        )
                        activity.main {
                            pendingSequenceRestores.remove(sequenceId)
                            val owner = CastHelper.getCastEpisodeSequenceProfileBinding(sequenceId)
                            val activeAccountId = DataStoreHelper.currentAccount
                            val activeProfileToken = DataStoreHelper.getCurrentAccountProfileToken()
                            val belongsToActiveProfile = owner?.let {
                                CastHelper.isCastEpisodeSequenceProfileAllowed(
                                    it,
                                    activeAccountId,
                                    activeProfileToken,
                                )
                            } == true
                            if (owner != null && !belongsToActiveProfile) {
                                Log.e("CHROMECAST", "Cast playback belongs to another profile; ignoring its progress.")
                            } else if (restored == null && owner == null &&
                                activeAccountId == accountId && activeProfileToken == profileToken
                            ) {
                                failedSequenceRestores.add(failureKey)
                                Log.e("CHROMECAST", "Local Cast episode sequence could not be restored.")
                                Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                            }
                            val activeSequenceId = remoteMediaClient?.mediaInfo?.customData?.toString()
                                ?.let { runCatching { parseJson<MetadataHolder>(it).episodeSequenceId }.getOrNull() }
                            if (activeSequenceId == sequenceId && belongsToActiveProfile) {
                                onMediaStatusUpdated()
                            }
                        }
                    }
                }
                return null
            }

            val absoluteIndex = parsed.episodeOffset + parsed.currentEpisodeIndex
            if (absoluteIndex !in fullSequence.indices) {
                return null
            }
            parsed.copy(
                episodes = fullSequence,
                currentEpisodeIndex = absoluteIndex,
                episodeOffset = 0,
            )
        } catch (_: Exception) {
            null
        }
    }

    var isLoadingMore = false
    private var loadingMoreClient: RemoteMediaClient? = null
    private var loadingMoreIdentity: CastQueueItemIdentity? = null

    override fun onMediaStatusUpdated() {
        super.onMediaStatusUpdated()
        val meta = getCurrentMetaData()
        view.visibility = if ((meta?.currentLinks?.size ?: 0) > 1) VISIBLE else INVISIBLE

        try {
            if (meta != null) {
                val currentIndex = meta.currentEpisodeIndex
                val currentEpisode = meta.episodes.getOrNull(currentIndex)

                try {
                    val currentDuration = remoteMediaClient?.streamDuration
                    val currentPosition = remoteMediaClient?.approximateStreamPosition
                    val knownNextEpisode = getNextCastMetadataEpisode(meta.episodes, currentIndex)
                    if (currentEpisode != null && currentDuration != null && currentDuration > 0L &&
                        currentPosition != null && currentPosition >= 0L
                    ) {
                        DataStoreHelper.setViewPosAndResume(
                            currentEpisode.id,
                            currentPosition,
                            currentDuration,
                            currentEpisode,
                            knownNextEpisode,
                            isNextEpisodeKnown = knownNextEpisode != null,
                        )
                    }
                } catch (t: Throwable) {
                    logError(t)
                }

                if (currentEpisode != null && currentIndex < meta.episodes.lastIndex) {
                    val initiatingSequenceId = meta.episodeSequenceId ?: return
                    val initiatingOwner = CastHelper.getCastEpisodeSequenceProfileBinding(initiatingSequenceId)
                        ?: return
                    val initiatingAccountId = DataStoreHelper.currentAccount
                    val initiatingProfileToken = DataStoreHelper.getCurrentAccountProfileToken()
                    if (!isCastQueueSequenceOwnerCurrent(
                            initiatingSequenceId,
                            initiatingSequenceId,
                            initiatingOwner,
                            initiatingOwner,
                            initiatingAccountId,
                            initiatingProfileToken,
                        )
                    ) return

                    val initiatingClient = remoteMediaClient ?: return
                    val initiatingIdentity = initiatingClient.currentQueueItemIdentity() ?: return
                    val currentIdIndex = initiatingClient.getItemIndex() ?: return
                    val itemCount = initiatingClient.mediaQueue.itemCount

                    // A prior lookup can still be running after Cast changes media. Let the new
                    // item start its own lookup; the old lookup is guarded again before append.
                    if (isLoadingMore &&
                        (loadingMoreClient !== initiatingClient || loadingMoreIdentity != initiatingIdentity)
                    ) {
                        isLoadingMore = false
                        loadingMoreClient = null
                        loadingMoreIdentity = null
                    }

                    if (itemCount - currentIdIndex == 1 && !isLoadingMore) {
                        isLoadingMore = true
                        loadingMoreClient = initiatingClient
                        loadingMoreIdentity = initiatingIdentity
                        val index = currentIndex + 1
                        val epData = meta.episodes[index]
                        ioSafe {
                            try {
                                if (!isCastQueueSequenceOwnerCurrent(
                                        initiatingSequenceId,
                                        initiatingSequenceId,
                                        initiatingOwner,
                                        CastHelper.getCastEpisodeSequenceProfileBinding(initiatingSequenceId),
                                        DataStoreHelper.currentAccount,
                                        DataStoreHelper.getCurrentAccountProfileToken(),
                                    )
                                ) return@ioSafe

                                val currentLinks = mutableSetOf<ExtractorLink>()
                                val currentSubs = mutableSetOf<SubtitleData>()
                                val generator = RepoLinkGenerator(listOf(epData))
                                val isSuccessful = safeApiCall {
                                    generator.generateLinks(
                                        clearCache = false,
                                        sourceTypes = LOADTYPE_CHROMECAST,
                                        callback = {
                                            it.first?.let { link ->
                                                currentLinks.add(link)
                                            }
                                        }, subtitleCallback = {
                                            currentSubs.add(it)
                                        },
                                        offset = 0,
                                        isCasting = true,
                                    )
                                }

                                if (isSuccessful != Resource.Success(true) || currentLinks.isEmpty()) {
                                    return@ioSafe
                                }

                                val sortedLinks = sortUrls(currentLinks)
                                val sortedSubs = sortSubs(currentSubs)
                                val jsonCopy = meta.copy(
                                    currentLinks = sortedLinks,
                                    currentSubtitles = sortedSubs,
                                    currentEpisodeIndex = index,
                                    episodes = meta.episodes.map { it.copy(posterHeaders = null) },
                                )

                                val queueMetadata = jsonCopy.toCastMetadataWindow()
                                if (queueMetadata == null) {
                                    Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show()
                                    return@ioSafe
                                }
                                val done = JSONObject(queueMetadata.toJson())
                                val mediaInfo = getMediaInfo(
                                    epData,
                                    jsonCopy,
                                    0,
                                    done,
                                    sortedSubs,
                                )
                                val initiatingJob = currentCoroutineContext()[Job] ?: return@ioSafe
                                initiatingJob.ensureActive()
                                val appendStatus = withCastQueueAppendSettled {
                                    val appendResult = enqueueCastAppendOnDispatcherIfActive(
                                        initiatingJob = initiatingJob,
                                        dispatcher = Dispatchers.Main,
                                        isTargetCurrent = {
                                            val currentClient = remoteMediaClient
                                            val currentIdentity = currentClient?.currentQueueItemIdentity()
                                            val currentItemIndex = currentClient?.getItemIndex()
                                            val currentItemCount = currentClient?.mediaQueue?.itemCount
                                            val currentSequenceId = currentClient?.mediaInfo?.customData?.toString()
                                                ?.let {
                                                    runCatching { parseJson<MetadataHolder>(it).episodeSequenceId }
                                                        .getOrNull()
                                                }
                                            val currentOwner = currentSequenceId?.let {
                                                CastHelper.getCastEpisodeSequenceProfileBinding(it)
                                            }
                                            isSameCastQueueTarget(
                                                initiatingClient = initiatingClient,
                                                currentClient = currentClient,
                                                initiatingItem = initiatingIdentity,
                                                currentItem = currentIdentity,
                                            ) && currentItemIndex != null && currentItemCount != null &&
                                                currentItemCount - currentItemIndex == 1 &&
                                                isCastQueueSequenceOwnerCurrent(
                                                    initiatingSequenceId,
                                                    currentSequenceId,
                                                    initiatingOwner,
                                                    currentOwner,
                                                    DataStoreHelper.currentAccount,
                                                    DataStoreHelper.getCurrentAccountProfileToken(),
                                                )
                                        },
                                    ) {
                                        initiatingClient.queueAppendItem(
                                            MediaQueueItem.Builder(mediaInfo).build(),
                                            JSONObject(),
                                        )
                                    } ?: return@withCastQueueAppendSettled null
                                    withContext(Dispatchers.IO) { appendResult.await() }
                                }
                                if (appendStatus != null && !appendStatus.status.isSuccess) {
                                    println("FAILED TO LOAD NEXT ITEM")
                                }
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main) {
                                    if (loadingMoreClient === initiatingClient &&
                                        loadingMoreIdentity == initiatingIdentity
                                    ) {
                                        isLoadingMore = false
                                        loadingMoreClient = null
                                        loadingMoreIdentity = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println(e)
        }
    }

    override fun onSessionConnected(castSession: CastSession) {
        super.onSessionConnected(castSession)
        remoteMediaClient?.queueSetRepeatMode(REPEAT_MODE_REPEAT_OFF, JSONObject())
    }
}

class SkipTimeController(val view: ImageView, forwards: Boolean) : UIController() {
    init {
        val time = 30
        view.setImageResource(if (forwards) R.drawable.go_forward_30 else R.drawable.go_back_30)
        view.contentDescription = view.context.getString(
            if (forwards) R.string.go_forward_30 else R.string.go_back_30
        )
        view.setOnClickListener {
            remoteMediaClient?.let {
                val options = MediaSeekOptions.Builder()
                    .setPosition(it.approximateStreamPosition + time * 1000 * if (forwards) 1 else -1)
                it.seek(options.build())
            }
        }
    }
}

class ControllerActivity : ExpandedControllerActivity() {
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.cast_expanded_controller_menu, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourcesButton: ImageView = getButtonImageViewAt(0)
        val skipBackButton: ImageView = getButtonImageViewAt(1)
        val skipForwardButton: ImageView = getButtonImageViewAt(2)
        val skipOpButton: ImageView = getButtonImageViewAt(3)
        uiMediaController.bindViewToUIController(
            sourcesButton,
            SelectSourceController(sourcesButton, this)
        )
        uiMediaController.bindViewToUIController(
            skipBackButton,
            SkipTimeController(skipBackButton, false)
        )
        uiMediaController.bindViewToUIController(
            skipForwardButton,
            SkipTimeController(skipForwardButton, true)
        )
        uiMediaController.bindViewToUIController(
            skipOpButton,
            SkipNextEpisodeController(skipOpButton)
        )
    }
}
