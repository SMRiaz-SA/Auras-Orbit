package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSearchBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.currentSpan
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.updateChips
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BaseFragment.BindingCreator.Bind(FragmentSearchBinding::bind)
) {
    companion object {
        fun List<SearchResponse>.filterSearchResponse(): List<SearchResponse> {
            return this.filter { response ->
                if (response is AnimeSearchResponse) {
                    val status = response.dubStatus
                    (status.isNullOrEmpty()) || (status.any {
                        APIRepository.dubStatusActive.contains(it)
                    })
                } else {
                    true
                }
            }
        }

        const val SEARCH_QUERY = "search_query"

        fun newInstance(query: String): Bundle {
            return Bundle().apply {
                if (query.isNotBlank()) putString(SEARCH_QUERY, query)
            }
        }
    }

    private val searchViewModel: SearchViewModel by activityViewModels()
    private var bottomSheetDialog: BottomSheetDialog? = null

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.mainSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun pickLayout(): Int? =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_search_tv else R.layout.fragment_search

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        hideKeyboard()
        bottomSheetDialog?.ownHide()
        activity?.detachBackPressedCallback("SearchFragment")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        searchViewModel.clearSuggestions()
        reloadRepos()
        afterPluginsLoadedEvent += ::reloadRepos
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()

    /**
     * Will filter all providers by preferred media and selectedSearchTypes.
     * If that results in no available providers then only filter
     * providers by preferred media
     **/
    fun search(query: String?) {
        if (query == null) return
        searchViewModel.clearSuggestions()
        // don't resume state from prev search
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            val notFilteredBySelectedTypes = selectedApis.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            searchViewModel.searchAndCancel(
                query = query,
                providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                    types?.any { selectedSearchTypes.contains(it) } == true
                }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()
            )
        }
    }

    // Null if defined as a variable
    // This needs to be run after view created

    private fun reloadRepos(success: Boolean = false) = main {
        searchViewModel.reloadRepos()
        if (!DataStoreHelper.hasSearchPreferenceProviders) {
            // Keep the default provider set in sync when extensions become available.
            selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()
        }
        context?.filterProviderByPreferredMedia(hasHomePageIsRequired = false)?.let { validAPIs ->
            val chips = binding?.tvtypesChipsScroll?.tvtypesChips
            val validTypes = validAPIs.flatMap { api -> api.supportedTypes }.distinct()
            bindChips(
                chips,
                selectedSearchTypes,
                validTypes
            ) { list ->
                if (selectedSearchTypes.toSet() != list.toSet()) {
                    DataStoreHelper.searchPreferenceTags = list
                    selectedSearchTypes.clear()
                    selectedSearchTypes.addAll(list)
                    search(binding?.mainSearch?.query?.toString())
                }
            }

            if (isLayout(PHONE)) {
                binding?.let(::prioritizeTorrentSearchChip)
                if (TvType.Torrent !in validTypes) {
                    binding?.let(::showTorrentSearchSetupChip)
                }
            }
        }
    }

    private fun prioritizeTorrentSearchChip(binding: FragmentSearchBinding) {
        val chips = binding.tvtypesChipsScroll.tvtypesChips
        val group = chips.homeSelectGroup
        val torrentChip = chips.homeSelectTorrents
        val targetIndex = minOf(2, (group.childCount - 1).coerceAtLeast(0))
        if (group.indexOfChild(torrentChip) != targetIndex) {
            val params = torrentChip.layoutParams
            group.removeView(torrentChip)
            group.addView(torrentChip, targetIndex, params)
        }
    }

    private fun showTorrentSearchSetupChip(binding: FragmentSearchBinding) {
        val torrentChip = binding.tvtypesChipsScroll.tvtypesChips.homeSelectTorrents
        torrentChip.isVisible = true
        torrentChip.setOnCheckedChangeListener(null)
        torrentChip.isChecked = false
        torrentChip.setOnCheckedChangeListener { chip, checked ->
            if (checked) {
                chip.isChecked = false
                val context = requireContext()
                val preferredMedia = PreferenceManager.getDefaultSharedPreferences(context)
                    .getStringSet(context.getString(R.string.prefer_media_type_key), null)
                val torrentIsPreferred = preferredMedia.isNullOrEmpty() ||
                    preferredMedia.contains(TvType.Torrent.ordinal.toString())
                val dialog = AlertDialog.Builder(context)
                    .setTitle(
                        if (torrentIsPreferred) R.string.torrent_search_provider_setup_title
                        else R.string.torrent_search_setup_title
                    )
                    .setMessage(
                        if (torrentIsPreferred) R.string.torrent_search_provider_setup_message
                        else R.string.torrent_search_setup_message
                    )

                if (torrentIsPreferred) {
                    dialog.setPositiveButton(R.string.extensions) { _, _ ->
                        findNavController().navigate(
                            R.id.action_navigation_global_to_navigation_settings_extensions
                        )
                    }.setNeutralButton(R.string.provider_lang_settings) { _, _ ->
                        findNavController().navigate(
                            R.id.action_navigation_global_to_navigation_settings_providers
                        )
                    }
                } else {
                    dialog.setPositiveButton(R.string.preferred_media_settings) { _, _ ->
                        findNavController().navigate(
                            R.id.action_navigation_global_to_navigation_settings_providers
                        )
                    }.setNeutralButton(R.string.extensions) { _, _ ->
                        findNavController().navigate(
                            R.id.action_navigation_global_to_navigation_settings_extensions
                        )
                    }
                }

                dialog.setNegativeButton(R.string.cancel, null).show()
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        currentSpan = view.context.getSpanCount()
        binding?.searchAutofitResults?.spanCount = currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onBindingCreated(
        binding: FragmentSearchBinding,
        savedInstanceState: Bundle?
    ) {
        reloadRepos()
        binding.apply {
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    SearchHelper.handleSearchClickCallback(callback)
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            searchAutofitResults.setRecycledViewPool(SearchAdapter.sharedPool)
            searchAutofitResults.adapter = adapter
            searchLoadingBar.alpha = 0f
        }
        if (isLayout(PHONE)) positionSearchSuggestions(binding)

        binding.voiceSearch.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                try {
                    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                        showToast(R.string.speech_recognition_unavailable)
                    } else {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                ctx.getString(R.string.begin_speaking)
                            )
                        }
                        speechRecognizerLauncher.launch(intent)
                    }
                } catch (_: Throwable) {
                    // launch may throw
                    showToast(R.string.speech_recognition_unavailable)
                }
            }
        }

        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        binding.searchFilter.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                val validAPIs = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                var currentValidApis = listOf<MainAPI>()
                val currentSelectedApis = if (selectedApis.isEmpty()) {
                    validAPIs.map { it.name }.toMutableSet()
                } else {
                    selectedApis.toMutableSet()
                }

                val builder =
                    BottomSheetDialog(ctx)

                builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                val selectMainpageBinding: HomeSelectMainpageBinding =
                    HomeSelectMainpageBinding.inflate(
                        builder.layoutInflater,
                        null,
                        false
                    )
                builder.setContentView(selectMainpageBinding.root)
                builder.show()
                builder.let { dialog ->
                    val previousSelectedApis = selectedApis.toSet()
                    val previousSelectedSearchTypes = selectedSearchTypes.toSet()
                    val pendingSelectedSearchTypes = selectedSearchTypes.toMutableList()

                    val isMultiLang = ctx.getApiProviderLangSettings().let { set ->
                        set.size > 1 || set.contains(AllLanguagesName)
                    }

                    val cancelBtt = dialog.findViewById<MaterialButton>(R.id.cancel_btt)
                    val applyBtt = dialog.findViewById<MaterialButton>(R.id.apply_btt)

                    val listView = dialog.findViewById<ListView>(R.id.listview1)
                    val arrayAdapter = ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                    listView?.adapter = arrayAdapter
                    listView?.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

                    listView?.setOnItemClickListener { _, _, i, _ ->
                        if (currentValidApis.isNotEmpty()) {
                            val api = currentValidApis[i].name
                            if (currentSelectedApis.contains(api)) {
                                listView.setItemChecked(i, false)
                                currentSelectedApis -= api
                            } else {
                                listView.setItemChecked(i, true)
                                currentSelectedApis += api
                            }
                        }
                    }

                    fun updateList(types: List<TvType>) {
                        arrayAdapter.clear()
                        currentValidApis = validAPIs.filter { api ->
                            api.supportedTypes.any {
                                types.contains(it)
                            }
                        }.sortedBy { it.name.lowercase() }

                        val names = currentValidApis.map {
                            if (isMultiLang) "${
                                SubtitleHelper.getFlagFromIso(
                                    it.lang
                                )?.plus(" ") ?: ""
                            }${it.name}" else it.name
                        }
                        arrayAdapter.addAll(names)
                        arrayAdapter.notifyDataSetChanged()
                        for ((index, api) in currentValidApis.map { it.name }.withIndex()) {
                            listView?.setItemChecked(index, currentSelectedApis.contains(api))
                        }
                    }

                    bindChips(
                        selectMainpageBinding.tvtypesChipsScroll.tvtypesChips,
                        pendingSelectedSearchTypes,
                        validAPIs.flatMap { api -> api.supportedTypes }.distinct()
                    ) { list ->
                        updateList(list)
                        pendingSelectedSearchTypes.clear()
                        pendingSelectedSearchTypes.addAll(list)
                    }

                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    applyBtt?.setOnClickListener {
                        DataStoreHelper.searchPreferenceTags = pendingSelectedSearchTypes
                        DataStoreHelper.searchPreferenceProviders = currentSelectedApis.toList()
                        selectedSearchTypes.clear()
                        selectedSearchTypes.addAll(pendingSelectedSearchTypes)
                        selectedApis = currentSelectedApis.toMutableSet()
                        updateChips(
                            binding.tvtypesChipsScroll.tvtypesChips,
                            selectedSearchTypes
                        )
                        dialog.dismissSafe()

                        // run search when dialog is close
                        if (previousSelectedApis != selectedApis.toSet() || previousSelectedSearchTypes != selectedSearchTypes.toSet()) {
                            search(binding.mainSearch.query.toString())
                        }
                    }
                    updateList(pendingSelectedSearchTypes.toList())
                }
            }
        }

        val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val isAdvancedSearch = settingsManager?.getBoolean("advanced_search", true) ?: true
        val isSearchSuggestionsEnabled = settingsManager?.getBoolean("search_suggestions_enabled", true) ?: true

        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()

        if (!isLayout(PHONE)) {
            binding.searchFilter.isFocusable = true
            binding.searchFilter.isFocusableInTouchMode = true
        }

        // Hide suggestions when search view loses focus (phone only)
        if (isLayout(PHONE)) {
            binding.mainSearch.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchViewModel.clearSuggestions()
                }
            }
        }


        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                searchViewModel.clearSuggestions()

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                //searchViewModel.quickSearch(newText)
                val showHistory = newText.isBlank()
                if (showHistory) {
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                } else {
                    // Fetch suggestions when user is typing (if enabled)
                    if (isSearchSuggestionsEnabled) {
                        searchViewModel.fetchSuggestions(newText)
                    }
                }
                binding.apply {
                    searchHistoryRecycler.isVisible = showHistory
                    searchMasterRecycler.isVisible = !showHistory && isAdvancedSearch
                    searchAutofitResults.isVisible = !showHistory && !isAdvancedSearch
                    // Hide suggestions when showing history or showing search results
                    searchSuggestionsRecycler.isVisible = !showHistory && isSearchSuggestionsEnabled
                }

                return true
            }
        })

        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    it.value.let { data ->
                        val list = data.list
                        if (list.isNotEmpty()) {
                            (binding.searchAutofitResults.adapter as? SearchAdapter)?.submitList(
                                list
                            )
                        }
                    }
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        val listLock = ReentrantLock()
        observe(searchViewModel.currentSearch) { list ->
            try {
                // https://stackoverflow.com/questions/6866238/concurrent-modification-exception-adding-to-an-arraylist
                listLock.lock()

                val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()

                val sortedList = list.toList().sortedWith(compareBy { (providerName, _) ->
                    val index = pinnedOrder.indexOf(providerName)
                    if (index == -1) Int.MAX_VALUE else index
                })

                (binding.searchMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                    val newItems = sortedList.map { (providerName, providerData) ->
                        val dataList = providerData.list
                        val dataListFiltered =
                            context?.filterSearchResultByFilmQuality(dataList) ?: dataList

                        val homePageList = HomePageList(
                            providerName,
                            dataListFiltered
                        )

                        HomeViewModel.ExpandableHomepageList(
                            homePageList,
                            providerData.currentPage,
                            providerData.hasNext
                        )
                    }

                    submitList(newItems)
                    //notifyDataSetChanged()
                }
            } catch (e: Exception) {
                logError(e)
            } finally {
                listLock.unlock()
            }
        }


        /*main_search.setOnQueryTextFocusChangeListener { _, b ->
            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                showInputMethod(view.findFocus())
            }
        }*/
        //main_search.onActionViewExpanded()*/

        val masterAdapter =
            ParentItemAdapter(id = "masterAdapter".hashCode(), { callback ->
                SearchHelper.handleSearchClickCallback(callback)
            }, { item ->
                bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                    bottomSheetDialog = null
                }, expandCallback = { name -> searchViewModel.expandAndReturn(name) })
            }, expandCallback = { name ->
                ioSafe {
                    searchViewModel.expandAndReturn(name)
                }
            })

        val historyAdapter = SearchHistoryAdaptor { click ->
            val searchItem = click.item
            when (click.clickAction) {
                SEARCH_HISTORY_OPEN -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    searchViewModel.clearSearch()
                    if (searchItem.type.isNotEmpty())
                        updateChips(
                            binding.tvtypesChipsScroll.tvtypesChips,
                            searchItem.type.toMutableList()
                        )
                    binding.mainSearch.setQuery(searchItem.searchText, true)
                }

                SEARCH_HISTORY_REMOVE -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    removeKey("$currentAccount/$SEARCH_HISTORY_KEY", searchItem.key)
                    searchViewModel.updateHistory()
                }

                SEARCH_HISTORY_CLEAR -> {
                    // Show confirmation dialog (from footer button)
                    activity?.let { ctx ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
                                        searchViewModel.updateHistory()
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {
                                    }
                                }
                            }

                        try {
                            builder.setTitle(R.string.clear_history).setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getString(R.string.history)
                                )
                            )
                                .setPositiveButton(R.string.sort_clear, dialogClickListener)
                                .setNegativeButton(R.string.cancel, dialogClickListener)
                                .show().setDefaultFocus()
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }

                else -> {
                    // wth are you doing???
                }
            }
        }

        val suggestionAdapter = SearchSuggestionAdapter { callback ->
            when (callback.clickAction) {
                SEARCH_SUGGESTION_CLICK -> {
                    // Search directly
                    binding.mainSearch.setQuery(callback.suggestion, true)
                    searchViewModel.clearSuggestions()
                }
                SEARCH_SUGGESTION_FILL -> {
                    // Fill the search box without searching
                    binding.mainSearch.setQuery(callback.suggestion, false)
                }
                SEARCH_SUGGESTION_CLEAR -> {
                    // Clear suggestions (from footer button)
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.apply {
            searchHistoryRecycler.adapter = historyAdapter
            searchHistoryRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)
            //searchHistoryRecycler.layoutManager = GridLayoutManager(context, 1)

            // Setup suggestions RecyclerView
            searchSuggestionsRecycler.adapter = suggestionAdapter
            searchSuggestionsRecycler.layoutManager = LinearLayoutManager(context)

            searchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            searchMasterRecycler.adapter = masterAdapter
            //searchMasterRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)

            searchMasterRecycler.layoutManager = GridLayoutManager(context, 1)

            // Automatically search the specified query, this allows the app search to launch from intent
            var sq =
                arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let

                // Queries are dropped if you are submitted before layout finishes
                mainSearch.doOnLayout {
                    mainSearch.setQuery(query, true)
                }
                // Clear the query as to not make it request the same query every time the page is opened
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        observe(searchViewModel.currentHistory) { list ->
            (binding.searchHistoryRecycler.adapter as? SearchHistoryAdaptor?)?.submitList(list)
             // Scroll to top to show newest items (list is sorted by newest first)
            if (list.isNotEmpty()) {
                binding.searchHistoryRecycler.scrollToPosition(0)
            }
        }

        // Observe search suggestions
        observe(searchViewModel.searchSuggestions) { suggestions ->
            val hasSuggestions = suggestions.isNotEmpty()
            binding.searchSuggestionsRecycler.isVisible = hasSuggestions
            (binding.searchSuggestionsRecycler.adapter as? SearchSuggestionAdapter?)?.submitList(suggestions)

            // On non-phone layouts, redirect focus and handle back button
            if (!isLayout(PHONE)) {
                if (hasSuggestions) {
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_suggestions_recycler
                    // Attach back button callback to clear suggestions
                    activity?.attachBackPressedCallback("SearchFragment") {
                        searchViewModel.clearSuggestions()
                    }
                } else {
                    // Reset to default focus target (history)
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_history_recycler
                    // Detach back button callback when no suggestions
                    activity?.detachBackPressedCallback("SearchFragment")
                }
            }
        }

        searchViewModel.updateHistory()
    }

    private fun positionSearchSuggestions(binding: FragmentSearchBinding) {
        val root = binding.searchRoot
        val chips = binding.tvtypesChipsScroll.root
        val suggestions = binding.searchSuggestionsRecycler

        val updatePosition = Runnable {
            val rootLocation = IntArray(2)
            val chipsLocation = IntArray(2)
            root.getLocationInWindow(rootLocation)
            chips.getLocationInWindow(chipsLocation)

            val params = suggestions.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            val spacing = (8f * root.resources.displayMetrics.density).toInt()
            val topMargin = (chipsLocation[1] - rootLocation[1] + chips.height + spacing)
                .coerceAtLeast(0)
            if (params.topMargin != topMargin) {
                params.topMargin = topMargin
                suggestions.layoutParams = params
            }
        }

        chips.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            root.post(updatePosition)
        }
        root.doOnLayout { updatePosition.run() }
    }
}
