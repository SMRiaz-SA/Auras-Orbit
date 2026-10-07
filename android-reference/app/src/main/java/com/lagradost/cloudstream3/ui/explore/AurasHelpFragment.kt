package com.lagradost.cloudstream3.ui.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.R

private data class AurasHelpTopic(
    @StringRes val section: Int,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @ArrayRes val steps: Int,
    @StringRes val searchTerms: Int,
    @StringRes val recovery: Int,
    val destination: Int?,
    val tmdbAttribution: Boolean = false,
)

private const val TMDB_OFFICIAL_LOGO_URL = "https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_1-5bdc75aaebeb75dc7ae79426ddd9be3b2be1e342510f8202baf6bffa71d7f5c4.svg"

private val aurasHelpTopics = listOf(
    AurasHelpTopic(R.string.auras_help_section_start, R.string.auras_help_connect_title, R.string.auras_help_connect_summary, R.array.auras_help_connect_steps, R.string.auras_help_connect_terms, R.string.auras_help_connect_recovery, R.id.navigation_settings_extensions),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_catalogs_title, R.string.auras_help_catalogs_summary, R.array.auras_help_catalogs_steps, R.string.auras_help_catalogs_terms, R.string.auras_help_catalogs_recovery, R.id.navigation_auras_catalogs),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_topten_title, R.string.auras_help_topten_summary, R.array.auras_help_topten_steps, R.string.auras_help_topten_terms, R.string.auras_help_topten_recovery, R.id.navigation_home),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_search_title, R.string.auras_help_search_summary, R.array.auras_help_search_steps, R.string.auras_help_search_terms, R.string.auras_help_search_recovery, R.id.navigation_search),
    AurasHelpTopic(R.string.auras_help_section_people, R.string.auras_help_people_title, R.string.auras_help_people_summary, R.array.auras_help_people_steps, R.string.auras_help_people_terms, R.string.auras_help_people_recovery, R.id.navigation_search),
    AurasHelpTopic(R.string.auras_help_section_about, R.string.auras_help_tmdb_title, R.string.auras_help_tmdb_summary, R.array.auras_help_tmdb_steps, R.string.auras_help_tmdb_terms, R.string.auras_help_tmdb_recovery, null, true),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_titleplay_title, R.string.auras_help_titleplay_summary, R.array.auras_help_titleplay_steps, R.string.auras_help_titleplay_terms, R.string.auras_help_titleplay_recovery, R.id.navigation_search),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_audio_title, R.string.auras_help_audio_summary, R.array.auras_help_audio_steps, R.string.auras_help_audio_terms, R.string.auras_help_audio_recovery, R.id.navigation_search),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_library_title, R.string.auras_help_library_summary, R.array.auras_help_library_steps, R.string.auras_help_library_terms, R.string.auras_help_library_recovery, R.id.navigation_library),
    AurasHelpTopic(R.string.auras_help_section_catalogs, R.string.auras_help_downloads_title, R.string.auras_help_downloads_summary, R.array.auras_help_downloads_steps, R.string.auras_help_downloads_terms, R.string.auras_help_downloads_recovery, R.id.navigation_downloads),
    AurasHelpTopic(R.string.auras_help_section_recovery, R.string.auras_help_settings_title, R.string.auras_help_settings_summary, R.array.auras_help_settings_steps, R.string.auras_help_settings_terms, R.string.auras_help_settings_recovery, R.id.navigation_settings),
)

/** Searchable, bundled Android guide. All article content is Android string resources so it can be translated. */
class AurasHelpFragment : Fragment() {
    private var query by mutableStateOf("")
    private var selectedTopic by mutableStateOf<AurasHelpTopic?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                AurasHelpScreen(
                    query = query,
                    selectedTopic = selectedTopic,
                    onQueryChange = { query = it },
                    onSelectTopic = { selectedTopic = it },
                    onBackToTopics = { selectedTopic = null },
                    onBack = { findNavController().navigateUp() },
                    onOpenDestination = { destination -> findNavController().navigate(destination) },
                )
            }
        }
    }
}

@Composable
private fun AurasHelpScreen(
    query: String,
    selectedTopic: AurasHelpTopic?,
    onQueryChange: (String) -> Unit,
    onSelectTopic: (AurasHelpTopic) -> Unit,
    onBackToTopics: () -> Unit,
    onBack: () -> Unit,
    onOpenDestination: (Int) -> Unit,
) {
    val context = LocalContext.current
    val filteredTopics = remember(query, context) {
        val needle = query.trim()
        if (needle.isBlank()) aurasHelpTopics else aurasHelpTopics.filter { topic ->
            val indexedText = listOf(
                context.getString(topic.section),
                context.getString(topic.title),
                context.getString(topic.summary),
                context.getString(topic.searchTerms),
                context.getString(topic.recovery),
                *context.resources.getStringArray(topic.steps),
            )
            indexedText.any { it.contains(needle, ignoreCase = true) }
        }
    }
    val selectedSteps = selectedTopic?.let { context.resources.getStringArray(it.steps).toList() }.orEmpty()

    BackHandler(enabled = selectedTopic != null, onBack = onBackToTopics)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurasPalette.Canvas),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AurasQuietButton(
                        label = stringResource(
                            if (selectedTopic == null) R.string.auras_help_back_to_explore
                            else R.string.auras_help_back_to_topics
                        ),
                        onClick = if (selectedTopic == null) onBack else onBackToTopics,
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AurasPageTitle(
                        title = selectedTopic?.let { stringResource(it.title) }
                            ?: stringResource(R.string.auras_help_manual_title),
                        subtitle = selectedTopic?.let { stringResource(it.summary) }
                            ?: stringResource(R.string.auras_help_intro),
                    )
                }
            }

            if (selectedTopic != null) {
                if (selectedTopic.tmdbAttribution) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AsyncImage(
                                model = TMDB_OFFICIAL_LOGO_URL,
                                contentDescription = stringResource(R.string.auras_help_tmdb_logo_description),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(58.dp),
                            )
                            Text(
                                stringResource(R.string.auras_help_tmdb_attribution),
                                color = AurasPalette.Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.auras_help_steps_heading),
                        color = AurasPalette.Accent,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                itemsIndexed(selectedSteps, key = { index, _ -> "step-$index" }) { index, step ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AurasPalette.Stroke),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(15.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                color = AurasPalette.Accent,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(step, color = AurasPalette.Text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AurasPalette.SurfaceRaised),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AurasPalette.Stroke),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.auras_help_recovery_heading),
                                color = AurasPalette.Ember,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                stringResource(selectedTopic.recovery),
                                color = AurasPalette.Text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                selectedTopic.destination?.let { destination ->
                    item {
                        AurasPrimaryButton(
                            label = stringResource(R.string.auras_help_open_screen),
                            onClick = { onOpenDestination(destination) },
                        )
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.auras_help_search_hint)) },
                    )
                }
                if (filteredTopics.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.auras_help_no_results),
                            color = AurasPalette.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    filteredTopics.groupBy { it.section }.forEach { (section, topics) ->
                        item(key = "help-section-$section") {
                            Text(
                                stringResource(section),
                                color = AurasPalette.Accent,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        items(topics, key = { it.title }) { topic ->
                            Card(
                                onClick = { onSelectTopic(topic) },
                                colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AurasPalette.Stroke),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        stringResource(topic.title),
                                        color = AurasPalette.Text,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(topic.summary),
                                        color = AurasPalette.Muted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
