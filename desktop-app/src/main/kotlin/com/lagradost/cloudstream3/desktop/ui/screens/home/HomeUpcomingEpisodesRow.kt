package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.screens.details.UpcomingEpisode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun HomeUpcomingEpisodesRow(
    followedSeriesCount: Int,
    episodes: List<UpcomingEpisode>,
    isRefreshing: Boolean,
    refreshError: String?,
    onRefresh: () -> Unit,
    onOpenEpisode: (UpcomingEpisode) -> Unit,
) {
    AnimatedVisibility(visible = followedSeriesCount > 0) {
        val safeArea = com.lagradost.cloudstream3.desktop.ui.LocalSafeArea.current
        val layoutDirection = LocalLayoutDirection.current
        val safeStart = safeArea.calculateStartPadding(layoutDirection)
        val safeEnd = safeArea.calculateEndPadding(layoutDirection)
        val today = remember { LocalDate.now() }
        var weekOffset by remember { mutableIntStateOf(0) }
        var isWeekExpanded by remember { mutableStateOf(false) }
        val weekStart = remember(today, weekOffset) {
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
        }
        val maxWeekOffset = remember(today) {
            ChronoUnit.WEEKS.between(
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                today.plusDays(90),
            ).toInt()
        }
        val weekDays = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
        var selectedDate by remember(weekStart) { mutableStateOf(weekStart.coerceAtLeast(today)) }
        val upcomingEpisodes = remember(episodes, today) {
            episodes.filter { !it.airDate.isBefore(today) }.sortedBy { it.airDate }
        }
        val currentWeekEvents = remember(upcomingEpisodes, weekStart) {
            val weekEnd = weekStart.plusDays(6)
            upcomingEpisodes.filter { !it.airDate.isBefore(weekStart) && !it.airDate.isAfter(weekEnd) }
        }

        LaunchedEffect(weekStart, currentWeekEvents) {
            if (selectedDate.isBefore(weekStart) || selectedDate.isAfter(weekStart.plusDays(6))) {
                selectedDate = currentWeekEvents.firstOrNull()?.airDate ?: weekStart.coerceAtLeast(today)
            }
            if (currentWeekEvents.none { it.airDate == selectedDate }) {
                selectedDate = currentWeekEvents.firstOrNull()?.airDate ?: weekStart.coerceAtLeast(today)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = safeStart, end = safeEnd, top = 8.dp, bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Coming up",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Following $followedSeriesCount ${if (followedSeriesCount == 1) "show" else "shows"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { isWeekExpanded = !isWeekExpanded }) {
                        Text(if (isWeekExpanded) "Hide week" else "Show week")
                    }
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh release dates")
                        }
                    }
                }

                if (isWeekExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButtonLikeWeekArrow(label = "‹", enabled = weekOffset > 0) { weekOffset-- }
                        Text(
                            text = "${weekStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${weekStart.dayOfMonth}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButtonLikeWeekArrow(label = "›", enabled = weekOffset < maxWeekOffset) {
                            weekOffset++
                            selectedDate = weekStart.plusWeeks(1).coerceAtLeast(today)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        weekDays.forEach { date ->
                            val count = currentWeekEvents.count { it.airDate == date }
                            val enabled = !date.isBefore(today)
                            FilterChip(
                                selected = selectedDate == date,
                                onClick = { selectedDate = date },
                                enabled = enabled,
                                label = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                                        Text(date.dayOfMonth.toString(), fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal)
                                        if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val dayEvents = currentWeekEvents.filter { it.airDate == selectedDate }
                    if (dayEvents.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            dayEvents.take(5).forEach { episode ->
                                UpcomingEpisodeItem(episode = episode, onClick = { onOpenEpisode(episode) })
                            }
                            if (dayEvents.size > 5) {
                                Text(
                                    "+${dayEvents.size - 5} more episodes",
                                    modifier = Modifier.padding(start = 54.dp, top = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (upcomingEpisodes.isEmpty()) {
                        Text(
                            text = when {
                                isRefreshing -> "Checking followed shows for release dates…"
                                refreshError != null -> refreshError
                                else -> "No upcoming release dates are cached yet. Refresh to check followed shows."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            "No releases on this day. Choose another date or browse the next week.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else if (upcomingEpisodes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        upcomingEpisodes.take(3).forEach { episode ->
                            UpcomingEpisodeItem(episode = episode, onClick = { onOpenEpisode(episode) })
                        }
                        if (upcomingEpisodes.size > 3) {
                            Text(
                                "+${upcomingEpisodes.size - 3} more upcoming",
                                modifier = Modifier.padding(start = 54.dp, top = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        text = when {
                            isRefreshing -> "Checking followed shows for release dates…"
                            refreshError != null -> refreshError
                            else -> "No upcoming release dates are cached yet. Refresh to check followed shows."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                if (refreshError != null && upcomingEpisodes.isNotEmpty()) {
                    Text(
                        "Some release dates could not be refreshed. Showing saved dates.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingEpisodeItem(episode: UpcomingEpisode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val showName = episode.showName.trim().ifEmpty { "Followed series" }
        val episodeName = episode.episodeName?.trim()?.takeIf(String::isNotEmpty)
            ?: "Episode ${episode.episodeNumber}"

        Column(
            modifier = Modifier
                .size(42.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "S${episode.seasonNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "E${episode.episodeNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).widthIn(min = 0.dp).padding(end = 12.dp)) {
            Text(
                showName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                episodeName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${episode.airDate.dayOfMonth} ${episode.airDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}",
            modifier = Modifier.widthIn(min = 54.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun TextButtonLikeWeekArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .size(28.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        style = MaterialTheme.typography.titleLarge,
    )
}

private fun LocalDate.coerceAtLeast(minimum: LocalDate): LocalDate = if (isBefore(minimum)) minimum else this
