package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.components.CategoryRowWithHeader
import com.lagradost.cloudstream3.desktop.ui.components.PosterCard
import com.lagradost.cloudstream3.desktop.ui.screens.home.contract.HomeCategoryUiState

@Composable
fun HomeCategorySection(
    pageData: MainPageData,
    provider: MainAPI,
    categoryState: HomeCategoryUiState?,
    onLoadCategory: () -> Unit,
    isFirstPage: Boolean = false,
    heroMetaMap: Map<String, com.lagradost.cloudstream3.desktop.repo.HeroMeta>,
    allBookmarks: Map<String, com.lagradost.common.storage.DesktopBookmark>,
    onPrefetchHeroItem: (MainAPI?, SearchResponse) -> Unit,
    onHeroBackgroundChanged: (String?) -> Unit,
    outerPadding: androidx.compose.ui.unit.Dp = 0.dp,
    afterHeroContent: @Composable () -> Unit = {},
    isHistoryVisible: Boolean = false,
    onViewAll: (MainAPI, com.lagradost.cloudstream3.MainPageData, String, List<SearchResponse>, Boolean) -> Unit,
    onItemClick: (MainAPI, SearchResponse, String?, Boolean) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(pageData, provider) {
        visible = true
        if (categoryState == null || (categoryState.response == null && categoryState.error == null)) {
            onLoadCategory()
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "alpha",
    )
    val heroEnabled by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.heroEnabled.collectAsState()
    val homeVerticalSpacingDp by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.homeVerticalSpacingDp.collectAsState()

    val hp = categoryState?.response
    val isLoading = categoryState?.isLoading ?: (hp == null && categoryState?.error == null)
    val errorMessage = categoryState?.error

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(homeVerticalSpacingDp.dp),
    ) {
        if (isLoading) {
            if (isFirstPage) {
                HomeHeroCarouselPlaceholder()
                afterHeroContent()
            } else {
                CategoryRowPlaceholder(
                    title = pageData.name,
                    showLargeHeader = !isFirstPage,
                    outerPadding = outerPadding,
                )
            }
        } else {
            val visibleSections = hp?.items.orEmpty().filter { section ->
                val title = section.name.takeIf { it.isNotBlank() } ?: pageData.name
                !isHiddenHomeCatalogTitle(title)
            }

            if (visibleSections.isNotEmpty()) {
                visibleSections.forEachIndexed { sectionIndex, section ->
                    if (heroEnabled && isFirstPage && sectionIndex == 0 && section.list.size >= 3) {
                        val heroCandidates = remember(visibleSections) {
                            visibleSections.flatMap { it.list }.distinctBy { it.url }.take(30)
                        }
                        HomeHeroCarousel(
                            items = heroCandidates,
                            provider = provider,
                            heroMetaMap = heroMetaMap,
                            allBookmarks = allBookmarks,
                            onPrefetchHeroItem = onPrefetchHeroItem,
                            onHeroBackgroundChanged = onHeroBackgroundChanged,
                            onItemClick = { item, backdrop, autoPlay -> onItemClick(provider, item, backdrop, autoPlay) },
                        )
                        afterHeroContent()
                    } else {
                        val isFirstRowOfFirstPage = isFirstPage && sectionIndex == 0
                        if (isFirstRowOfFirstPage) {
                            afterHeroContent()
                        }
                        val titleStr = section.name.takeIf { it.isNotBlank() } ?: pageData.name
                        val showLargeHeader = sectionIndex == 0 && !isFirstPage && !titleStr.equals(pageData.name, ignoreCase = true)

                        val safeArea = com.lagradost.cloudstream3.desktop.ui.LocalSafeArea.current
                        val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                        val paddingStart = maxOf(
                            safeArea.calculateStartPadding(layoutDirection),
                            outerPadding,
                        )
                        val paddingEnd = maxOf(
                            safeArea.calculateEndPadding(layoutDirection),
                            outerPadding,
                        )

                        if (showLargeHeader) {
                            Text(
                                text = pageData.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = paddingStart + 10.dp, top = 24.dp, bottom = 0.dp),
                            )
                        }

                        val isHorizontalCategory = pageData.horizontalImages || section.list.any { it.type == com.lagradost.cloudstream3.TvType.Live || it.posterHeaders?.containsKey("landscape") == true }
                        val categoryAspectRatio = if (isHorizontalCategory) 16f / 9f else 2f / 3f

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = paddingStart, end = paddingEnd),
                        ) {
                            val availableWidth = this.maxWidth
                            val isCompact = availableWidth < 600.dp

                            val posterWidthDp by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.posterWidthDp.collectAsState()
                            val homeSpacingDp by com.lagradost.cloudstream3.desktop.ui.theme.AppearanceConfig.homeSpacingDp.collectAsState()

                            val spacingDp = if (isCompact) 8.dp else homeSpacingDp.dp

                            val optimalItemWidth = if (isCompact) {
                                if (isHorizontalCategory) 160.dp else 115.dp
                            } else {
                                val baseWidth = if (isHorizontalCategory) (posterWidthDp.dp * 1.45f) else posterWidthDp.dp
                                // Subtract 20.dp (10.dp start + 10.dp end horizontal content padding) from availableWidth
                                val netWidth = availableWidth - 20.dp
                                val exactColumns = (netWidth + spacingDp) / (baseWidth + spacingDp)
                                val columns = exactColumns.toInt().coerceAtLeast(1)
                                ((netWidth + spacingDp) / columns) - spacingDp
                            }

                            CategoryRowWithHeader(
                                modifier = Modifier.fillMaxWidth(),
                                title = titleStr,
                                itemCount = section.list.size,
                                onViewAll = { onViewAll(provider, pageData, section.name, section.list, hp?.hasNext == true) },
                                rowContentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = if (isCompact) 4.dp else 10.dp,
                                    vertical = if (isCompact) 4.dp else (4.dp + (homeVerticalSpacingDp * 0.25f).dp),
                                ),
                                headerPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = 10.dp,
                                    end = 10.dp,
                                    top = (4.dp + (homeVerticalSpacingDp * 0.35f).dp),
                                    bottom = 4.dp,
                                ),
                                itemSpacing = spacingDp,
                            ) {
                                items(
                                    count = section.list.size,
                                    key = { index ->
                                        "${section.list[index].url}_$index"
                                    },
                                ) { index ->
                                    val posterItem = section.list[index]
                                    PosterCard(
                                        item = posterItem,
                                        provider = provider,
                                        itemWidth = optimalItemWidth,
                                        aspectRatio = categoryAspectRatio,
                                        onClick = { onItemClick(provider, posterItem, null, false) },
                                        onPlayClick = { onItemClick(provider, posterItem, null, true) },
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (errorMessage != null) {
                val safeArea = com.lagradost.cloudstream3.desktop.ui.LocalSafeArea.current
                val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                val paddingStart = maxOf(
                    safeArea.calculateStartPadding(layoutDirection),
                    outerPadding,
                )
                val paddingEnd = maxOf(
                    safeArea.calculateEndPadding(layoutDirection),
                    outerPadding,
                )

                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = paddingStart + 10.dp, end = paddingEnd + 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${pageData.name}: $errorMessage",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onLoadCategory,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (isFirstPage) afterHeroContent()
            } else if (isFirstPage) {
                afterHeroContent()
            }
        }
    }
}
