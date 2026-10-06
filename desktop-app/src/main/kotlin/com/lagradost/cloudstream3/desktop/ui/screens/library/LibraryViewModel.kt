package com.lagradost.cloudstream3.desktop.ui.screens.library

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.di.AppContainerHolder
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.GetBookmarks
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.RemoveBookmark
import com.lagradost.cloudstream3.desktop.domain.bookmarks.interactor.ToggleBookmark
import com.lagradost.cloudstream3.desktop.domain.category.interactor.SetItemCategory
import com.lagradost.cloudstream3.desktop.domain.customlists.repository.CustomListsRepository
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.ui.base.BaseMviViewModel
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiState
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.SortOption
import com.lagradost.cloudstream3.desktop.ui.screens.library.transfer.LibraryArchive
import com.lagradost.cloudstream3.desktop.ui.screens.library.transfer.LibraryArchiveService
import com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType
import com.lagradost.runtime.executor.SafePluginInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class LibraryViewModel(
    private val getBookmarks: GetBookmarks = AppContainerHolder.container.getBookmarks,
    private val toggleBookmark: ToggleBookmark = AppContainerHolder.container.toggleBookmark,
    private val removeBookmark: RemoveBookmark = AppContainerHolder.container.removeBookmark,
    private val setItemCategory: SetItemCategory = AppContainerHolder.container.setItemCategory,
    private val customListsRepository: CustomListsRepository = AppContainerHolder.container.customListsRepository,
) : BaseMviViewModel<LibraryUiState, LibraryUiEvent, LibraryUiEffect>(
    initialState = LibraryUiState(),
) {
    private var reLinkSearchJob: Job? = null
    private val reLinkSearchGeneration = AtomicLong(0L)
    private var pendingImportArchive: LibraryArchive? = null
    private var pendingImportProfileId: Int? = null

    init {
        viewModelScope.launch {
            getBookmarks.subscribeAll().collect { bookmarksMap ->
                val allList = bookmarksMap.values.toList()
                val installed = APIHolder.allProviders.map { it.name }.toSet()
                val provMap = APIHolder.allProviders.associateBy { it.name }
                updateState {
                    val availableProvs = allList.map { it.apiName }.distinct().sorted()
                    val newSelectedProv = if (selectedProvider in availableProvs) selectedProvider else null
                    copy(
                        bookmarks = allList,
                        availableProviders = availableProvs,
                        selectedProvider = newSelectedProv,
                        installedProviderNames = installed,
                        providerMap = provMap,
                    ).applyFilters()
                }
            }
        }
        viewModelScope.launch {
            customListsRepository.subscribeActive().collect { snapshot ->
                updateState {
                    val selectedList = selectedCustomListId?.takeIf { id -> snapshot.lists.any { it.id == id } }
                    copy(
                        customLists = snapshot.lists,
                        customListItems = snapshot.items,
                        selectedCustomListId = selectedList,
                    ).applyFilters()
                }
            }
        }
        viewModelScope.launch {
            AppearanceConfig.posterWidthDp.collect { width ->
                updateState { copy(posterWidthDp = width) }
            }
        }
    }

    override fun handleEvent(event: LibraryUiEvent) {
        when (event) {
            is LibraryUiEvent.OnSelectTab -> selectTab(event.tab)
            is LibraryUiEvent.OnBookmarkClick -> handleBookmarkClick(event.bookmark)
            is LibraryUiEvent.OnDeleteBookmark -> deleteBookmark(event.bookmarkId)
            is LibraryUiEvent.OnDismissError -> dismissError()
            is LibraryUiEvent.OnSearchQueryChange -> updateState { copy(searchQuery = event.query).applyFilters() }
            is LibraryUiEvent.OnSortOptionChange -> updateState { copy(sortOption = event.sortOption).applyFilters() }
            is LibraryUiEvent.OnProviderFilterChange -> updateState { copy(selectedProvider = event.provider).applyFilters() }
            is LibraryUiEvent.OnStartReLink -> startReLink(event.bookmark)
            is LibraryUiEvent.OnSelectReLinkMatch -> selectReLinkMatch(event.bookmark, event.provider, event.match)
            is LibraryUiEvent.OnChangeWatchType -> changeWatchType(event.bookmarkId, event.newType)
            is LibraryUiEvent.OnSelectCustomList -> updateState { copy(selectedCustomListId = event.listId).applyFilters() }
            is LibraryUiEvent.OnCreateCustomList -> createCustomList(event.name, event.addBookmarkId)
            is LibraryUiEvent.OnRenameCustomList -> renameCustomList(event.listId, event.name)
            is LibraryUiEvent.OnDeleteCustomList -> deleteCustomList(event.listId)
            is LibraryUiEvent.OnPinCustomListToHome -> setPinnedToHome(event.listId, event.pinned)
            is LibraryUiEvent.OnSetBookmarkInCustomList -> setBookmarkInCustomList(event.listId, event.bookmarkId, event.included)
            is LibraryUiEvent.OnSearchGlobal -> searchGlobal(event.title)
            is LibraryUiEvent.OnDismissRecoveryModal -> dismissRecoveryModal()
            is LibraryUiEvent.OnExportLibrary -> exportLibrary(event.file, event.profileId)
            is LibraryUiEvent.OnImportLibraryFileSelected -> loadLibraryArchive(event.file, event.profileId)
            is LibraryUiEvent.OnConfirmLibraryImport -> confirmLibraryImport()
            is LibraryUiEvent.OnDismissLibraryImport -> dismissLibraryImport()
        }
    }

    private fun exportLibrary(file: File, profileId: Int) {
        if (uiState.value.isTransferring) return
        updateState { copy(isTransferring = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val written = LibraryArchiveService.export(profileId, file)
                sendEffect(LibraryUiEffect.ShowToast("Library exported to ${written.name}."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.e("Library archive export failed (${failure::class.simpleName})")
                sendEffect(LibraryUiEffect.ShowToast("Could not export the library file.", isError = true))
            } finally {
                updateState { copy(isTransferring = false) }
            }
        }
    }

    private fun loadLibraryArchive(file: File, profileId: Int) {
        if (uiState.value.isTransferring) return
        updateState { copy(isTransferring = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val validated = LibraryArchiveService.read(file)
                val profile = ProfileManager.profiles.value.firstOrNull { it.id == profileId }
                    ?: error("The target profile no longer exists")
                pendingImportArchive = validated.archive
                pendingImportProfileId = profileId
                updateState {
                    copy(
                        importPreview = validated.preview,
                        importTargetProfileName = profile.name,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.e("Library archive import validation failed (${failure::class.simpleName})")
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not read the library file.", isError = true))
            } finally {
                updateState { copy(isTransferring = false) }
            }
        }
    }

    private fun confirmLibraryImport() {
        val archive = pendingImportArchive ?: return
        val profileId = pendingImportProfileId ?: return
        if (uiState.value.isTransferring) return
        pendingImportArchive = null
        pendingImportProfileId = null
        updateState { copy(importPreview = null, importTargetProfileName = null, isTransferring = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(ProfileManager.profiles.value.any { it.id == profileId }) { "The target profile no longer exists" }
                val result = LibraryArchiveService.import(archive, profileId)
                AppContainerHolder.container.bookmarksRepository.refresh(profileId)
                val message = buildString {
                    append("Import complete: ${result.bookmarksAdded} titles added")
                    if (result.bookmarksSkipped > 0) append(", ${result.bookmarksSkipped} existing titles kept")
                    append("; ${result.historyAdded} progress records added")
                    if (result.historyUpdated > 0) append(", ${result.historyUpdated} updated")
                    if (result.historyUnchanged > 0) append(", ${result.historyUnchanged} newer local records kept")
                    append("; ${result.customListsAdded} lists added")
                    if (result.customListsMerged > 0) append(", ${result.customListsMerged} merged")
                    if (result.customListItemsAdded > 0) append(" and ${result.customListItemsAdded} list entries added")
                    append('.')
                }
                sendEffect(LibraryUiEffect.ShowToast(message))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.e("Library archive import failed (${failure::class.simpleName})")
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not import the library file.", isError = true))
            } finally {
                updateState { copy(isTransferring = false) }
            }
        }
    }

    private fun dismissLibraryImport() {
        pendingImportArchive = null
        pendingImportProfileId = null
        updateState { copy(importPreview = null, importTargetProfileName = null) }
    }

    private fun LibraryUiState.applyFilters(): LibraryUiState {
        val selectedListId = selectedCustomListId
        val selectedBookmarkIds = if (selectedListId == null) {
            null
        } else {
            customListItems.asSequence().filter { it.listId == selectedListId }.map { it.bookmarkId }.toSet()
        }
        var result = if (selectedBookmarkIds == null) {
            bookmarks.filter { it.watchType == selectedTab.id }
        } else {
            bookmarks.filter { it.id in selectedBookmarkIds }
        }

        if (selectedProvider != null) {
            result = result.filter { it.apiName == selectedProvider }
        }

        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        result = when (sortOption) {
            SortOption.DATE_ADDED_DESC -> result.sortedByDescending { it.dateAdded }
            SortOption.DATE_ADDED_ASC -> result.sortedBy { it.dateAdded }
            SortOption.ALPHA_ASC -> result.sortedBy { it.name.lowercase() }
            SortOption.ALPHA_DESC -> result.sortedByDescending { it.name.lowercase() }
        }

        return copy(filteredBookmarks = result)
    }

    private fun selectTab(tab: DesktopWatchType) {
        updateState {
            copy(selectedTab = tab, selectedCustomListId = null).applyFilters()
        }
    }

    private fun createCustomList(name: String, addBookmarkId: String? = null) {
        val profileId = ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val created = customListsRepository.create(name, profileId)
                if (addBookmarkId != null) {
                    check(customListsRepository.setBookmark(created.id, addBookmarkId, true, profileId)) {
                        "The title is no longer in your Library."
                    }
                }
                updateState { copy(selectedCustomListId = created.id).applyFilters() }
                sendEffect(LibraryUiEffect.ShowToast("Created list ${created.name}."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not create the list.", isError = true))
            }
        }
    }

    private fun renameCustomList(listId: String, name: String) {
        val profileId = ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(customListsRepository.rename(listId, name, profileId)) { "Could not rename the list." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not rename the list.", isError = true))
            }
        }
    }

    private fun deleteCustomList(listId: String) {
        val profileId = ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(customListsRepository.delete(listId, profileId)) { "Could not delete the list." }
                updateState {
                    copy(selectedCustomListId = selectedCustomListId?.takeUnless { it == listId }).applyFilters()
                }
                sendEffect(LibraryUiEffect.ShowToast("List deleted. Saved titles remain in your Library."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not delete the list.", isError = true))
            }
        }
    }

    private fun setBookmarkInCustomList(listId: String, bookmarkId: String, included: Boolean) {
        val profileId = ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(customListsRepository.setBookmark(listId, bookmarkId, included, profileId)) {
                    "The title or list is no longer available."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not update the list.", isError = true))
            }
        }
    }

    private fun setPinnedToHome(listId: String, pinned: Boolean) {
        val profileId = ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(customListsRepository.setPinnedToHome(listId, pinned, profileId)) {
                    "Could not update the Home shelf."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sendEffect(LibraryUiEffect.ShowToast(failure.message ?: "Could not update the Home shelf.", isError = true))
            }
        }
    }

    private fun handleBookmarkClick(bookmark: DesktopBookmark) {
        if (bookmark.apiName == com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher.NETWORK_STREAM_API_NAME) {
            com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher.playNetworkStreamBookmark(bookmark)
            return
        }
        val provider = APIHolder.allProviders.firstOrNull {
            it.name == bookmark.apiName && it.mainUrl.isNotBlank() && bookmark.url.startsWith(it.mainUrl)
        } ?: APIHolder.getApiFromNameNull(bookmark.apiName)
        if (provider != null) {
            sendEffect(LibraryUiEffect.Navigate(Config.Details(provider.name, bookmark.url, null, null, null, false)))
        } else {
            startReLink(bookmark)
        }
    }

    private fun startReLink(bookmark: DesktopBookmark) {
        val generation = reLinkSearchGeneration.incrementAndGet()
        updateState {
            copy(
                orphanRecoveryBookmark = bookmark,
                isSearchingMatches = true,
                matchedResults = emptyList(),
            )
        }
        reLinkSearchJob?.cancel()
        reLinkSearchJob = viewModelScope.launch(Dispatchers.IO) {
            val activeProviders = APIHolder.allProviders.filter { it.hasMainPage || it.supportedTypes.isNotEmpty() }
            val resultsList = CopyOnWriteArrayList<Pair<MainAPI, SearchResponse>>()

            val jobs = activeProviders.map { p ->
                launch {
                    try {
                        val searchRes = SafePluginInvoker.invokeOrNull(p.name, "search") {
                            p.search(bookmark.name)
                        }
                        val matches = searchRes?.filterIsInstance<SearchResponse>() ?: emptyList()
                        matches.take(3).forEach { resp ->
                            resultsList.add(p to resp)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
            }
            jobs.forEach { it.join() }

            updateState {
                if (generation == reLinkSearchGeneration.get() && orphanRecoveryBookmark?.id == bookmark.id) {
                    copy(
                        isSearchingMatches = false,
                        matchedResults = resultsList.toList(),
                    )
                } else {
                    this
                }
            }
        }
    }

    private fun selectReLinkMatch(bookmark: DesktopBookmark, newProvider: MainAPI, match: SearchResponse) {
        val profileId = com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = bookmark.copy(
                    apiName = newProvider.name,
                    url = match.url,
                    name = match.name,
                    posterUrl = match.posterUrl ?: bookmark.posterUrl,
                )
                toggleBookmark.saveBookmark(updated, profileId)
                updateState {
                    copy(
                        orphanRecoveryBookmark = null,
                        isSearchingMatches = false,
                        matchedResults = emptyList(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLogger.e("LibraryViewModel: Failed to save re-linked bookmark: ${e.message}")
            }
        }
    }

    private fun changeWatchType(bookmarkId: String, newType: DesktopWatchType) {
        val profileId = com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            setItemCategory.await(bookmarkId, newType.id, profileId)
        }
    }

    private fun searchGlobal(title: String) {
        updateState { copy(orphanRecoveryBookmark = null) }
        sendEffect(LibraryUiEffect.Navigate(Config.Search))
    }

    private fun dismissRecoveryModal() {
        reLinkSearchGeneration.incrementAndGet()
        reLinkSearchJob?.cancel()
        updateState {
            copy(
                orphanRecoveryBookmark = null,
                isSearchingMatches = false,
                matchedResults = emptyList(),
            )
        }
    }

    private fun deleteBookmark(bookmarkId: String) {
        val profileId = com.lagradost.cloudstream3.desktop.profile.ProfileManager.activeProfileId
        viewModelScope.launch(Dispatchers.IO) {
            removeBookmark.await(bookmarkId, profileId)
        }
    }

    private fun dismissError() {
        updateState { copy(showError = null) }
    }
}
