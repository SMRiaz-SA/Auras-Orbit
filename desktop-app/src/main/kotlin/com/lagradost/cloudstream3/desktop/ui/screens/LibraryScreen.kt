package com.lagradost.cloudstream3.desktop.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.profile.ProfileManager
import com.lagradost.cloudstream3.desktop.ui.components.AppToastManager
import com.lagradost.cloudstream3.desktop.ui.components.GlobalContextMenuState
import com.lagradost.cloudstream3.desktop.ui.navigation.Config
import com.lagradost.cloudstream3.desktop.ui.screens.library.LibraryViewModel
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.BookmarkCard
import com.lagradost.cloudstream3.desktop.ui.screens.library.components.LibraryActionBar
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEffect
import com.lagradost.cloudstream3.desktop.ui.screens.library.contract.LibraryUiEvent
import com.lagradost.cloudstream3.desktop.ui.screens.library.dialogs.LibraryRecoveryDialog
import com.lagradost.cloudstream3.desktop.utils.NativeFileDialog
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopCustomList
import com.lagradost.common.storage.DesktopWatchType
import java.awt.FileDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeLibraryScreen(
    onNavigate: (Config) -> Unit,
    viewModel: LibraryViewModel,
) {
    var transferMenuExpanded by remember { mutableStateOf(false) }
    var showExportPrivacyDialog by remember { mutableStateOf(false) }
    var exportProfileId by remember { mutableStateOf<Int?>(null) }
    var listsMenuExpanded by remember { mutableStateOf(false) }
    var bookmarkForLists by remember { mutableStateOf<DesktopBookmark?>(null) }
    var showCreateListDialog by remember { mutableStateOf(false) }
    var createListForBookmarkId by remember { mutableStateOf<String?>(null) }
    var createListName by remember { mutableStateOf("") }
    var showManageListsDialog by remember { mutableStateOf(false) }
    var renameListTarget by remember { mutableStateOf<DesktopCustomList?>(null) }
    var renameListName by remember { mutableStateOf("") }
    var deleteListTarget by remember { mutableStateOf<DesktopCustomList?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is LibraryUiEffect.Navigate -> {
                    onNavigate(effect.screen)
                }
                is LibraryUiEffect.ShowToast -> {
                    if (effect.isError) AppToastManager.showError(effect.message) else AppToastManager.showSuccess(effect.message)
                }
            }
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val bookmarksList = uiState.bookmarks
    val filteredBookmarks = uiState.filteredBookmarks
    val selectedTab = uiState.selectedTab
    val posterWidthDp = uiState.posterWidthDp

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (uiState.isTransferring) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Box {
                    TextButton(onClick = { transferMenuExpanded = true }, enabled = !uiState.isTransferring) {
                        Text("Library file ▾")
                    }
                    DropdownMenu(
                        expanded = transferMenuExpanded,
                        onDismissRequest = { transferMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export library…") },
                            onClick = {
                                transferMenuExpanded = false
                                exportProfileId = ProfileManager.activeProfileId
                                showExportPrivacyDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import library…") },
                            onClick = {
                                transferMenuExpanded = false
                                val targetProfileId = ProfileManager.activeProfileId
                                val file = NativeFileDialog.open(
                                    title = "Import Auras Orbit Library",
                                    mode = FileDialog.LOAD,
                                    allowedExtensions = listOf(".orbitlib"),
                                    category = NativeFileDialog.Category.BACKUP,
                                )
                                file?.let {
                                    viewModel.onEvent(LibraryUiEvent.OnImportLibraryFileSelected(it, targetProfileId))
                                }
                            },
                        )
                    }
                }
            }

            if (bookmarksList.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Your library is empty",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Button(onClick = { onNavigate(Config.Home) }) {
                        Text("Browse Shows")
                    }
                }
            } else {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isCompact) 8.dp else 16.dp, vertical = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DesktopWatchType.entries.forEach { tab ->
                            FilterChip(
                                selected = uiState.selectedCustomListId == null && selectedTab == tab,
                                onClick = { viewModel.onEvent(LibraryUiEvent.OnSelectTab(tab)) },
                                label = {
                                    Text(
                                        tab.stringRes,
                                        fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                        Box {
                            val selectedList = uiState.customLists.firstOrNull { it.id == uiState.selectedCustomListId }
                            FilterChip(
                                selected = selectedList != null,
                                onClick = { listsMenuExpanded = true },
                                label = { Text(selectedList?.name ?: "My Lists", maxLines = 1) },
                            )
                            DropdownMenu(
                                expanded = listsMenuExpanded,
                                onDismissRequest = { listsMenuExpanded = false },
                            ) {
                                if (selectedList != null) {
                                    DropdownMenuItem(
                                        text = { Text("Use watch status filters") },
                                        onClick = {
                                            listsMenuExpanded = false
                                            viewModel.onEvent(LibraryUiEvent.OnSelectCustomList(null))
                                        },
                                    )
                                    HorizontalDivider()
                                }
                                uiState.customLists.forEach { list ->
                                    val count = uiState.customListItems.count { it.listId == list.id }
                                    DropdownMenuItem(
                                        text = { Text("${list.name}  ·  $count") },
                                        onClick = {
                                            listsMenuExpanded = false
                                            viewModel.onEvent(LibraryUiEvent.OnSelectCustomList(list.id))
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Create list…") },
                                    onClick = {
                                        listsMenuExpanded = false
                                        createListName = ""
                                        createListForBookmarkId = null
                                        showCreateListDialog = true
                                    },
                                )
                                if (uiState.customLists.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Manage lists…") },
                                        onClick = {
                                            listsMenuExpanded = false
                                            showManageListsDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                    LibraryActionBar(
                        uiState = uiState,
                        isCompact = isCompact,
                        onSearch = { query -> viewModel.onEvent(LibraryUiEvent.OnSearchQueryChange(query)) },
                        onSortChange = { sort -> viewModel.onEvent(LibraryUiEvent.OnSortOptionChange(sort)) },
                        onProviderChange = { provider -> viewModel.onEvent(LibraryUiEvent.OnProviderFilterChange(provider)) },
                    )

                    if (filteredBookmarks.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                if (uiState.selectedCustomListId != null) {
                                    "This list is empty. Add titles from their Library card menu or details."
                                } else {
                                    "No bookmarks in ${selectedTab.stringRes}."
                                },
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    } else {
                        val minSize = if (isCompact) 105.dp else posterWidthDp.dp

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = minSize),
                            contentPadding = PaddingValues(horizontal = if (isCompact) 6.dp else 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 16.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            items(filteredBookmarks, key = { it.id }) { bookmark ->
                                val provider = uiState.providerMap[bookmark.apiName]

                                BookmarkCard(
                                    bookmark = bookmark,
                                    isProviderMissing = bookmark.apiName != com.lagradost.cloudstream3.desktop.ui.GlobalMediaLauncher.NETWORK_STREAM_API_NAME &&
                                        bookmark.apiName !in uiState.installedProviderNames,
                                    onClick = {
                                        viewModel.onEvent(LibraryUiEvent.OnBookmarkClick(bookmark))
                                    },
                                    onSecondaryClick = { bounds ->
                                        GlobalContextMenuState.showForBookmark(
                                            bounds = bounds,
                                            bookmark = bookmark,
                                            provider = provider,
                                            onClick = {
                                                viewModel.onEvent(LibraryUiEvent.OnBookmarkClick(bookmark))
                                            },
                                            onPlayClick = {
                                                viewModel.onEvent(LibraryUiEvent.OnBookmarkClick(bookmark))
                                            },
                                            onRemove = {
                                                viewModel.onEvent(LibraryUiEvent.OnDeleteBookmark(bookmark.id))
                                            },
                                            onChangeCategory = { newType ->
                                                viewModel.onEvent(LibraryUiEvent.OnChangeWatchType(bookmark.id, newType))
                                            },
                                            onManageCustomLists = { bookmarkForLists = bookmark },
                                            onReLink = {
                                                viewModel.onEvent(LibraryUiEvent.OnStartReLink(bookmark))
                                            },
                                            onSearchOtherProviders = {
                                                viewModel.onEvent(LibraryUiEvent.OnSearchGlobal(bookmark.name))
                                            },
                                        )
                                    },
                                    onDelete = {
                                        viewModel.onEvent(LibraryUiEvent.OnDeleteBookmark(bookmark.id))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        uiState.orphanRecoveryBookmark?.let { orphan ->
            LibraryRecoveryDialog(
                bookmark = orphan,
                isSearching = uiState.isSearchingMatches,
                matchedResults = uiState.matchedResults,
                onDismiss = { viewModel.onEvent(LibraryUiEvent.OnDismissRecoveryModal) },
                onSelectMatch = { prov, match ->
                    viewModel.onEvent(LibraryUiEvent.OnSelectReLinkMatch(orphan, prov, match))
                },
                onSearchGlobal = {
                    viewModel.onEvent(LibraryUiEvent.OnSearchGlobal(orphan.name))
                },
                onDelete = {
                    viewModel.onEvent(LibraryUiEvent.OnDeleteBookmark(orphan.id))
                    viewModel.onEvent(LibraryUiEvent.OnDismissRecoveryModal)
                },
            )
        }

        if (showExportPrivacyDialog) {
            AlertDialog(
                onDismissRequest = {
                    showExportPrivacyDialog = false
                    exportProfileId = null
                },
                title = { Text("Export this profile’s library?") },
                text = {
                    Text(
                        "The .orbitlib file contains saved titles, provider names and URLs, statuses, and episode progress. " +
                            "It excludes credentials, add-on data, settings, downloads, and screenshots. Keep the file private.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val targetProfileId = exportProfileId
                        showExportPrivacyDialog = false
                        exportProfileId = null
                        val file = NativeFileDialog.open(
                            title = "Export Auras Orbit Library",
                            mode = FileDialog.SAVE,
                            allowedExtensions = listOf(".orbitlib"),
                            category = NativeFileDialog.Category.BACKUP,
                        )
                        if (file != null && targetProfileId != null) {
                            viewModel.onEvent(LibraryUiEvent.OnExportLibrary(file, targetProfileId))
                        }
                    }) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showExportPrivacyDialog = false
                        exportProfileId = null
                    }) { Text("Cancel") }
                },
            )
        }

        uiState.importPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(LibraryUiEvent.OnDismissLibraryImport) },
                title = { Text("Import library?") },
                text = {
                    Text(
                        "Import into ${uiState.importTargetProfileName ?: "the selected profile"}.\n\n" +
                            "${preview.bookmarkCount} saved titles, ${preview.historyCount} watch-progress records, " +
                            "${preview.followedShowCount} followed shows, ${preview.episodeWatchMarkCount} episode watch marks, " +
                            "${preview.customListCount} custom lists, and ${preview.customListItemCount} list entries " +
                            "from Orbit ${preview.sourceAppVersion}.\n\n" +
                            "Existing titles stay unchanged; newer episode progress is kept. Nothing is deleted. " +
                            "The file is read locally without contacting providers.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.onEvent(LibraryUiEvent.OnConfirmLibraryImport) },
                        enabled = !uiState.isTransferring,
                    ) { Text("Import") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.onEvent(LibraryUiEvent.OnDismissLibraryImport) },
                        enabled = !uiState.isTransferring,
                    ) { Text("Cancel") }
                },
            )
        }

        bookmarkForLists?.let { bookmark ->
            AlertDialog(
                onDismissRequest = { bookmarkForLists = null },
                title = { Text("Add to lists") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(bookmark.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        if (uiState.customLists.isEmpty()) {
                            Text("Create a list first using the My Lists control.")
                        } else {
                            uiState.customLists.forEach { list ->
                                val included = uiState.customListItems.any {
                                    it.listId == list.id && it.bookmarkId == bookmark.id
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = included,
                                        onCheckedChange = { checked ->
                                            viewModel.onEvent(
                                                LibraryUiEvent.OnSetBookmarkInCustomList(list.id, bookmark.id, checked),
                                            )
                                        },
                                    )
                                    Text(list.name)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { bookmarkForLists = null }) { Text("Done") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        createListName = ""
                        createListForBookmarkId = bookmark.id
                        showCreateListDialog = true
                        bookmarkForLists = null
                    }) { Text("New list") }
                },
            )
        }

        if (showCreateListDialog) {
            AlertDialog(
                onDismissRequest = { showCreateListDialog = false },
                title = { Text("Create list") },
                text = {
                    OutlinedTextField(
                        value = createListName,
                        onValueChange = { createListName = it.take(64) },
                        label = { Text("List name") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = createListName.isNotBlank(),
                        onClick = {
                            viewModel.onEvent(LibraryUiEvent.OnCreateCustomList(createListName, createListForBookmarkId))
                            showCreateListDialog = false
                        },
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateListDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showManageListsDialog) {
            AlertDialog(
                onDismissRequest = { showManageListsDialog = false },
                title = { Text("Manage lists") },
                text = {
                    if (uiState.customLists.isEmpty()) {
                        Text("You have no custom lists yet.")
                    } else {
                        Column(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.customLists.forEach { list ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(list.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${uiState.customListItems.count { it.listId == list.id }} titles",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = {
                                        viewModel.onEvent(LibraryUiEvent.OnPinCustomListToHome(list.id, !list.showOnHome))
                                    }) { Text(if (list.showOnHome) "Unpin" else "Pin to Home") }
                                    TextButton(onClick = {
                                        renameListTarget = list
                                        renameListName = list.name
                                        showManageListsDialog = false
                                    }) { Text("Rename") }
                                    TextButton(onClick = {
                                        deleteListTarget = list
                                        showManageListsDialog = false
                                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showManageListsDialog = false }) { Text("Done") }
                },
            )
        }

        renameListTarget?.let { list ->
            AlertDialog(
                onDismissRequest = { renameListTarget = null },
                title = { Text("Rename list") },
                text = {
                    OutlinedTextField(
                        value = renameListName,
                        onValueChange = { renameListName = it.take(64) },
                        label = { Text("List name") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = renameListName.isNotBlank(),
                        onClick = {
                            viewModel.onEvent(LibraryUiEvent.OnRenameCustomList(list.id, renameListName))
                            renameListTarget = null
                        },
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { renameListTarget = null }) { Text("Cancel") }
                },
            )
        }

        deleteListTarget?.let { list ->
            AlertDialog(
                onDismissRequest = { deleteListTarget = null },
                title = { Text("Delete ${list.name}?") },
                text = { Text("This removes the list and its entries. The saved titles remain in your Library.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onEvent(LibraryUiEvent.OnDeleteCustomList(list.id))
                            deleteListTarget = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteListTarget = null }) { Text("Cancel") }
                },
            )
        }
    }
}
