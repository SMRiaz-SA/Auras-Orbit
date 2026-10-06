package com.lagradost.cloudstream3.desktop.ui.screens.library.contract

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.base.UiEvent
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopWatchType
import java.io.File

sealed interface LibraryUiEvent : UiEvent {
    data class OnSelectTab(val tab: DesktopWatchType) : LibraryUiEvent
    data class OnBookmarkClick(val bookmark: DesktopBookmark) : LibraryUiEvent
    data class OnDeleteBookmark(val bookmarkId: String) : LibraryUiEvent
    data object OnDismissError : LibraryUiEvent
    data class OnSearchQueryChange(val query: String) : LibraryUiEvent
    data class OnSortOptionChange(val sortOption: SortOption) : LibraryUiEvent
    data class OnProviderFilterChange(val provider: String?) : LibraryUiEvent
    data class OnStartReLink(val bookmark: DesktopBookmark) : LibraryUiEvent
    data class OnSelectReLinkMatch(val bookmark: DesktopBookmark, val provider: MainAPI, val match: SearchResponse) : LibraryUiEvent
    data class OnChangeWatchType(val bookmarkId: String, val newType: DesktopWatchType) : LibraryUiEvent
    data class OnSelectCustomList(val listId: String?) : LibraryUiEvent
    data class OnCreateCustomList(val name: String, val addBookmarkId: String? = null) : LibraryUiEvent
    data class OnRenameCustomList(val listId: String, val name: String) : LibraryUiEvent
    data class OnDeleteCustomList(val listId: String) : LibraryUiEvent
    data class OnPinCustomListToHome(val listId: String, val pinned: Boolean) : LibraryUiEvent
    data class OnSetBookmarkInCustomList(val listId: String, val bookmarkId: String, val included: Boolean) : LibraryUiEvent
    data class OnSearchGlobal(val title: String) : LibraryUiEvent
    data object OnDismissRecoveryModal : LibraryUiEvent
    data class OnExportLibrary(val file: File, val profileId: Int) : LibraryUiEvent
    data class OnImportLibraryFileSelected(val file: File, val profileId: Int) : LibraryUiEvent
    data object OnConfirmLibraryImport : LibraryUiEvent
    data object OnDismissLibraryImport : LibraryUiEvent
}
