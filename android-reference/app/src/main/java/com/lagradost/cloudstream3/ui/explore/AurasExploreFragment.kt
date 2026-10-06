package com.lagradost.cloudstream3.ui.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.compose.ui.unit.Dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

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

/** Auras-owned discovery landing page. The provider, search and playback flows remain in the
 * existing Android application; this page gives them an Orbit-native front door. */
class AurasExploreFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val providerCount = remember {
                APIHolder.apis.withLock { APIHolder.apis.count { it.hasMainPage } }
            }

            AurasExploreScreen(
                isTv = isLayout(TV),
                providerCount = providerCount,
                onCatalogs = { openDestination(R.id.navigation_catalogs) },
                onSearch = { openDestination(R.id.navigation_search) },
                onSources = { openDestination(R.id.navigation_settings_extensions) },
                onLibrary = { openDestination(R.id.navigation_library) },
            )
        }
    }

    private fun openDestination(destination: Int) {
        if (findNavController().currentDestination?.id != destination) {
            findNavController().navigate(destination)
        }
    }
}

@Composable
private fun AurasExploreScreen(
    isTv: Boolean,
    providerCount: Int,
    onCatalogs: () -> Unit,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onLibrary: () -> Unit,
) {
    val firstActionFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) {
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1120.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = contentPadding, vertical = if (wide) 34.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 22.dp),
        ) {
            BrandHeading()

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "Explore",
                    color = AurasExplorePalette.Text,
                    fontSize = if (wide) 40.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.7).sp,
                )
                Text(
                    text = "Find something to watch across your connected catalogs.",
                    color = AurasExplorePalette.Muted,
                    fontSize = if (wide) 17.sp else 15.sp,
                    lineHeight = if (wide) 25.sp else 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            ExploreHero(
                wide = wide,
                providerCount = providerCount,
                firstActionFocus = firstActionFocus,
                onCatalogs = onCatalogs,
                onSearch = onSearch,
            )

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Discover",
                    color = AurasExplorePalette.Text,
                    fontSize = if (wide) 22.sp else 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                val tiles = listOf(
                    ExploreTileData(
                        title = "Catalogs",
                        description = "Browse collections from your sources.",
                        icon = R.drawable.baseline_grid_view_24,
                        onClick = onCatalogs,
                    ),
                    ExploreTileData(
                        title = "Search",
                        description = "Look across connected catalogs.",
                        icon = R.drawable.search_icon,
                        onClick = onSearch,
                    ),
                    ExploreTileData(
                        title = "Sources",
                        description = if (providerCount > 0) {
                            "$providerCount source${if (providerCount == 1) "" else "s"} available on this device."
                        } else {
                            "Choose the catalogs you want to connect."
                        },
                        icon = R.drawable.ic_baseline_extension_24,
                        onClick = onSources,
                    ),
                    ExploreTileData(
                        title = "Library",
                        description = "Return to saved titles and activity.",
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
                            text = if (providerCount > 0) "Your Orbit is ready" else "Make Orbit yours",
                            color = AurasExplorePalette.Text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = if (providerCount > 0) {
                                "Your sources stay on this device. Add or manage them any time."
                            } else {
                                "Connect a source to bring its catalogs into one place."
                            },
                            color = AurasExplorePalette.Muted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    QuietAction(label = "Sources", onClick = onSources)
                }
            }
        }
    }
}

@Composable
private fun BrandHeading() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Surface(
            color = Color(0xFF292437),
            shape = RoundedCornerShape(13.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.auras_orbit_mark),
                    contentDescription = null,
                    modifier = Modifier.size(29.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "AURAS ORBIT",
                color = AurasExplorePalette.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = "Your personal watch space",
                color = AurasExplorePalette.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ExploreHero(
    wide: Boolean,
    providerCount: Int,
    firstActionFocus: FocusRequester,
    onCatalogs: () -> Unit,
    onSearch: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = if (providerCount > 0) "A world of catalogs,\none Orbit." else "One place for every\nway you watch.",
            color = AurasExplorePalette.Text,
            fontSize = 25.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.25).sp,
        )
        Text(
            text = "Bring your favorite sources together, then explore their catalogs from one calm space.",
            color = AurasExplorePalette.Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction(label = "Open catalogs", onClick = onCatalogs, focusRequester = firstActionFocus)
            QuietAction(label = "Search", onClick = onSearch)
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
        Surface(color = Color(0xFF302A42), shape = CircleShape, modifier = Modifier.size(centerMarkSize + 18.dp)) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.auras_orbit_mark),
                    contentDescription = null,
                    modifier = Modifier.size(centerMarkSize),
                )
            }
        }
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
                Text("↗", color = AurasExplorePalette.Muted, fontSize = 17.sp)
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
private fun QuietAction(label: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.055f),
        contentColor = AurasExplorePalette.Text,
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
