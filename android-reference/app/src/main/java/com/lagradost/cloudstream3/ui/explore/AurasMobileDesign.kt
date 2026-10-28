package com.lagradost.cloudstream3.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.PORTRAIT_POSTER_ASPECT_RATIO
import com.lagradost.cloudstream3.utils.DataStoreHelper
import coil3.request.ImageRequest

internal object AurasPalette {
    val Ink = Color(0xFF100D17)
    val Canvas = Color(0xFF15121D)
    val Surface = Color(0xFF201B2B)
    val SurfaceRaised = Color(0xFF2B2438)
    val Accent = Color(0xFFB9A7FF)
    val AccentDeep = Color(0xFF382B52)
    val Ember = Color(0xFFFFB681)
    val Text = Color(0xFFF7F3FC)
    val Muted = Color(0xFFB1AABD)
    val Stroke = Color.White.copy(alpha = 0.11f)
}

private val AurasColorScheme = darkColorScheme(
    primary = AurasPalette.Accent,
    onPrimary = AurasPalette.Ink,
    primaryContainer = AurasPalette.AccentDeep,
    onPrimaryContainer = AurasPalette.Text,
    secondary = AurasPalette.Ember,
    onSecondary = AurasPalette.Ink,
    background = AurasPalette.Canvas,
    onBackground = AurasPalette.Text,
    surface = AurasPalette.Surface,
    onSurface = AurasPalette.Text,
    surfaceVariant = AurasPalette.SurfaceRaised,
    onSurfaceVariant = AurasPalette.Muted,
    outline = AurasPalette.Stroke,
)

private val AurasTypography = Typography().copy(
    headlineLarge = Typography().headlineLarge.copy(
        fontSize = 32.sp,
        lineHeight = 37.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontSize = 25.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
internal fun AurasMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AurasColorScheme,
        typography = AurasTypography,
        content = content,
    )
}

internal data class AurasSourceOption(val key: String, val label: String)

internal data class AurasShelf(
    val name: String,
    val items: List<SearchResponse>,
    val hasNext: Boolean = false,
    val isLoadingMore: Boolean = false,
    val pageError: String? = null,
)

/** Shelf names can be more reliable than a provider's item type for a categorized catalog row. */
internal data class AurasBrowseState(
    val sourceName: String? = null,
    val sources: List<AurasSourceOption> = emptyList(),
    val shelves: List<AurasShelf> = emptyList(),
    val resumeItems: List<SearchResponse> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@Composable
internal fun AurasBrandBar(
    modifier: Modifier = Modifier,
    onHelpClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.auras_orbit_mark),
            contentDescription = null,
            modifier = Modifier.size(42.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "AURAS ORBIT",
                color = AurasPalette.Text,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = stringResource(R.string.auras_explore_brand_tagline),
                color = AurasPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onHelpClick != null) {
            Surface(
                onClick = onHelpClick,
                color = AurasPalette.SurfaceRaised,
                contentColor = AurasPalette.Text,
                shape = CircleShape,
                border = BorderStroke(1.dp, AurasPalette.Stroke),
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        painter = painterResource(R.drawable.question_mark_24),
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.auras_help_manual_title),
                        tint = AurasPalette.Accent,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AurasPageTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = AurasPalette.Text)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = AurasPalette.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AurasSectionHeading(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = AurasPalette.Text, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionLabel, color = AurasPalette.Accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun AurasPosterRail(
    title: String?,
    items: List<SearchResponse>,
    onOpen: (SearchResponse, Int) -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    progressByUrl: Map<String, Float> = emptyMap(),
    actionEnabled: Boolean = true,
    rankStart: Int? = null,
    startIndex: Int = 0,
    maxItems: Int = AURAS_POSTER_RAIL_LIMIT,
    posterWidth: Dp = 98.dp,
) {
    val visibleItems = items.take(maxItems)
    if (visibleItems.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!title.isNullOrBlank()) AurasSectionHeading(title, actionLabel, onAction, actionEnabled)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            itemsIndexed(visibleItems, key = { index, item ->
                aurasUniqueLazyKey(item.id?.let { "${item.apiName}:$it" } ?: "${item.apiName}:${item.url}", index)
            }) { index, item ->
                AurasPosterCard(
                    item = item,
                    width = posterWidth,
                    progress = progressByUrl[item.url],
                    rank = rankStart?.plus(index),
                    onClick = { onOpen(item, startIndex + index) },
                )
            }
        }
    }
}

