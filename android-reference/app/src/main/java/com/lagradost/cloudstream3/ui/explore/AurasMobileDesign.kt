package com.lagradost.cloudstream3.ui.explore

import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

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
    sourceName: String?,
    sourceLabel: String,
    onSourceClick: () -> Unit,
    onHelpClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = AurasPalette.AccentDeep,
            border = BorderStroke(1.dp, AurasPalette.Stroke),
            modifier = Modifier.size(46.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.auras_orbit_mark),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "AURAS ORBIT",
                color = AurasPalette.Text,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = if (sourceName.isNullOrBlank()) "DISCOVER YOUR NEXT STORY" else "YOUR ORBIT · $sourceName",
                color = AurasPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            onClick = onSourceClick,
            color = AurasPalette.SurfaceRaised,
            contentColor = AurasPalette.Text,
            shape = CircleShape,
            border = BorderStroke(1.dp, AurasPalette.Stroke),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(sourceLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text("⌄", color = AurasPalette.Accent, fontSize = 16.sp)
            }
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
internal fun AurasFeaturedCard(
    item: SearchResponse?,
    onOpen: () -> Unit,
    onBrowse: () -> Unit,
) {
    Card(
        onClick = if (item == null) onBrowse else onOpen,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
        border = BorderStroke(1.dp, AurasPalette.Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 250.dp)
                .clip(RoundedCornerShape(30.dp)),
        ) {
            if (item?.posterUrl != null) {
                AurasArtwork(
                    url = item.posterUrl,
                    headers = item.posterHeaders,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(AurasPalette.AccentDeep, AurasPalette.Surface, AurasPalette.Ink)
                            )
                        ),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to AurasPalette.Ink.copy(alpha = 0.04f),
                            0.3f to AurasPalette.Ink.copy(alpha = 0.20f),
                            1f to AurasPalette.Ink.copy(alpha = 0.95f),
                        )
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 21.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (item == null) "A NEW WAY TO FIND YOUR NEXT WATCH" else "IN YOUR ORBIT",
                    color = AurasPalette.Accent,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.1.sp,
                )
                Text(
                    text = item?.name ?: "Stories, gathered around you.",
                    color = AurasPalette.Text,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item == null) {
                        "Connect a source and explore its catalogs in your own Orbit."
                    } else {
                        "A title selected from your connected catalog."
                    },
                    color = AurasPalette.Text.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AurasPrimaryButton(
                        label = if (item == null) "Connect a source" else "Open title",
                        onClick = if (item == null) onBrowse else onOpen,
                    )
                    if (item != null) AurasQuietButton("Browse catalogs", onBrowse)
                }
            }
            if (item == null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.auras_orbit_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 26.dp, end = 24.dp)
                        .size(96.dp),
                    alpha = 0.48f,
                )
            }
        }
    }
}

@Composable
internal fun AurasPosterRail(
    title: String,
    items: List<SearchResponse>,
    onOpen: (SearchResponse, Int) -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    progressByUrl: Map<String, Float> = emptyMap(),
    actionEnabled: Boolean = true,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AurasSectionHeading(title, actionLabel, onAction, actionEnabled)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            itemsIndexed(items, key = { _, item -> item.id?.toString() ?: "${item.apiName}:${item.url}" }) { index, item ->
                AurasPosterCard(
                    item = item,
                    width = 137.dp,
                    progress = progressByUrl[item.url],
                    onClick = { onOpen(item, index) },
                )
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
    val shape = RoundedCornerShape(21.dp)
    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
        border = BorderStroke(1.dp, AurasPalette.Stroke),
        modifier = Modifier.width(width),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.69f)
                .clip(shape),
        ) {
            AurasArtwork(
                url = item.posterUrl,
                headers = item.posterHeaders,
                modifier = Modifier.fillMaxSize(),
            )
            if (rank != null) {
                Surface(
                    color = AurasPalette.Ink.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, AurasPalette.Stroke),
                    modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
                ) {
                    Text(
                        text = rank.toString().padStart(2, '0'),
                        color = AurasPalette.Accent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, AurasPalette.Ink.copy(alpha = 0.94f))
                        )
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    item.name,
                    color = AurasPalette.Text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.type?.name?.replace('_', ' ')?.uppercase() ?: item.apiName,
                    color = AurasPalette.Muted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 11.dp, end = 11.dp, bottom = 5.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(AurasPalette.Text.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(AurasPalette.Accent),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AurasTopTenRail(
    title: String,
    items: List<SearchResponse>,
    onOpen: (SearchResponse, Int) -> Unit,
    onLoadMore: (() -> Unit)? = null,
) {
    val topTen = items.take(10)
    if (topTen.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AurasSectionHeading(
            title = title,
            actionLabel = if (onLoadMore != null && topTen.size < 10) "Load more" else null,
            onAction = onLoadMore?.takeIf { topTen.size < 10 },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            itemsIndexed(topTen, key = { _, item -> item.id?.toString() ?: "${item.apiName}:${item.url}" }) { index, item ->
                AurasPosterCard(
                    item = item,
                    width = 137.dp,
                    rank = index + 1,
                    onClick = { onOpen(item, index) },
                )
            }
        }
    }
}

@Composable
internal fun AurasCatalogShelf(
    shelf: AurasShelf,
    onOpen: (SearchResponse, Int) -> Unit,
    onExpand: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AurasPosterRail(
            title = shelf.name,
            items = shelf.items,
            onOpen = onOpen,
            actionLabel = when {
                !shelf.hasNext -> null
                shelf.isLoadingMore -> androidx.compose.ui.res.stringResource(R.string.auras_catalog_loading_more)
                shelf.pageError != null -> androidx.compose.ui.res.stringResource(R.string.auras_catalog_retry)
                else -> androidx.compose.ui.res.stringResource(R.string.auras_catalog_more)
            },
            onAction = if (shelf.hasNext) onExpand else null,
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

@Composable
internal fun AurasPrimaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = AurasPalette.Accent,
        contentColor = AurasPalette.Ink,
        shape = RoundedCornerShape(16.dp),
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
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.09f),
        contentColor = AurasPalette.Text,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AurasPalette.Stroke),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
internal fun AurasSourcePicker(
    visible: Boolean,
    selectedKey: String?,
    options: List<AurasSourceOption>,
    onDismiss: () -> Unit,
    onSelect: (AurasSourceOption) -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurasPalette.Surface,
        titleContentColor = AurasPalette.Text,
        textContentColor = AurasPalette.Muted,
        title = { Text("Choose your source", style = MaterialTheme.typography.titleLarge) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(options, key = { _, option -> option.key }) { _, option ->
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = AurasPalette.Accent) }
        },
    )
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
            Surface(color = AurasPalette.AccentDeep, shape = CircleShape, modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.auras_orbit_mark),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        colorFilter = ColorFilter.tint(AurasPalette.Accent),
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = AurasPalette.Text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = AurasPalette.Muted)
            AurasPrimaryButton(button, onClick)
        }
    }
}

@Composable
private fun AurasArtwork(
    url: String?,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
        AndroidView(
            factory = { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            modifier = modifier,
            update = { imageView ->
                val key = "$url|${headers?.hashCode()}"
                if (imageView.tag != key) {
                    imageView.tag = key
                    imageView.loadImage(url, headers)
                }
            },
        )
    }
}
