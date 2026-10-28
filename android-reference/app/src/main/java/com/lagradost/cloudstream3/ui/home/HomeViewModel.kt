package com.lagradost.cloudstream3.ui.home

import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.beginResumeWatchingSync
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.isResumeWatchingSyncCurrent
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.deleteAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal data class HomePageRequestToken(
    val generation: Long,
    val profileIdentity: String,
    val requestedSource: String?,
)

/** Synchronous request ordering so dispatch order cannot override the user's latest source choice. */
internal class HomePageRequestGate {
    private var generation = 0L
    private var latest: HomePageRequestToken? = null

    @Synchronized
    fun begin(profileIdentity: String, requestedSource: String?): HomePageRequestToken {
        val token = HomePageRequestToken(++generation, profileIdentity, requestedSource)
        latest = token
        return token
    }

    @Synchronized
    fun beginFromUiSelection(
        profileIdentity: String,
        requestedSource: String?,
        persistSource: (String) -> Unit,
    ): HomePageRequestToken {
        val token = HomePageRequestToken(++generation, profileIdentity, requestedSource)
        latest = token
        requestedSource?.let(persistSource)
        return token
    }

    @Synchronized
    fun current(): HomePageRequestToken? = latest

    @Synchronized
    fun isCurrent(token: HomePageRequestToken, profileIdentity: String): Boolean =
        latest == token && token.profileIdentity == profileIdentity

    @Synchronized
    fun commitIfCurrent(
        token: HomePageRequestToken,
        profileIdentity: String,
        action: () -> Unit,
    ): Boolean {
        if (!isCurrent(token, profileIdentity)) return false
        action()
        return true
    }
}

/** Read the persisted source only after entering the same lock used by UI source changes. */
internal fun <T> withStoredHomeSourceDispatch(
    dispatchLock: Any,
    readSource: () -> String?,
    dispatch: (String?) -> T,
): T = synchronized(dispatchLock) {
    dispatch(readSource())
}