/** Provider identities can repeat across the initial catalog and later pages. */
internal fun aurasUniqueLazyKey(identity: String, index: Int): String = "auras-item:$index:$identity"

internal const val AURAS_POSTER_RAIL_LIMIT = 30

@Composable
internal fun AurasResumeHero(
    item: SearchResponse,
    progress: Float?,
    isTv: Boolean,
    onResume: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val resume = item as? DataStoreHelper.ResumeWatchingResult
    val episode = resume?.let {
        when {
            it.season != null && it.episode != null ->
                stringResource(R.string.auras_resume_season_episode, it.season, it.episode)
            it.episode != null -> stringResource(R.string.auras_resume_episode, it.episode)
            else -> null
        }
    }
    val percentage = progress?.let { (it * 100).toInt().coerceIn(0, 100) }
    val description = buildList {
        add(item.name)
        episode?.let(::add)
        percentage?.let { add("$it% watched") }
    }.joinToString(", ")
    val shape = RoundedCornerShape(if (isTv) 26.dp else 22.dp)

    Card(
        onClick = onResume,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
        border = BorderStroke(
            if (isFocused) 3.dp else 1.dp,
            if (isFocused) AurasPalette.Accent else AurasPalette.Stroke,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isTv) 252.dp else 176.dp)
                .clip(shape),
        ) {
            val hasArtwork = aurasResumeArtwork(
                episodePosterUrl = resume?.episodePosterUrl,
                showPosterUrl = item.posterUrl,
                episodeHeaders = resume?.episodePosterHeaders,
                showHeaders = item.posterHeaders,
                modifier = Modifier.fillMaxSize(),
            )
            if (hasArtwork) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.08f),
                                    Color.Black.copy(alpha = 0.2f),
                                    Color.Black.copy(alpha = 0.86f),
                                ),
                            ),
                        ),
                )
            } else {
                Box(Modifier.fillMaxSize().background(AurasPalette.SurfaceRaised))
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = if (isTv) 24.dp else 17.dp, vertical = if (isTv) 20.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 9.dp else 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.auras_explore_resume_title),
                    color = AurasPalette.Accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                episode?.let {
                    Text(it, color = AurasPalette.Text, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = stringResource(R.string.auras_resume_action),
                    color = AurasPalette.Text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                progress?.let { value ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(AurasPalette.Text.copy(alpha = 0.38f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(value.coerceIn(0f, 1f))
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(AurasPalette.Accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun aurasResumeArtwork(
    episodePosterUrl: String?,
    showPosterUrl: String?,
    episodeHeaders: Map<String, String>?,
    showHeaders: Map<String, String>?,
    modifier: Modifier = Modifier,
): Boolean {
    val context = LocalContext.current
    var episodeFailed by remember(episodePosterUrl, showPosterUrl, episodeHeaders, showHeaders) { mutableStateOf(false) }
    var showPosterFailed by remember(episodePosterUrl, showPosterUrl, episodeHeaders, showHeaders) { mutableStateOf(false) }
    val showEpisodeArtwork = !episodePosterUrl.isNullOrBlank() && !episodeFailed
    val fallbackPosterUrl = showPosterUrl?.takeIf { it.isNotBlank() }
    val showFallbackArtwork = !showEpisodeArtwork && !fallbackPosterUrl.isNullOrBlank() && !showPosterFailed

    Box(modifier.background(AurasPalette.SurfaceRaised)) {
        when {
            showEpisodeArtwork -> AsyncImage(
                model = remember(context, episodePosterUrl, episodeHeaders) {
                    buildAurasImageRequest(context, episodePosterUrl, episodeHeaders)
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) episodeFailed = true
                },
            )

            showFallbackArtwork -> AsyncImage(
                model = remember(context, fallbackPosterUrl, showHeaders) {
                    buildAurasImageRequest(context, fallbackPosterUrl, showHeaders)
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) showPosterFailed = true
                },
            )
        }
    }
    return showEpisodeArtwork || showFallbackArtwork
}

private fun buildAurasImageRequest(
    context: android.content.Context,
    url: String,
    headers: Map<String, String>?,
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
        headerBuilder["User-Agent"] = USER_AGENT
        headers?.forEach { (key, value) ->
            headerBuilder[key] = value
        }
    }.build())
    .build()

@Composable
internal fun AurasSelectionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = if (selected) AurasPalette.Accent.copy(alpha = 0.22f) else AurasPalette.Surface,
        contentColor = if (selected) AurasPalette.Text else AurasPalette.Muted,
        shape = CircleShape,
        border = BorderStroke(
            if (isFocused) 3.dp else 1.dp,
            if (isFocused || selected) AurasPalette.Accent else AurasPalette.Stroke,
        ),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { this.selected = selected },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun AurasSourceRow(
    sourceName: String?,
    hasSources: Boolean,
    isTv: Boolean = false,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = AurasPalette.Surface,
        contentColor = AurasPalette.Text,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            if (isFocused) 3.dp else 1.dp,
            if (isFocused) AurasPalette.Accent else AurasPalette.Stroke,
        ),
        modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(
                    if (hasSources) R.string.auras_explore_current_source
                    else R.string.auras_explore_source_not_connected
                ),
                color = AurasPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = sourceName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.auras_explore_source_choose),
                color = AurasPalette.Text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    when {
                        hasSources && isTv -> R.string.auras_tv_source_change_hint
                        !hasSources && isTv -> R.string.auras_tv_source_connect_hint
                        hasSources -> R.string.auras_explore_source_change_hint
                        else -> R.string.auras_explore_source_connect_hint
                    },
                ),
                color = AurasPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun AurasRankedTrendingRow(
    item: SearchResponse,
    rank: Int,
    onOpen: () -> Unit,
) {
    val year = when (item) {
        is AnimeSearchResponse -> item.year
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        else -> null
    }
    val score = item.score?.toStringNull(minScore = 0.1, maxScore = 10, decimals = 1)
    val shape = RoundedCornerShape(20.dp)
    Card(
        onClick = onOpen,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
        border = BorderStroke(1.dp, AurasPalette.Stroke),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(item.name, year?.toString(), score?.let { "Rating $it" }).joinToString(", ")
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = rank.toString().padStart(2, '0'),
                color = AurasPalette.Accent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .width(78.dp)
                    .aspectRatio(PORTRAIT_POSTER_ASPECT_RATIO)
                    .clip(RoundedCornerShape(13.dp)),
            ) {
                AurasArtwork(
                    url = item.posterUrl,
                    headers = item.posterHeaders,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (year != null || score != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        year?.let {
                            Text(
                                text = it.toString(),
                                color = AurasPalette.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        score?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = "★",
                                    color = AurasPalette.Accent,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = it,
                                    color = AurasPalette.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AurasPosterCard(
    item: SearchResponse,
    width: Dp,
    progress: Float? = null,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    val year = when (item) {
        is AnimeSearchResponse -> item.year
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        else -> null
    }
    val score = item.score?.toStringNull(minScore = 0.1, maxScore = 10, decimals = 1)
    val accessibilityDescription = listOfNotNull(
        item.name,
        year?.toString(),
        score?.let { "Rating $it" },
    ).joinToString(", ")
    val shape = RoundedCornerShape(21.dp)
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
        border = BorderStroke(
            if (isFocused) 3.dp else 1.dp,
            if (isFocused) AurasPalette.Accent else AurasPalette.Stroke,
        ),
        modifier = Modifier.width(width).onFocusChanged { isFocused = it.isFocused }.semantics(mergeDescendants = true) {
            contentDescription = accessibilityDescription
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PORTRAIT_POSTER_ASPECT_RATIO)
                .clip(shape),
        ) {
            AurasArtwork(
                url = item.posterUrl,
                headers = item.posterHeaders,
                modifier = Modifier.fillMaxSize(),
            )
            if (rank != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
                    color = AurasPalette.Ink.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, AurasPalette.Stroke),
                ) {
                    Text(
                        text = rank.toString().padStart(2, '0'),
                        color = AurasPalette.Accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            }
            score?.let { rating ->
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                    color = AurasPalette.Ink.copy(alpha = 0.86f),
                    contentColor = AurasPalette.Text,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, AurasPalette.Stroke),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "★",
                            color = AurasPalette.Accent,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = rating,
                            color = AurasPalette.Text,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            progress?.let { value ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(AurasPalette.Text.copy(alpha = 0.32f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(AurasPalette.Accent),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AurasCatalogShelf(
    shelf: AurasShelf,
    onOpen: (SearchResponse, Int) -> Unit,
    onExpand: (() -> Unit)?,
    posterWidth: Dp = 98.dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        val actionLabel = when {
            shelf.hasNext.not() && shelf.items.size > AURAS_POSTER_RAIL_LIMIT -> androidx.compose.ui.res.stringResource(R.string.auras_collection_see_all)
            !shelf.hasNext -> null
            shelf.isLoadingMore -> androidx.compose.ui.res.stringResource(R.string.auras_catalog_loading_more)
            shelf.pageError != null -> androidx.compose.ui.res.stringResource(R.string.auras_catalog_retry)
            else -> androidx.compose.ui.res.stringResource(R.string.auras_catalog_more)
        }
        if (shelf.items.isEmpty()) {
            AurasSectionHeading(
                title = shelf.name,
                actionLabel = actionLabel,
                onAction = if (shelf.hasNext || shelf.items.size > AURAS_POSTER_RAIL_LIMIT) onExpand else null,
                actionEnabled = !shelf.isLoadingMore,
            )
            Text(
                text = shelf.pageError?.let {
                    androidx.compose.ui.res.stringResource(R.string.auras_catalog_page_error, it)
                } ?: androidx.compose.ui.res.stringResource(R.string.auras_catalog_empty_shelf),
                color = if (shelf.pageError == null) AurasPalette.Muted else AurasPalette.Ember,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            AurasPosterRail(
                title = shelf.name,
                items = shelf.items,
                onOpen = onOpen,
                actionLabel = actionLabel,
                onAction = if (shelf.hasNext || shelf.items.size > AURAS_POSTER_RAIL_LIMIT) onExpand else null,
                actionEnabled = !shelf.isLoadingMore,
            )
            shelf.pageError?.let { message ->
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.auras_catalog_page_error, message),
                    color = AurasPalette.Ember,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun AurasPrimaryButton(label: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = AurasPalette.Accent,
        contentColor = AurasPalette.Ink,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AurasPalette.Text else AurasPalette.Accent),
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun AurasQuietButton(label: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.09f),
        contentColor = AurasPalette.Text,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AurasPalette.Accent else AurasPalette.Stroke),
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AurasSourcePicker(
    visible: Boolean,
    selectedKey: String?,
    options: List<AurasSourceOption>,
    onDismiss: () -> Unit,
    onSelect: (AurasSourceOption) -> Unit,
    onConnectSource: (() -> Unit)? = null,
) {
    if (!visible) return
    var query by remember { mutableStateOf("") }
    val matchingOptions = remember(options, query) {
        options.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AurasPalette.Surface,
        contentColor = AurasPalette.Text,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.auras_source_picker_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.auras_source_picker_search)) },
            )
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(matchingOptions, key = { _, option -> option.key }) { _, option ->
                    val selected = option.key == selectedKey
                    Surface(
                        onClick = { onSelect(option) },
                        color = if (selected) AurasPalette.AccentDeep else AurasPalette.SurfaceRaised,
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(option.label, color = AurasPalette.Text, style = MaterialTheme.typography.bodyMedium)
                            if (selected) Text("●", color = AurasPalette.Accent, fontSize = 12.sp)
                        }
                    }
                }
                if (matchingOptions.isEmpty()) item {
                    Text(
                        stringResource(R.string.auras_source_picker_no_results),
                        color = AurasPalette.Muted,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (onConnectSource != null) item {
                    AurasQuietButton(stringResource(R.string.auras_welcome_connect), onConnectSource)
                }
            }
        }
    }
}

@Composable
internal fun AurasEmptyPanel(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
) {
    Surface(
        color = AurasPalette.Surface,
        shape = RoundedCornerShape(27.dp),
        border = BorderStroke(1.dp, AurasPalette.Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 25.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.auras_orbit_mark),
                contentDescription = null,
                modifier = Modifier.size(54.dp),
                colorFilter = ColorFilter.tint(AurasPalette.Accent),
            )
            Text(title, style = MaterialTheme.typography.titleLarge, color = AurasPalette.Text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = AurasPalette.Muted)
            AurasPrimaryButton(button, onClick)
        }
    }
}

@Composable
internal fun AurasArtwork(
    url: String?,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fallbackArtwork = painterResource(R.drawable.default_cover)
    if (url.isNullOrBlank()) {
        Box(
            modifier.background(AurasPalette.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.default_cover),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        val request = remember(context, url, headers) {
            ImageRequest.Builder(context)
                .data(url)
                .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                    headerBuilder["User-Agent"] = USER_AGENT
                    headers?.forEach { (key, value) ->
                        headerBuilder[key] = value
                    }
                }.build())
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = fallbackArtwork,
            error = fallbackArtwork,
            modifier = modifier,
        )
    }
}
