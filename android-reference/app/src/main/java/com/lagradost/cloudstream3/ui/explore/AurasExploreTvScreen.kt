package com.lagradost.cloudstream3.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.DataStoreHelper
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private object AurasExplorePalette {
    val Background = Color(0xFF17151F)
    val BackgroundRaised = Color(0xFF1D1A27)
    val Surface = Color(0xFF211F2C)
    val SurfaceHover = Color(0xFF292635)
    val Lavender = Color(0xFFA394E1)
    val LavenderBright = Color(0xFFBBAAF4)
    val Text = Color(0xFFF4F1FA)
    val Muted = Color(0xFFB8B3C3)
    val Stroke = Color.White.copy(alpha = 0.09f)
}

@Composable
internal fun AurasExploreTvScreen(
    isTv: Boolean,
    state: AurasBrowseState,
    sourcePickerVisible: Boolean,
    onSourceClick: () -> Unit,
    onSourceDismiss: () -> Unit,
    onSourceSelect: (AurasSourceOption) -> Unit,
    onRetrySource: () -> Unit,
    onLoadMoreShelf: (String) -> Unit,
    onCatalogs: () -> Unit,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onLibrary: () -> Unit,
    onHelp: () -> Unit,
    onOpenTitle: (SearchResponse, Int) -> Unit,
    onResumeTitle: (SearchResponse, Int) -> Unit,
    onSeeAllTrending: (String) -> Unit,
) {
    val firstActionFocus = remember { FocusRequester() }
    val providerCount = state.sources.count { it.key != APIRepository.randomApi.name }
    val selectedSourceLabel = state.sources.firstOrNull { it.key == state.sourceName }?.label
        ?: state.sourceName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.auras_explore_source_choose)
    val resumeProgressByUrl = state.resumeItems.mapNotNull { item ->
        val watchPos = (item as? DataStoreHelper.ResumeWatchingResult)?.watchPos
            ?: return@mapNotNull null
        if (watchPos.duration <= 0L) return@mapNotNull null
        item.url to (watchPos.position.toFloat() / watchPos.duration.toFloat()).coerceIn(0f, 1f)
    }.distinctBy { it.first }.toMap()

    LaunchedEffect(isTv, state.resumeItems.isNotEmpty()) {
        if (isTv) firstActionFocus.requestFocus()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1B1827),
                        AurasExplorePalette.Background,
                        Color(0xFF171620),
                    )
                )
            ),
    ) {
        val wide = maxWidth >= 720.dp
        val contentPadding = if (wide) 42.dp else 22.dp
        val columns = if (maxWidth >= 960.dp) 4 else 2

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1120.dp)
                .align(Alignment.TopCenter),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(
                horizontal = contentPadding,
                vertical = if (wide) 34.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 22.dp),
        ) {
            item(key = "brand-heading") {
                BrandHeading(onHelp)
            }

            item(key = "page-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = stringResource(R.string.auras_tv_explore_title),
                        color = AurasExplorePalette.Text,
                        fontSize = if (wide) 40.sp else 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.7).sp,
                    )
                    Text(
                        text = stringResource(R.string.auras_tv_explore_subtitle),
                        color = AurasExplorePalette.Muted,
                        fontSize = if (wide) 17.sp else 15.sp,
                        lineHeight = if (wide) 25.sp else 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            state.resumeItems.firstOrNull()?.let { latestResume ->
                item(key = "resume-hero") {
                    AurasResumeHero(
                        item = latestResume,
                        progress = resumeProgressByUrl[latestResume.url],
                        isTv = true,
                        onResume = { onResumeTitle(latestResume, 0) },
                        focusRequester = firstActionFocus,
                    )
                }
            } ?: item(key = "welcome-hero") {
                ExploreHero(
                    wide = wide,
                    providerCount = providerCount,
                    firstActionFocus = firstActionFocus,
                    onCatalogs = onCatalogs,
                    onSearch = onSearch,
                    showActions = false,
                )
            }

            if (state.resumeItems.size > 1) {
                item(key = "resume-more") {
                    TvPosterRail(
                        title = stringResource(R.string.auras_explore_resume_more_title),
                        items = state.resumeItems.drop(1),
                        posterWidth = if (wide) 150.dp else 124.dp,
                        progressByUrl = resumeProgressByUrl,
                        onOpen = { item, index -> onResumeTitle(item, index + 1) },
                        actionLabel = stringResource(R.string.auras_explore_resume_see_all),
                        onAction = onLibrary,
                    )
                }
            }

            item(key = "source-selector") {
                ExploreSourceSelector(
                    sourceName = selectedSourceLabel,
                    hasSources = state.sources.isNotEmpty(),
                    onClick = onSourceClick,
                )
            }

            item(key = "browse-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction(
                        label = stringResource(R.string.auras_explore_browse_catalog),
                        onClick = onCatalogs,
                        focusRequester = if (state.resumeItems.isEmpty()) firstActionFocus else null,
                    )
                    QuietAction(label = stringResource(R.string.auras_tv_explore_search_title), onClick = onSearch)
                }
            }

            when {
                state.loading -> item(key = "loading") { ExploreLoadingPanel() }
                state.error != null -> item(key = "error") {
                    ExploreMessagePanel(
                        title = stringResource(
                            if (state.shelves.isEmpty()) R.string.auras_explore_load_error_title
                            else R.string.auras_explore_refresh_error_title
                        ),
                        body = state.error,
                        actionLabel = stringResource(R.string.auras_catalog_retry),
                        onAction = onRetrySource,
                    )
                }
                state.shelves.isEmpty() && state.resumeItems.isEmpty() -> item(key = "empty") {
                    ExploreMessagePanel(
                        title = stringResource(R.string.auras_explore_empty_title),
                        body = stringResource(R.string.auras_explore_empty_body),
                        actionLabel = stringResource(
                            if (state.sources.isEmpty()) R.string.auras_explore_connect_source
                            else R.string.auras_explore_choose_source
                        ),
                        onAction = if (state.sources.isEmpty()) onSources else onSourceClick,
                    )
                }
            }

            itemsIndexed(
                items = state.shelves.filter {
                    it.items.isNotEmpty() || it.hasNext || it.pageError != null
                },
                        key = { _, shelf -> "shelf-${shelf.name}" },
            ) { _, shelf ->
                    val isTrending = shelf.isTrendingShelf()
                    val hasFullList = isTrending || shelf.items.size > 10 || shelf.hasNext
                    val pageActionLabel = when {
                        !shelf.hasNext -> null
                        shelf.isLoadingMore -> stringResource(R.string.auras_catalog_loading_more)
                        shelf.pageError != null -> stringResource(R.string.auras_catalog_retry)
                        else -> stringResource(R.string.auras_catalog_more)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (shelf.items.isEmpty()) {
                            ExploreShelfHeading(shelf.name)
                            Text(
                                text = shelf.pageError?.let {
                                    stringResource(R.string.auras_catalog_page_error, it)
                                } ?: stringResource(R.string.auras_catalog_empty_shelf),
                                color = if (shelf.pageError == null) AurasExplorePalette.Muted else AurasExplorePalette.LavenderBright,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            )
                        } else {
                            TvPosterRail(
                                title = if (isTrending) stringResource(R.string.auras_trending_section_title) else shelf.name,
                                items = if (isTrending) shelf.items.take(10) else shelf.items,
                                posterWidth = if (wide) 150.dp else 124.dp,
                                onOpen = onOpenTitle,
                                actionLabel = when {
                                    isTrending -> stringResource(R.string.auras_trending_see_all)
                                    hasFullList -> stringResource(R.string.auras_collection_see_all)
                                    else -> null
                                },
                                onAction = if (hasFullList) ({ onSeeAllTrending(shelf.name) }) else null,
                            )
                        }
                        shelf.pageError?.takeIf { shelf.items.isNotEmpty() }?.let { message ->
                            Text(
                                stringResource(R.string.auras_catalog_page_error, message),
                                color = AurasExplorePalette.LavenderBright,
                                fontSize = 13.sp,
                            )
                        }
                        if (shelf.hasNext) {
                            QuietAction(
                                label = pageActionLabel.orEmpty(),
                                onClick = { onLoadMoreShelf(shelf.name) },
                                enabled = !shelf.isLoadingMore,
                            )
                        }
                    }
            }

            item(key = "discover") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.auras_tv_explore_discover),
                        color = AurasExplorePalette.Text,
                        fontSize = if (wide) 22.sp else 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )

                    val tiles = listOf(
                        ExploreTileData(
                            title = stringResource(R.string.auras_tv_explore_catalogs_title),
                            description = stringResource(R.string.auras_tv_explore_catalogs_description),
                            icon = R.drawable.baseline_grid_view_24,
                            onClick = onCatalogs,
                        ),
                        ExploreTileData(
                            title = stringResource(R.string.auras_tv_explore_search_title),
                            description = stringResource(R.string.auras_tv_explore_search_description),
                            icon = R.drawable.search_icon,
                            onClick = onSearch,
                        ),
                        ExploreTileData(
                            title = stringResource(R.string.auras_tv_explore_sources_title),
                            description = if (providerCount > 0) {
                                pluralStringResource(
                                    R.plurals.auras_tv_explore_sources_count,
                                    providerCount,
                                    providerCount,
                                )
                            } else {
                                stringResource(R.string.auras_tv_explore_sources_empty)
                            },
                            icon = R.drawable.ic_baseline_extension_24,
                            onClick = onSources,
                        ),
                        ExploreTileData(
                            title = stringResource(R.string.auras_tv_explore_library_title),
                            description = stringResource(R.string.auras_tv_explore_library_description),
                            icon = R.drawable.library_icon,
                            onClick = onLibrary,
                        ),
                    )

                    tiles.chunked(columns).forEach { rowTiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            rowTiles.forEach { tile ->
                                ExploreTile(
                                    data = tile,
                                    compact = wide,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            item(key = "footer") {
                Surface(
                    color = AurasExplorePalette.BackgroundRaised,
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AurasExplorePalette.Stroke),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Text("✦", color = AurasExplorePalette.LavenderBright, fontSize = 21.sp)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = stringResource(
                                    if (providerCount > 0) R.string.auras_tv_explore_orbit_ready_title
                                    else R.string.auras_tv_explore_orbit_empty_title
                                ),
                                color = AurasExplorePalette.Text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = stringResource(
                                    if (providerCount > 0) R.string.auras_tv_explore_orbit_ready_body
                                    else R.string.auras_tv_explore_orbit_empty_body
                                ),
                                color = AurasExplorePalette.Muted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        QuietAction(label = stringResource(R.string.auras_tv_explore_sources_title), onClick = onSources)
                    }
                }
            }
        }

        ExploreSourcePicker(
            visible = sourcePickerVisible,
            selectedKey = state.sourceName,
            options = state.sources,
            onDismiss = onSourceDismiss,
            onSelect = onSourceSelect,
            onConnectSource = onSources,
        )
    }
}

