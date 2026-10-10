package com.lagradost.cloudstream3.ui.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.explore.AurasMobileTheme
import com.lagradost.cloudstream3.ui.explore.AurasPalette
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private data class PersonPageState(
    val requestedName: String,
    val fallbackImage: String?,
    val isLoading: Boolean = false,
    val error: String? = null,
    val candidates: List<AndroidPersonRepository.PersonCandidate> = emptyList(),
    val person: AndroidPersonRepository.PersonDetail? = null,
    val category: String = "all",
    val filterText: String = "",
    val selectedCredit: AndroidPersonRepository.PersonCredit? = null,
    val providerResults: List<PersonProviderResult> = emptyList(),
    val isSearchingProviders: Boolean = false,
)

private data class PersonProviderResult(val providerName: String, val result: SearchResponse)

class PersonFilmographyFragment : Fragment() {
    private var pageState by mutableStateOf(PersonPageState("", null))
    private var personJob: Job? = null
    private var providerJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                PersonFilmographyScreen(
                    state = pageState,
                    onBack = ::handleBack,
                    onRetry = ::loadCandidates,
                    onSelectCandidate = ::loadPerson,
                    onCategorySelected = { pageState = pageState.copy(category = it) },
                    onFilterChanged = { pageState = pageState.copy(filterText = it) },
                    onCreditSelected = ::searchProvidersForCredit,
                    onCreditBack = ::clearSelectedCredit,
                    onOpenResult = { result ->
                        requireActivity().loadResult(result.url, result.apiName, result.name)
                    },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val name = requireArguments().getString("personName").orEmpty()
        val image = requireArguments().getString("personImage")
        pageState = PersonPageState(name, image)
        val personId = requireArguments().getInt("personId", -1)
        if (personId > 0) {
            loadPerson(
                AndroidPersonRepository.PersonCandidate(
                    id = personId,
                    name = name,
                    profileUrl = image,
                    department = null,
                    knownFor = emptyList(),
                )
            )
        } else {
            loadCandidates()
        }
    }

    override fun onDestroyView() {
        personJob?.cancel()
        providerJob?.cancel()
        super.onDestroyView()
    }

    private fun handleBack() {
        if (pageState.selectedCredit != null) clearSelectedCredit()
        else findNavController().navigateUp()
    }