class HomeViewModel : ViewModel() {
    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? {
            val resumeWatching = withContext(Dispatchers.IO) {
                getAllResumeStateIds()?.mapNotNull { id ->
                    getLastWatched(id)
                }?.sortedBy { -it.updateTime }
            }
            val resumeWatchingResult = withContext(Dispatchers.IO) {
                resumeWatching?.mapNotNull { resume ->
                    val headerCache = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        resume.parentId.toString()
                    )

                    val data = if (headerCache == null) {
                        // We store resume watching data in download header cache
                        // Because downloads automatically pruned outdated download headers we
                        // removed resume watching data. We should restore the data for affected users.
                        val oldData = getKey<DownloadObjects.DownloadHeaderCached>(
                            DOWNLOAD_HEADER_CACHE_BACKUP,
                            resume.parentId.toString()
                        ) ?: return@mapNotNull null

                        // Restore data
                        setKey(DOWNLOAD_HEADER_CACHE, resume.parentId.toString(), oldData)
                        oldData
                    } else {
                        headerCache
                    }

                    val watchPos = getViewPos(resume.episodeId)

                    DataStoreHelper.ResumeWatchingResult(
                        data.name,
                        data.url,
                        data.apiName,
                        data.type,
                        data.poster,
                        watchPos,
                        resume.episodeId,
                        resume.parentId,
                        resume.episode,
                        resume.season,
                        resume.isFromDownload,
                        posterHeaders = data.posterHeaders,
                        episodePosterUrl = resume.episodePoster,
                        episodePosterHeaders = resume.episodePosterHeaders,
                    )
                }
            }
            return resumeWatchingResult
        }
    }

    fun deleteResumeWatching() {
        deleteAllResumeStateIds()
        loadResumeWatching()
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
    }

    var repo: APIRepository? = null

    private val _apiName = MutableLiveData<String>()
    val apiName: LiveData<String> = _apiName

    private val _currentAccount = MutableLiveData<DataStoreHelper.Account?>()
    val currentAccount: MutableLiveData<DataStoreHelper.Account?> = _currentAccount

    private val _randomItems = MutableLiveData<List<SearchResponse>?>(null)
    val randomItems: LiveData<List<SearchResponse>?> = _randomItems

    private var currentShuffledList: List<SearchResponse> = listOf()

    private fun autoloadRepo(): APIRepository {
        return APIRepository(apis.withLock { apis.first { it.hasMainPage } })
    }

    private val _availableWatchStatusTypes =
        MutableLiveData<Pair<Set<WatchType>, Set<WatchType>>>()
    val availableWatchStatusTypes: LiveData<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes
    private val _bookmarks = MutableLiveData<Pair<Boolean, List<SearchResponse>>>()
    val bookmarks: LiveData<Pair<Boolean, List<SearchResponse>>> = _bookmarks

    private val _resumeWatching = MutableLiveData<List<SearchResponse>>()
    private val _preview = MutableLiveData<Resource<Pair<Boolean, List<LoadResponse>>>>()
    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = mutableSetOf<String>()

    val resumeWatching: LiveData<List<SearchResponse>> = _resumeWatching
    val preview: LiveData<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview

    private fun loadResumeWatching() {
        // Capture the selected profile and advance the request before the asynchronous reads
        // begin. A slower old snapshot must not supersede a later removal or account switch.
        val request = beginResumeWatchingSync(DataStoreHelper.currentAccount)
        viewModelScope.launchSafe {
            val resumeWatchingResult = getResumeWatching()
            if (!isResumeWatchingSyncCurrent(request, DataStoreHelper.currentAccount)) {
                return@launchSafe
            }
            if (isLayout(TV) && resumeWatchingResult != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ioSafe {
                    // this WILL crash on non tvs, so keep this inside a try catch
                    activity?.applicationContext?.addProgramsToContinueWatching(resumeWatchingResult, request)
                }
            }
            if (isResumeWatchingSyncCurrent(request, DataStoreHelper.currentAccount)) {
                resumeWatchingResult?.let { _resumeWatching.postValue(it) }
            }
        }
    }

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.postValue(setOf<WatchType>() to setOf())
            _bookmarks.postValue(Pair(false, ArrayList()))
            return@launchSafe
        }

        val watchPrefNotNull = preferredWatchStatus ?: EnumSet.of(currentWatchTypes.first())
        //if (currentWatchTypes.any { watchPrefNotNull.contains(it) }) watchPrefNotNull else listOf(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.postValue(

            watchPrefNotNull to
                    currentWatchTypes,

            )

        val list = withContext(Dispatchers.IO) {
            watchStatusIds.filter { watchPrefNotNull.contains(it.second) }
                .mapNotNull { getBookmarkedData(it.first) }
                .sortedBy { -it.latestUpdatedTime }
        }
        _bookmarks.postValue(Pair(true, list))
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null

    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
        var isLoadingMore: Boolean = false,
        var pageError: String? = null,
    )

    private val expandable: MutableMap<String, ExpandableHomepageList> = mutableMapOf()
    private val _page =
        MutableLiveData<Resource<Map<String, ExpandableHomepageList>>>(Resource.Loading())
    val page: LiveData<Resource<Map<String, ExpandableHomepageList>>> = _page

    private val pageRequestGate = HomePageRequestGate()
    private val pageLoadDispatchLock = Any()
    private val lock = ConcurrentHashMap.newKeySet<Pair<Long, String>>()
    private var repoRequest: HomePageRequestToken? = null
    private var expandableRequest: HomePageRequestToken? = null

    private fun isCurrentPageRequest(request: HomePageRequestToken): Boolean =
        pageRequestGate.isCurrent(request, DataStoreHelper.currentAccount)

    private fun commitPageRequest(request: HomePageRequestToken, action: () -> Unit): Boolean =
        pageRequestGate.commitIfCurrent(request, DataStoreHelper.currentAccount, action)

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        val request = pageRequestGate.current() ?: return null
        if (!isCurrentPageRequest(request)) return null
        if (repoRequest != request || expandableRequest != request) return null
        val generation = request.generation
        val current = expandable[name] ?: return null
        if (!current.hasNext) return current

        val requestKey = generation to name
        if (!lock.add(requestKey)) return current

        if (!commitPageRequest(request) {
                current.isLoadingMore = true
                current.pageError = null
                _page.postValue(Resource.Success(expandable.toMap()))
            }
        ) return null

        try {
            val currentRepo = repo?.takeIf { repoRequest == request } ?: return null

            currentRepo.waitForHomeDelay()
            if (!isCurrentPageRequest(request) || expandable[name] !== current) return null

            val catalogIndex = currentRepo.mainPage.indexOfFirst { it.name == name }
            if (catalogIndex < 0) {
                val committed = commitPageRequest(request) {
                    current.pageError = "This catalog is no longer available from the selected source."
                }
                return current.takeIf { committed }
            }

            val nextPage = current.currentPage + 1
            when (val result = currentRepo.getMainPage(nextPage, catalogIndex)) {
                is Resource.Success -> {
                    if (!commitPageRequest(request) {
                            if (expandable[name] === current) {
                                val response = result.value.filterNotNull().firstOrNull { page ->
                                    page.items.any { it.name == name }
                                }
                                val nextCatalog = response?.items?.firstOrNull { it.name == name }
                                if (response == null || nextCatalog == null) {
                                    current.pageError = "The source did not return this catalog page. Try again."
                                } else {
                                    current.list.list = CopyOnWriteArrayList(
                                        (current.list.list + nextCatalog.list).distinctBy { it.url }
                                    )
                                    current.currentPage = nextPage
                                    current.hasNext = response.hasNext
                                    current.pageError = null
                                }
                            }
                        }
                    ) {
                        return null
                    }
                }

                is Resource.Failure -> commitPageRequest(request) { current.pageError = result.errorString }
                is Resource.Loading -> commitPageRequest(request) {
                    current.pageError = "The catalog page could not be loaded. Try again."
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            commitPageRequest(request) {
                current.pageError = error.message ?: "The catalog page could not be loaded. Try again."
            }
        } finally {
            lock.remove(requestKey)
            commitPageRequest(request) {
                if (expandable[name] === current) {
                    current.isLoadingMore = false
                    _page.postValue(Resource.Success(expandable.toMap()))
                }
            }
        }

        return current.takeIf { isCurrentPageRequest(request) && expandable[name] === current }
    }

    // this is soo over engineered, but idk how I can make it clean without making the main api harder to use :pensive:
    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int,
        repository: APIRepository,
        request: HomePageRequestToken,
    ): Int {
        if (!isCurrentPageRequest(request)) return 0
        val addItems = shuffledList.asSequence()
            .filterNot { alreadyAdded.contains(it.url) }
            .take(size.coerceAtLeast(0))
            .toList()
        if (addItems.isEmpty()) return 0

        if (!commitPageRequest(request) { alreadyAdded.addAll(addItems.map { it.url }) }) return 0
        val add = addItems.amap { searchResponse ->
            repository.load(searchResponse.url)
        }.mapNotNull { if (it is Resource.Success) it.value else null }
        if (!commitPageRequest(request) { current.addAll(add) }) return 0
        return add.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        val request = pageRequestGate.current() ?: return
        val currentRepo = repo ?: return
        val shuffledList = currentShuffledList
        addJob = ioSafe {
            updatePreviewResponses(
                previewResponses,
                previewResponsesAdded,
                shuffledList,
                1,
                currentRepo,
                request,
            )
            commitPageRequest(request) {
                _preview.postValue(Resource.Success((previewResponsesAdded.size < shuffledList.size) to previewResponses))
            }
        }
    }

    private fun load(api: MainAPI, request: HomePageRequestToken): Job = ioSafe {
        if (!isCurrentPageRequest(request)) return@ioSafe
        val newRepo = APIRepository(api)
        if (!commitPageRequest(request) {
                repo = newRepo
                repoRequest = request
                _apiName.postValue(newRepo.name)
                _randomItems.postValue(listOf())
            }
        ) return@ioSafe
        //} else {
        //    autoloadRepo()
        //}

        if (!newRepo.hasMainPage) {
            commitPageRequest(request) {
                expandable.clear()
                expandableRequest = request
                previewResponses.clear()
                previewResponsesAdded.clear()
                currentShuffledList = emptyList()
                _page.postValue(Resource.Success(emptyMap()))
                _preview.postValue(Resource.Failure(false, "No homepage"))
                isCurrentlyLoadingName = null
            }
            return@ioSafe
        }

        if (!commitPageRequest(request) {
                _page.postValue(Resource.Loading())
                _preview.postValue(Resource.Loading())
            }
        ) return@ioSafe
        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        val data = newRepo.getMainPage(1, null)
        if (!isCurrentPageRequest(request)) return@ioSafe
        when (data) {
            is Resource.Success -> {
                try {
                    val newShelves = linkedMapOf<String, ExpandableHomepageList>()
                    data.value.forEach { home ->
                        home?.items?.forEach { list ->
                            val filteredList =
                                context?.filterHomePageListByFilmQuality(list) ?: list
                            newShelves[list.name] =
                                ExpandableHomepageList(
                                    filteredList.copy(
                                        list = CopyOnWriteArrayList(
                                            filteredList.list
                                        )
                                    ), 1, home.hasNext
                                )
                        }
                    }

                    val items = data.value.mapNotNull { it?.items }.flatten()
                    if (!commitPageRequest(request) {
                            expandable.clear()
                            expandable.putAll(newShelves)
                        expandableRequest = request
                            previewResponses.clear()
                            previewResponsesAdded.clear()
                            currentShuffledList = emptyList()
                            // Publish shelves before slower preview details finish loading.
                            _page.postValue(Resource.Success(expandable.toMap()))
                        }
                    ) return@ioSafe

                    //val home = data.value
                    if (items.isNotEmpty()) {
                        val currentList =
                            items.shuffled().filter { it.list.isNotEmpty() }
                                .flatMap { it.list }
                                .distinctBy { it.url }.toList()

                        if (currentList.isNotEmpty()) {
                            val randomItems =
                                context?.filterSearchResultByFilmQuality(currentList.shuffled())
                                    ?: currentList.shuffled()

                            updatePreviewResponses(
                                previewResponses,
                                previewResponsesAdded,
                                randomItems,
                                3,
                                newRepo,
                                request,
                            )

                            if (!commitPageRequest(request) {
                                    _randomItems.postValue(randomItems)
                                    currentShuffledList = randomItems
                                }
                            ) return@ioSafe
                        }
                    }
                    commitPageRequest(request) {
                        if (previewResponses.isEmpty()) {
                            _preview.postValue(
                                Resource.Failure(
                                    false,
                                    "No homepage responses"
                                )
                            )
                        } else {
                            _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    commitPageRequest(request) { _randomItems.postValue(emptyList()) }
                    logError(e)
                }
            }

            is Resource.Failure -> {
                commitPageRequest(request) {
                    _page.postValue(data)
                    _preview.postValue(data)
                }
            }

            else -> Unit
        }
        commitPageRequest(request) { isCurrentlyLoadingName = null }
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    private val _popup = MutableLiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: LiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.postValue(null)
        else
            _popup.postValue(list to deleteCallback)
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        loadAndCancelFromStoredSource(forceReload)
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancelFromStoredSource(false)
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancelFromStoredSource(true)
    }

    private fun loadAndCancelFromStoredSource(forceReload: Boolean) {
        withStoredHomeSourceDispatch(
            dispatchLock = pageLoadDispatchLock,
            readSource = { DataStoreHelper.currentHomePage },
            dispatch = { source -> loadAndCancel(source, forceReload) },
        )
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.postValue(
            getCurrentAccount()
        )
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount
    }

    override fun onCleared() {
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(list)
    }

    fun reloadStored() {
        loadResumeWatching()
        loadStoredData()
    }

    fun click(load: LoadClickCallback) {
        loadResult(load.response.url, load.response.apiName, load.response.name, load.action)
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ): Job? {
        val request = synchronized(pageLoadDispatchLock) {
            //println("trying to load $preferredApiName")
            // Since plugins are loaded in stages this function can get called multiple times.
            // The issue with this is that the homepage may be fetched multiple times while the first request is loading
            // api?.let { expandable[it.name]?.list?.list?.isNotEmpty() } == true
            val currentPage = page.value

            // if we don't need to reload and we have a valid homepage or currently loading the same thing then return
            val currentLoading = isCurrentlyLoadingName
            if (!forceReload && (currentPage is Resource.Success && currentPage.value.isNotEmpty() || (currentLoading != null && currentLoading == preferredApiName))) {
                return onGoingLoad
            }

            val profileIdentity = DataStoreHelper.currentAccount
            val token = if (fromUI) {
                pageRequestGate.beginFromUiSelection(profileIdentity, preferredApiName) {
                    DataStoreHelper.currentHomePage = it
                }
            } else {
                pageRequestGate.begin(profileIdentity, preferredApiName)
            }
            onGoingLoad?.cancel()
            onGoingLoad = null
            addJob?.cancel()
            isCurrentlyLoadingName = preferredApiName
            pageRequestGate.commitIfCurrent(token, DataStoreHelper.currentAccount) {
                repo = null
                repoRequest = null
                expandableRequest = null
                _page.postValue(Resource.Loading())
                _preview.postValue(Resource.Loading())
                _randomItems.postValue(emptyList())
            }
            token
        }

        return ioSafe {
            if (!isCurrentPageRequest(request)) return@ioSafe
            val api = getApiFromNameNull(preferredApiName)
            if (!isCurrentPageRequest(request)) return@ioSafe
            if (preferredApiName == noneApi.name) {
                // just set to random
                startLoad(noneApi, request)
            } else if (preferredApiName == randomApi.name) {
                // randomize the api, if none exist like if not loaded or not installed
                // then use nothing
                val validAPIs = context?.filterProviderByPreferredMedia()
                if (!isCurrentPageRequest(request)) return@ioSafe
                if (validAPIs.isNullOrEmpty()) {
                    startLoad(noneApi, request)
                } else {
                    val apiRandom = validAPIs.random()
                    if (fromUI && !commitPageRequest(request) {
                            DataStoreHelper.currentHomePage = apiRandom.name
                        }
                    ) return@ioSafe
                    startLoad(apiRandom, request)
                }
            } else if (api == null) {
                // API is not found aka not loaded or removed, post the loading
                // progress if waiting for plugins, otherwise nothing
                if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                    startLoad(noneApi, request)
                } else {
                    commitPageRequest(request) {
                        isCurrentlyLoadingName = null
                        _page.postValue(Resource.Loading())
                        if (preferredApiName != null) _apiName.postValue(preferredApiName)
                    }
                }
            } else {
                // fromUI sources were recorded with the request token before dispatch.
                startLoad(api, request)
            }
            if (isCurrentPageRequest(request)) reloadAccount()
        }
    }

    private fun startLoad(api: MainAPI, request: HomePageRequestToken) {
        synchronized(pageLoadDispatchLock) {
            if (!isCurrentPageRequest(request)) return
            isCurrentlyLoadingName = api.name
            onGoingLoad = load(api, request)
        }
    }
}