@Composable
private fun ExploreSourceSelector(
    sourceName: String,
    hasSources: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = if (isFocused) AurasExplorePalette.SurfaceHover else AurasExplorePalette.Surface,
        contentColor = AurasExplorePalette.Text,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (isFocused) AurasExplorePalette.LavenderBright else AurasExplorePalette.Stroke,
        ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (hasSources) R.string.auras_explore_current_source
                    else R.string.auras_explore_source_not_connected
                ),
                color = AurasExplorePalette.Muted,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = sourceName,
                color = AurasExplorePalette.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (hasSources) R.string.auras_tv_source_change_hint
                    else R.string.auras_tv_source_connect_hint
                ),
                color = AurasExplorePalette.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TvPosterRail(
    title: String?,
    items: List<SearchResponse>,
    posterWidth: Dp,
    onOpen: (SearchResponse, Int) -> Unit,
    progressByUrl: Map<String, Float> = emptyMap(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    startIndex: Int = 0,
    maxItems: Int = AURAS_POSTER_RAIL_LIMIT,
) {
    val visibleItems = items.take(maxItems)
    if (visibleItems.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!title.isNullOrBlank() || (actionLabel != null && onAction != null)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        color = AurasExplorePalette.Text,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    QuietAction(label = actionLabel, onClick = onAction)
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                items = visibleItems,
                key = { _, item -> item.id?.let { "${item.apiName}:$it" } ?: "${item.apiName}:${item.url}" },
            ) { index, item ->
                TvPosterCard(
                    item = item,
                    width = posterWidth,
                    progress = progressByUrl[item.url],
                    onClick = { onOpen(item, startIndex + index) },
                )
            }
        }
    }
}