    private fun loadCandidates() {
        val name = pageState.requestedName.trim()
        if (name.isEmpty()) {
            pageState = pageState.copy(isLoading = false, error = getString(R.string.person_missing_name))
            return
        }
        personJob?.cancel()
        personJob = viewLifecycleOwner.lifecycleScope.launch {
            pageState = pageState.copy(isLoading = true, error = null, candidates = emptyList(), person = null)
            try {
                val candidates = withContext(Dispatchers.IO) { AndroidPersonRepository.searchPeople(name) }
                when {
                    candidates.isEmpty() -> pageState = pageState.copy(
                        isLoading = false,
                        error = getString(R.string.person_no_matches, name),
                    )
                    candidates.size == 1 -> loadPerson(candidates.single())
                    else -> pageState = pageState.copy(isLoading = false, candidates = candidates)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageState = pageState.copy(isLoading = false, error = e.message ?: getString(R.string.person_load_failed))
            }
        }
    }

    private fun loadPerson(candidate: AndroidPersonRepository.PersonCandidate) {
        personJob?.cancel()
        personJob = viewLifecycleOwner.lifecycleScope.launch {
            pageState = pageState.copy(isLoading = true, error = null, candidates = emptyList(), person = null)
            try {
                val person = withContext(Dispatchers.IO) { AndroidPersonRepository.loadPerson(candidate.id) }
                pageState = if (person != null) pageState.copy(isLoading = false, person = person)
                else pageState.copy(isLoading = false, error = getString(R.string.person_load_failed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageState = pageState.copy(isLoading = false, error = e.message ?: getString(R.string.person_load_failed))
            }
        }
    }

    private fun searchProvidersForCredit(credit: AndroidPersonRepository.PersonCredit) {
        providerJob?.cancel()
        pageState = pageState.copy(
            selectedCredit = credit,
            providerResults = emptyList(),
            isSearchingProviders = true,
        )
        providerJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repositories = apis.withLock {
                    apis.filter { it.providerType == ProviderType.DirectProvider }.map(::APIRepository)
                }
                val semaphore = Semaphore(8)
                val results = withContext(Dispatchers.IO) {
                    repositories.map { repository ->
                        async {
                            semaphore.withPermit {
                                val resource = repository.search(credit.title, 1)
                                val items = if (resource is Resource.Success) resource.value.items else emptyList()
                                repository.name to items
                            }
                        }
                    }.awaitAll()
                }
                val matches = results.flatMap { (providerName, entries) ->
                    entries.filter { isRelevantTitle(credit.title, it.name) }
                        .map { PersonProviderResult(providerName, it) }
                }
                pageState = pageState.copy(
                    providerResults = matches.distinctBy { "${it.providerName}:${it.result.url}" },
                    isSearchingProviders = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pageState = pageState.copy(isSearchingProviders = false)
            }
        }
    }

    private fun clearSelectedCredit() {
        providerJob?.cancel()
        pageState = pageState.copy(
            selectedCredit = null,
            providerResults = emptyList(),
            isSearchingProviders = false,
        )
    }

    private fun isRelevantTitle(query: String, resultName: String): Boolean {
        val q = normalize(query)
        val result = normalize(resultName)
        if (q.isEmpty() || result.isEmpty()) return false
        if (result.contains(q) || q.contains(result)) return true
        val tokens = q.split(" ").filter { it.length > 2 }
        return tokens.isNotEmpty() && tokens.count { it in result }.toDouble() / tokens.size >= 0.5
    }

    private fun normalize(text: String) = text.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

@Composable
private fun PersonFilmographyScreen(
    state: PersonPageState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectCandidate: (AndroidPersonRepository.PersonCandidate) -> Unit,
    onCategorySelected: (String) -> Unit,
    onFilterChanged: (String) -> Unit,
    onCreditSelected: (AndroidPersonRepository.PersonCredit) -> Unit,
    onCreditBack: () -> Unit,
    onOpenResult: (SearchResponse) -> Unit,
) {
    BackHandler(enabled = state.selectedCredit != null, onBack = onCreditBack)
    val shownCredits = state.person?.credits.orEmpty().filter { credit ->
        (state.category == "all" || credit.mediaType == state.category) &&
            (state.filterText.isBlank() || credit.title.contains(state.filterText, ignoreCase = true) ||
                credit.credit.orEmpty().contains(state.filterText, ignoreCase = true))
    }

    Column(Modifier.fillMaxSize().background(AurasPalette.Canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = if (state.selectedCredit != null) onCreditBack else onBack) {
                Text(stringResourceText(if (state.selectedCredit != null) R.string.person_back_to_filmography else R.string.person_back))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.selectedCredit?.title ?: state.person?.name ?: state.requestedName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AurasPalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            state.isLoading -> CenterState(stringResourceText(R.string.person_loading)) {
                CircularProgressIndicator(color = AurasPalette.Accent)
            }
            state.error != null -> CenterState(state.error) {
                TextButton(onClick = onRetry) { Text(stringResourceText(R.string.person_retry)) }
            }
            state.candidates.isNotEmpty() && state.person == null -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            stringResourceText(R.string.person_choose_match, state.requestedName),
                            color = AurasPalette.Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(state.candidates, key = { it.id }) { candidate ->
                        Card(
                            onClick = { onSelectCandidate(candidate) },
                            colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = candidate.profileUrl ?: state.fallbackImage,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(58.dp).clip(CircleShape),
                                )
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(candidate.name, color = AurasPalette.Text, fontWeight = FontWeight.SemiBold)
                                    Text(candidate.department.orEmpty(), color = AurasPalette.Muted, style = MaterialTheme.typography.bodySmall)
                                    if (candidate.knownFor.isNotEmpty()) {
                                        Text(candidate.knownFor.joinToString(), color = AurasPalette.Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            state.selectedCredit != null -> ProviderCreditResults(
                state = state,
                onOpenResult = onOpenResult,
            )
            state.person != null -> PersonCredits(
                state = state,
                credits = shownCredits,
                onCategorySelected = onCategorySelected,
                onFilterChanged = onFilterChanged,
                onCreditSelected = onCreditSelected,
            )
        }
    }
}

@Composable
private fun PersonCredits(
    state: PersonPageState,
    credits: List<AndroidPersonRepository.PersonCredit>,
    onCategorySelected: (String) -> Unit,
    onFilterChanged: (String) -> Unit,
    onCreditSelected: (AndroidPersonRepository.PersonCredit) -> Unit,
) {
    val person = state.person ?: return
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 6.dp)) {
                AsyncImage(
                    model = person.profileUrl ?: state.fallbackImage,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(112.dp).height(154.dp).clip(RoundedCornerShape(14.dp)),
                )
                Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(person.name, color = AurasPalette.Text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(person.department.orEmpty(), color = AurasPalette.Accent, style = MaterialTheme.typography.labelLarge)
                    Text(
                        listOfNotNull(person.birthday, person.deathday?.let { "– $it" }, person.placeOfBirth)
                            .joinToString(" · "),
                        color = AurasPalette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            person.biography?.let { bio ->
                Text(
                    bio,
                    color = AurasPalette.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("all" to R.string.person_all, "movie" to R.string.person_movies, "tv" to R.string.person_tv)
                    .forEach { (category, label) ->
                        val isSelected = state.category == category
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.Tab,
                                    onClick = { onCategorySelected(category) },
                                )
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResourceText(label),
                                color = if (isSelected) AurasPalette.Text else AurasPalette.Muted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.fillMaxWidth().height(2.dp).background(
                                    if (isSelected) AurasPalette.Accent else AurasPalette.Canvas
                                )
                            )
                        }
                    }
            }
            OutlinedTextField(
                value = state.filterText,
                onValueChange = onFilterChanged,
                label = { Text(stringResourceText(R.string.person_filter_credits)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                pluralStringResource(R.plurals.person_credit_count, credits.size, credits.size),
                color = AurasPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
        }
        items(credits, key = { "${it.mediaType}:${it.id}" }) { credit ->
            Card(
                onClick = { onCreditSelected(credit) },
                colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = credit.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(58.dp)
                                        .aspectRatio(com.lagradost.cloudstream3.ui.PORTRAIT_POSTER_ASPECT_RATIO)
                                        .clip(RoundedCornerShape(8.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(credit.title, color = AurasPalette.Text, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                if (credit.mediaType == "tv") "TV" else "Movie",
                                credit.year,
                                credit.credit,
                            ).joinToString(" · "),
                            color = AurasPalette.Accent,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        credit.overview?.let {
                            Text(it, color = AurasPalette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(stringResourceText(R.string.person_find_on_providers), color = AurasPalette.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (credits.isEmpty()) item { Text(stringResourceText(R.string.person_no_credits), color = AurasPalette.Muted) }
    }
}

@Composable
private fun ProviderCreditResults(state: PersonPageState, onOpenResult: (SearchResponse) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResourceText(R.string.person_provider_search_intro, state.selectedCredit?.title.orEmpty()),
                color = AurasPalette.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (state.isSearchingProviders) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AurasPalette.Accent, strokeWidth = 2.dp)
                Text(stringResourceText(R.string.person_searching_providers), color = AurasPalette.Muted, modifier = Modifier.padding(start = 12.dp))
            }
        }
        items(state.providerResults, key = { "${it.providerName}:${it.result.url}" }) { match ->
            val result = match.result
            Card(
                onClick = { onOpenResult(result) },
                colors = CardDefaults.cardColors(containerColor = AurasPalette.Surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(result.name, color = AurasPalette.Text, fontWeight = FontWeight.SemiBold)
                    Text(match.providerName, color = AurasPalette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!state.isSearchingProviders && state.providerResults.isEmpty()) {
            item { Text(stringResourceText(R.string.person_no_provider_matches), color = AurasPalette.Muted) }
        }
    }
}

@Composable
private fun CenterState(text: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = AurasPalette.Text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun stringResourceText(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