/** Poster cards keep titles hidden visually while retaining accessible labels and D-pad focus. */
@Composable
private fun TvPosterCard(
    item: SearchResponse,
    width: Dp,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AurasExplorePalette.Surface),
        border = androidx.compose.foundation.BorderStroke(
            if (isFocused) 3.dp else 1.dp,
            if (isFocused) AurasExplorePalette.LavenderBright else AurasExplorePalette.Stroke,
        ),
        modifier = Modifier
            .width(width)
            .onFocusChanged { isFocused = it.isFocused }
            .semantics(mergeDescendants = true) { contentDescription = item.name },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .clip(shape),
        ) {
            AurasArtwork(
                url = item.posterUrl,
                headers = item.posterHeaders,
                modifier = Modifier.fillMaxSize(),
            )
            progress?.let { value ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 9.dp, vertical = 10.dp)
                        .height(5.dp)
                        .background(AurasExplorePalette.Text.copy(alpha = 0.35f), CircleShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(AurasExplorePalette.LavenderBright, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExploreShelfHeading(title: String) {
    Text(
        text = title,
        color = AurasExplorePalette.Text,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ExploreLoadingPanel() {
    Surface(
        color = AurasExplorePalette.Surface,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AurasExplorePalette.Stroke),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            CircularProgressIndicator(color = AurasExplorePalette.LavenderBright)
            Text(
                stringResource(R.string.auras_catalogs_refreshing),
                color = AurasExplorePalette.Text,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun ExploreMessagePanel(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        color = AurasExplorePalette.Surface,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AurasExplorePalette.Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = AurasExplorePalette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = AurasExplorePalette.Muted, fontSize = 14.sp, lineHeight = 20.sp)
            PrimaryAction(label = actionLabel, onClick = onAction)
        }
    }
}

@Composable
private fun ExploreSourcePicker(
    visible: Boolean,
    selectedKey: String?,
    options: List<AurasSourceOption>,
    onDismiss: () -> Unit,
    onSelect: (AurasSourceOption) -> Unit,
    onConnectSource: () -> Unit,
) {
    if (!visible) return
    var query by remember { mutableStateOf("") }
    val matchingOptions = remember(options, query) {
        options.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    val searchFocus = remember { FocusRequester() }
    val optionFocus = remember { FocusRequester() }
    val initialOption = options.firstOrNull { it.key == selectedKey } ?: options.firstOrNull()
    val initialOptionIndex = initialOption?.let { option -> options.indexOf(option) } ?: -1
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialOptionIndex.coerceAtLeast(0))
    LaunchedEffect(visible) {
        if (visible) {
            if (initialOptionIndex >= 0) {
                optionFocus.requestFocus()
            } else {
                searchFocus.requestFocus()
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = AurasExplorePalette.BackgroundRaised,
            contentColor = AurasExplorePalette.Text,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AurasExplorePalette.Stroke),
            modifier = Modifier.fillMaxWidth(0.82f).widthIn(max = 620.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.auras_source_picker_title),
                    color = AurasExplorePalette.Text,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                    singleLine = true,
                    label = { Text(stringResource(R.string.auras_source_picker_search)) },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    itemsIndexed(matchingOptions, key = { _, option -> option.key }) { _, option ->
                        var isFocused by remember(option.key) { mutableStateOf(false) }
                        val selected = option.key == selectedKey
                        Surface(
                            onClick = { onSelect(option) },
                            color = when {
                                isFocused -> AurasExplorePalette.SurfaceHover
                                selected -> Color(0xFF302A42)
                                else -> AurasExplorePalette.Surface
                            },
                            contentColor = AurasExplorePalette.Text,
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                if (isFocused) 2.dp else 1.dp,
                                if (isFocused) AurasExplorePalette.LavenderBright else AurasExplorePalette.Stroke,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (option.key == initialOption?.key) Modifier.focusRequester(optionFocus) else Modifier)
                                .onFocusChanged { isFocused = it.isFocused },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(option.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (selected) Text("●", color = AurasExplorePalette.LavenderBright)
                            }
                        }
                    }
                    if (matchingOptions.isEmpty()) item {
                        Text(
                            stringResource(R.string.auras_source_picker_no_results),
                            color = AurasExplorePalette.Muted,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                QuietAction(
                    label = stringResource(R.string.auras_welcome_connect),
                    onClick = {
                        onDismiss()
                        onConnectSource()
                    },
                )
            }
        }
    }
}

private fun AurasShelf.isTrendingShelf(): Boolean =
    Regex("\\btrending\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)

@Composable
private fun BrandHeading(onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.auras_orbit_mark),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = stringResource(R.string.auras_tv_brand_name),
                color = AurasExplorePalette.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = stringResource(R.string.auras_tv_brand_tagline),
                color = AurasExplorePalette.Muted,
                fontSize = 12.sp,
            )
        }
        QuietAction(label = stringResource(R.string.auras_welcome_help), onClick = onHelp)
    }
}

@Composable
private fun ExploreHero(
    wide: Boolean,
    providerCount: Int,
    firstActionFocus: FocusRequester,
    onCatalogs: () -> Unit,
    onSearch: () -> Unit,
    showActions: Boolean = true,
) {
    Surface(
        color = AurasExplorePalette.Surface,
        shape = RoundedCornerShape(if (wide) 30.dp else 25.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF292438), Color(0xFF221F2E), Color(0xFF201E29))
                    )
                )
                .padding(horizontal = if (wide) 30.dp else 22.dp, vertical = if (wide) 28.dp else 22.dp),
        ) {
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeroCopy(
                        modifier = Modifier.weight(1f),
                        providerCount = providerCount,
                        firstActionFocus = firstActionFocus,
                        onCatalogs = onCatalogs,
                        onSearch = onSearch,
                        showActions = showActions,
                    )
                    Spacer(Modifier.width(20.dp))
                    OrbitArtwork(modifier = Modifier.size(180.dp), centerMarkSize = 64.dp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OrbitArtwork(
                        modifier = Modifier.align(Alignment.End).size(105.dp),
                        centerMarkSize = 42.dp,
                    )
                    HeroCopy(
                        providerCount = providerCount,
                        firstActionFocus = firstActionFocus,
                        onCatalogs = onCatalogs,
                        onSearch = onSearch,
                        showActions = showActions,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCopy(
    providerCount: Int,
    firstActionFocus: FocusRequester,
    onCatalogs: () -> Unit,
    onSearch: () -> Unit,
    showActions: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                if (providerCount > 0) R.string.auras_tv_explore_hero_connected
                else R.string.auras_tv_explore_hero_empty
            ),
            color = AurasExplorePalette.Text,
            fontSize = 25.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.25).sp,
        )
        Text(
            text = stringResource(R.string.auras_tv_explore_hero_description),
            color = AurasExplorePalette.Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (showActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryAction(
                    label = stringResource(R.string.auras_explore_browse_catalog),
                    onClick = onCatalogs,
                    focusRequester = firstActionFocus,
                )
                QuietAction(label = stringResource(R.string.auras_tv_explore_search_title), onClick = onSearch)
            }
        }
    }
}

@Composable
private fun OrbitArtwork(modifier: Modifier = Modifier, centerMarkSize: Dp) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension
            drawCircle(
                color = AurasExplorePalette.Lavender.copy(alpha = 0.10f),
                radius = base * 0.47f,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                radius = base * 0.35f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            rotate(degrees = -32f, pivot = center) {
                drawOval(
                    color = AurasExplorePalette.LavenderBright.copy(alpha = 0.42f),
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - base * 0.48f, center.y - base * 0.22f),
                    size = androidx.compose.ui.geometry.Size(base * 0.96f, base * 0.44f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(
                color = AurasExplorePalette.LavenderBright,
                radius = 3.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(center.x + base * 0.39f, center.y - base * 0.19f),
            )
        }
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.auras_orbit_mark),
            contentDescription = null,
            modifier = Modifier.size(centerMarkSize),
        )
    }
}

private data class ExploreTileData(
    val title: String,
    val description: String,
    val icon: Int,
    val onClick: () -> Unit,
)

@Composable
private fun ExploreTile(
    data: ExploreTileData,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = data.onClick,
        modifier = modifier
            .heightIn(min = if (compact) 164.dp else 146.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) AurasExplorePalette.SurfaceHover else AurasExplorePalette.Surface.copy(alpha = 0.95f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (isFocused) AurasExplorePalette.LavenderBright else AurasExplorePalette.Stroke,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 19.dp else 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Surface(color = Color(0xFF302A42), shape = RoundedCornerShape(12.dp), modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(data.icon),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(AurasExplorePalette.LavenderBright),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    data.title,
                    color = AurasExplorePalette.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    data.description,
                    color = AurasExplorePalette.Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = AurasExplorePalette.Lavender,
        contentColor = AurasExplorePalette.Background,
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isFocused) 2.dp else 0.dp,
            if (isFocused) AurasExplorePalette.Text else Color.Transparent,
        ),
        modifier = Modifier
            .height(46.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QuietAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.White.copy(alpha = 0.055f),
        contentColor = if (enabled) AurasExplorePalette.Text else AurasExplorePalette.Muted,
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (isFocused) AurasExplorePalette.LavenderBright else Color.White.copy(alpha = 0.10f),
        ),
        modifier = Modifier.height(46.dp).onFocusChanged { isFocused = it.isFocused },
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
