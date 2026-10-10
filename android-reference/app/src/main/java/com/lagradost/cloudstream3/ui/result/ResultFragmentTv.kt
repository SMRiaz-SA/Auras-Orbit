package com.lagradost.cloudstream3.ui.result

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.FragmentResultTvBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup
import com.lagradost.cloudstream3.ui.player.ExtractorLinkGenerator
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.ui.result.ResultFragment.updateUIEvent
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.isRtl
import com.lagradost.cloudstream3.utils.AppContextUtils.loadCache
import com.lagradost.cloudstream3.utils.AppContextUtils.updateHasTrailers
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogInstant
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.populateChips
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.setNavigationBarColorCompat
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.setTextHtml
import com.lagradost.cloudstream3.utils.txt

class ResultFragmentTv : BaseFragment<FragmentResultTvBinding>(
    BindingCreator.Inflate(FragmentResultTvBinding::inflate)
) {

    private lateinit var viewModel: ResultViewModel2
    private var inlineSourceEpisode: ResultEpisode? = null
    private var inlineSourceEpisodeIsResume = false
    private var inlineSourceResults: Pair<ResultEpisode, LinkLoadingResult>? = null
    private var inlineSourceShowAll = false

    override fun onDestroyView() {
        viewModel.cancelInlineSourceSearch()
        inlineSourceEpisode = null
        inlineSourceEpisodeIsResume = false
        inlineSourceResults = null
        inlineSourceShowAll = false
        updateUIEvent -= ::updateUI
        activity?.detachBackPressedCallback(this@ResultFragmentTv.toString())
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        viewModel =
            ViewModelProvider(this)[ResultViewModel2::class.java]
        viewModel.EPISODE_RANGE_SIZE = 50
        updateUIEvent += ::updateUI

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    private fun updateUI(id: Int?) {
        viewModel.reloadEpisodes()
    }

    private var currentRecommendations: List<SearchResponse> = emptyList()

    private fun handleSelection(data: Any) {
        when (data) {
            is EpisodeRange -> {
                viewModel.changeRange(data)
            }

            is Int -> {
                viewModel.changeSeason(data)
            }

            is DubStatus -> {
                viewModel.changeDubStatus(data)
            }

            is String -> {
                setRecommendations(currentRecommendations, data)
            }
        }
    }

    private fun RecyclerView?.select(index: Int) {
        (this?.adapter as? SelectAdaptor?)?.select(index, this)
    }

    private fun RecyclerView?.update(data: List<SelectData>) {
        (this?.adapter as? SelectAdaptor?)?.submitList(data)
        this?.isVisible = data.size > 1
    }

    private fun RecyclerView?.setAdapter() {
        this?.adapter = SelectAdaptor { data ->
            handleSelection(data)
        }
    }

//    private fun hasNoFocus(): Boolean {
//        val focus = activity?.currentFocus
//        if (focus == null || !focus.isVisible) return true
//        return focus == binding?.resultRoot
//    }

    /**
     * Force focus any play button.
     * Note that this will steal any focus if the episode loading is too slow (unlikely).
     */
    private fun focusPlayButton() {
        binding?.resultPlayMovieButton?.requestFocus()
        binding?.resultPlaySeriesButton?.requestFocus()
        binding?.resultResumeSeriesButton?.requestFocus()
    }

    private fun setRecommendations(rec: List<SearchResponse>?, validApiName: String?) {
        currentRecommendations = rec ?: emptyList()
        val isInvalid = rec.isNullOrEmpty()
        binding?.apply {
            resultRecommendationsList.isGone = isInvalid
            resultRecommendationsHolder.isGone = isInvalid
            val matchAgainst = validApiName ?: rec?.firstOrNull()?.apiName
            (resultRecommendationsList.adapter as? SearchAdapter)?.submitList(rec?.filter { it.apiName == matchAgainst }
                ?: emptyList())

            rec?.map { it.apiName }?.distinct()?.let { apiNames ->
                // very dirty selection
                resultRecommendationsFilterSelection.isVisible = apiNames.size > 1
                resultRecommendationsFilterSelection.update(apiNames.map {
                    txt(
                        it
                    ) to it
                })
                resultRecommendationsFilterSelection.select(apiNames.indexOf(matchAgainst))
            } ?: run {
                resultRecommendationsFilterSelection.isVisible = false
            }
        }
    }

    var loadingDialog: Dialog? = null
    var popupDialog: Dialog? = null

    private fun reloadViewModel(forceReload: Boolean) {
        if (!viewModel.hasLoaded() || forceReload) {
            val storedData = getStoredData() ?: return
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        }
    }

    override fun onResume() {
        activity?.setNavigationBarColorCompat(R.attr.primaryBlackBackground)
        afterPluginsLoadedEvent += ::reloadViewModel
        super.onResume()
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::reloadViewModel
        super.onStop()
    }

    private fun View.fade(turnVisible: Boolean) {
        if (turnVisible) {
            isVisible = true
        }

        this.animate().alpha(if (turnVisible) 0.97f else 0.0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    this@fade.isVisible = turnVisible
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }
        this.animate().translationX(if (turnVisible) 0f else if (isRtl()) -100.0f else 100f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }
    }

    private fun toggleEpisodes(show: Boolean) {
        binding?.apply {
            if (show) {
                activity?.attachBackPressedCallback(this@ResultFragmentTv.toString()) {
                    toggleEpisodes(false)
                }
            } else {
                activity?.detachBackPressedCallback(this@ResultFragmentTv.toString())
            }
            episodesShadow.fade(show)
            episodeHolderTv.fade(show)
            if (episodesShadow.isRtl()) {
                episodesShadowBackground.scaleX = -1f
            } else {
                episodesShadowBackground.scaleX = 1f
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view, padTop = false)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindingCreated(binding: FragmentResultTvBinding) {
        // ===== setup =====
        val storedData = getStoredData() ?: return
        activity?.window?.decorView?.clearFocus()
        activity?.loadCache()
        hideKeyboard()
        if (storedData.restart || !viewModel.hasLoaded())
            viewModel.load(
                activity,
                storedData.url,
                storedData.apiName,
                storedData.showFillers,
                storedData.dubStatus,
                storedData.start
            )
        // ===== ===== =====
        var comingSoon = false

        binding.apply {
            //episodesShadow.rotationX = 180.0f//if(episodesShadow.isRtl()) 180.0f else 0.0f

            // parallax on background
            resultFinishLoading.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { view, _, scrollY, _, oldScrollY ->
                backgroundPosterHolder.translationY = -scrollY.toFloat() * 0.8f
            })
            resultRetrySources.setOnClickListener {
                findInlineSources(forceReload = true)
            }

            redirectToPlay.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                toggleEpisodes(false)

                binding.apply {
                    val views = listOf(
                        resultPlayMovieButton,
                        resultPlaySeriesButton,
                        resultResumeSeriesButton,
                        resultPlayTrailerButton,
                        resultBookmarkButton,
                        resultFavoriteButton,
                        resultSubscribeButton,
                        resultSearchButton
                    )
                    for (requestView in views) {
                        if (!requestView.isVisible) continue
                        if (requestView.requestFocus()) break
                    }
                }
            }

            redirectToEpisodes.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) return@setOnFocusChangeListener
                toggleEpisodes(true)
                binding.apply {
                    val views = listOf(
                        resultDubSelection,
                        resultSeasonSelection,
                        resultRangeSelection,
                        resultEpisodes,
                        resultPlayTrailerButton,
                    )
                    for (requestView in views) {
                        if (!requestView.isShown) continue
                        if (requestView.requestFocus()) break // View.FOCUS_RIGHT
                    }
                }
            }

            mapOf(
                resultPlayMovieButton to resultPlayMovieText,
                resultPlaySeriesButton to resultPlaySeriesText,
                resultResumeSeriesButton to resultResumeSeriesText,
                resultPlayTrailerButton to resultPlayTrailerText,
                resultBookmarkButton to resultBookmarkText,
                resultFavoriteButton to resultFavoriteText,
                resultSubscribeButton to resultSubscribeText,
                resultSearchButton to resultSearchText,
                resultEpisodesShowButton to resultEpisodesShowText
            ).forEach { (button, text) ->

                button.setOnFocusChangeListener { view, hasFocus ->
                    if (!hasFocus) {
                        text.isSelected = false
                        if (view.id == R.id.result_episodes_show_button) toggleEpisodes(false)
                        return@setOnFocusChangeListener
                    }

                    text.isSelected = true
                    if (button.tag == context?.getString(R.string.tv_no_focus_tag)) {
                        resultFinishLoading.scrollTo(0, 0)
                    }
                    when (button.id) {
                        R.id.result_episodes_show_button -> {
                            toggleEpisodes(true)
                        }

                        else -> {
                            toggleEpisodes(false)
                        }
                    }
                }
            }

            resultEpisodesShowButton.setOnClickListener {
                // toggle, to make it more touch accessible just in case someone thinks that a
                // tv layout is better but is using a touch device
                toggleEpisodes(!episodeHolderTv.isVisible)
            }

            resultEpisodes.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            resultDubSelection.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
            )
            resultRangeSelection.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
            )
            resultSeasonSelection.setLinearListLayout(
                isHorizontal = false,
                nextUp = FOCUS_SELF,
                nextDown = FOCUS_SELF,
            )

            /*.layoutManager =
                LinearListLayout(resultEpisodes.context, resultEpisodes.isRtl()).apply {
                    setVertical()
                }*/

            resultReloadConnectionerror.setOnClickListener {
                viewModel.load(
                    activity,
                    storedData.url,
                    storedData.apiName,
                    storedData.showFillers,
                    storedData.dubStatus,
                    storedData.start
                )

            }

            resultMetaSite.isFocusable = false

            resultSeasonSelection.setAdapter()
            resultRangeSelection.setAdapter()
            resultDubSelection.setAdapter()
            resultRecommendationsFilterSelection.setAdapter()

            resultCastItems.setOnFocusChangeListener { _, hasFocus ->
                // Always escape focus
                if (hasFocus) binding.resultBookmarkButton.requestFocus()
            }
            //resultBack.setOnClickListener {
            //    activity?.popCurrentPage()
            //}

            resultRecommendationsList.spanCount = 8
            resultRecommendationsList.setRecycledViewPool(SearchAdapter.sharedPool)
            resultRecommendationsList.adapter =
                SearchAdapter(
                    resultRecommendationsList,
                ) { callback ->
                    if (callback.action == SEARCH_ACTION_FOCUSED) {
                        toggleEpisodes(false)
                    } else SearchHelper.handleSearchClickCallback(callback)
                }

            resultEpisodes.setRecycledViewPool(EpisodeAdapter.sharedPool)
            resultEpisodes.adapter =
                EpisodeAdapter(
                    false,
                    { episodeClick ->
                        viewModel.handleAction(episodeClick)
                    },
                    { downloadClickEvent ->
                        DownloadButtonSetup.handleDownloadClick(downloadClickEvent)
                    }
                )

            resultCastItems.layoutManager = object : LinearListLayout(root.context) {
                override fun onRequestChildFocus(
                    parent: RecyclerView,
                    state: RecyclerView.State,
                    child: View,
                    focused: View?
                ): Boolean {
                    // Make the cast always focus the first visible item when focused
                    // from somewhere else. Otherwise it jumps to the last item.
                    return if (parent.focusedChild == null) {
                        scrollToPosition(this.findFirstCompletelyVisibleItemPosition())
                        true
                    } else {
                        super.onRequestChildFocus(parent, state, child, focused)
                    }
                }
            }.apply { setHorizontal() }

            val aboveCast = listOf(
                binding.resultEpisodesShow,
                binding.resultBookmark,
                binding.resultFavorite,
                binding.resultSubscribe,
            ).firstOrNull { it.isVisible }

            resultCastItems.setRecycledViewPool(ActorAdaptor.sharedPool)
            resultCastItems.adapter = ActorAdaptor(
                nextFocusUpId = aboveCast?.id,
                focusCallback = { toggleEpisodes(false) },
                onPersonSelected = ::openPersonFilmography,
            )

            if (isLayout(EMULATOR)) {
                episodesShadow.setOnClickListener {
                    toggleEpisodes(false)
                }
            }
        }

        observeNullable(viewModel.resumeWatching) { resume ->
            if (resume != null && !resume.isMovie && !comingSoon) {
                setInlineSourcesEpisode(resume.result, fromResume = true)
            } else if (resume == null) {
                inlineSourceEpisodeIsResume = false
            }
            binding.apply {
                if (resume == null) {
                    return@observeNullable
                }

                resultResumeSeries.isVisible = true
                resultPlayMovie.isVisible = false
                resultPlaySeries.isVisible = false

                // show progress no matter if series or movie
                resume.progress?.let { progress ->
                    resultResumeSeriesTitle.apply {
                        isVisible = !resume.isMovie
                        text =
                            if (resume.isMovie) null else context?.getNameFull(
                                resume.result.name,
                                resume.result.episode,
                                resume.result.season
                            )
                    }
                    resultResumeSeriesProgressText.setText(progress.progressLeft)
                    resultResumeSeriesProgress.apply {
                        isVisible = true
                        this.max = progress.maxProgress
                        this.progress = progress.progress
                    }
                    resultResumeProgressHolder.isVisible = true
                } ?: run {
                    resultResumeProgressHolder.isVisible = false
                }

                focusPlayButton()
                // Stops last button right focus if it is a movie
                if (resume.isMovie)
                    resultSearchButton.nextFocusRightId = R.id.result_search_Button

                resultResumeSeriesText.text =
                    when {
                        resume.isMovie -> context?.getString(R.string.resume)
                        resume.result.season != null ->
                            "${getString(R.string.season_short)}${resume.result.season}:${
                                getString(
                                    R.string.episode_short
                                )
                            }${resume.result.episode}"

                        else -> "${getString(R.string.episode)} ${resume.result.episode}"
                    }

                resultResumeSeriesButton.setOnClickListener {
                    viewModel.handleAction(
                        EpisodeClickEvent(
                            storedData.playerAction, //?: ACTION_PLAY_EPISODE_IN_PLAYER,
                            resume.result
                        )
                    )
                }

                resultResumeSeriesButton.setOnLongClickListener {
                    viewModel.handleAction(
                        EpisodeClickEvent(ACTION_SHOW_OPTIONS, resume.result)
                    )
                    return@setOnLongClickListener true
                }

            }
        }

        observe(viewModel.trailers) { trailersLinks ->
            context?.updateHasTrailers()
            if (!LoadResponse.isTrailersEnabled) return@observe
            val extractedTrailerLinks = trailersLinks.flatMap { it.mirros }
                .map { (extractedTrailerLink, _) -> extractedTrailerLink }
            binding.apply {
                resultPlayTrailer.isGone = extractedTrailerLinks.isEmpty()
                resultPlayTrailerButton.setOnClickListener {
                    if (extractedTrailerLinks.isEmpty()) return@setOnClickListener
                    activity.navigate(
                        R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                            ExtractorLinkGenerator(
                                extractedTrailerLinks,
                                emptyList()
                            ), 0
                        )
                    )
                }
            }
        }

        observe(viewModel.watchStatus) { watchType ->
            binding.apply {
                resultBookmarkText.setText(watchType.stringRes)

                resultBookmarkButton.apply {
                    val drawable = if (watchType.stringRes == R.string.type_none) {
                        R.drawable.outline_bookmark_add_24
                    } else R.drawable.ic_baseline_bookmark_24
                    setIconResource(drawable)

                    setOnClickListener { view ->
                        activity?.showBottomDialog(
                            WatchType.entries.map { view.context.getString(it.stringRes) }.toList(),
                            watchType.ordinal,
                            view.context.getString(R.string.action_add_to_bookmarks),
                            showApply = false,
                            {}) {
                            viewModel.updateWatchStatus(WatchType.entries[it], context)
                        }
                    }
                }
            }
        }

        observeNullable(viewModel.favoriteStatus) { isFavorite ->
            binding.resultFavorite.isVisible = isFavorite != null
            binding.resultFavoriteButton.apply {
                if (isFavorite == null) return@observeNullable

                val drawable = if (isFavorite) {
                    R.drawable.ic_baseline_favorite_24
                } else R.drawable.ic_baseline_favorite_border_24
                setIconResource(drawable)

                setOnClickListener {
                    viewModel.toggleFavoriteStatus(context) { newStatus: Boolean? ->
                        if (newStatus == null) return@toggleFavoriteStatus

                        val message = if (newStatus) {
                            R.string.favorite_added
                        } else R.string.favorite_removed

                        val name = (viewModel.page.value as? Resource.Success)?.value?.title
                            ?: txt(R.string.no_data)
                                .asStringNull(context) ?: ""
                        CommonActivity.showToast(
                            txt(
                                message,
                                name
                            ), Toast.LENGTH_SHORT
                        )
                    }
                }
            }

            binding.resultFavoriteText.apply {
                val text = if (isFavorite == true) {
                    R.string.unfavorite
                } else R.string.favorite
                setText(text)
            }
        }

        observeNullable(viewModel.subscribeStatus) { isSubscribed ->
            binding.resultSubscribe.isVisible = isSubscribed != null && isLayout(EMULATOR)
            binding.resultSubscribeButton.apply {
                if (isSubscribed == null) return@observeNullable

                val drawable = if (isSubscribed) {
                    R.drawable.ic_baseline_notifications_active_24
                } else R.drawable.baseline_notifications_none_24
                setIconResource(drawable)

                setOnClickListener {
                    viewModel.toggleSubscriptionStatus(context) { newStatus: Boolean? ->
                        if (newStatus == null) return@toggleSubscriptionStatus

                        val message = if (newStatus) {
                            // Kinda icky to have this here, but it works.
                            SubscriptionWorkManager.enqueuePeriodicWork(context)
                            R.string.subscription_new
                        } else R.string.subscription_deleted

                        val name = (viewModel.page.value as? Resource.Success)?.value?.title
                            ?: txt(R.string.no_data)
                                .asStringNull(context) ?: ""
                        CommonActivity.showToast(
                            txt(
                                message,
                                name
                            ), Toast.LENGTH_SHORT
                        )
                    }
                }

                binding.resultSubscribeText.apply {
                    val text = if (isSubscribed) {
                        R.string.action_unsubscribe
                    } else R.string.action_subscribe
                    setText(text)
                }
            }
        }

        observeNullable(viewModel.movie) { data ->
            if (data == null) {
                return@observeNullable
            }

            binding.apply {
                (data as? Resource.Success)?.value?.let { (text, ep) ->
                    resultPlayMovieText.text = text.asString(resultPlayMovieText.context)
                    if (!comingSoon) setInlineSourcesEpisode(ep)
                    resultPlayMovieButton.setOnClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_CLICK_DEFAULT, ep)
                        )
                    }
                    resultPlayMovieButton.setOnLongClickListener {
                        viewModel.handleAction(
                            EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)
                        )
                        return@setOnLongClickListener true
                    }

                    resultPlayMovie.isVisible = !comingSoon && resultResumeSeries.isGone
                    if (comingSoon) {
                        resultBookmarkButton.requestFocus()
                    } else resultPlayMovieButton.requestFocus()

                    // Stops last button right focus
                    resultSearchButton.nextFocusRightId = R.id.result_search_Button
                }
            }
        }

        observeNullable(viewModel.selectPopup) { popup ->
            if (popup == null) {
                popupDialog?.dismissSafe(activity)
                popupDialog = null
                return@observeNullable
            }

            popupDialog?.dismissSafe(activity)

            popupDialog = activity?.let { act ->
                val options = popup.getOptions(act)
                val title = popup.getTitle(act)

                act.showBottomDialogInstant(
                    options, title, {
                        popupDialog = null
                        popup.callback(null)
                    }, {
                        popupDialog = null
                        popup.callback(it)
                    }
                )
            }
        }

        observeNullable(viewModel.loadedLinks) { load ->
            if (load == null) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
                return@observeNullable
            }
            if (loadingDialog?.isShowing != true) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
            }
            loadingDialog = loadingDialog ?: context?.let { ctx ->
                val builder = BottomSheetDialog(ctx)
                builder.setContentView(R.layout.bottom_loading)
                builder.setOnDismissListener {
                    loadingDialog = null
                    viewModel.cancelLinks()
                }
                builder.setCanceledOnTouchOutside(true)
                builder.show()
                builder
            }
            loadingDialog?.findViewById<MaterialButton>(R.id.overlay_loading_skip_button)?.apply {
                if (load.linksLoaded <= 0) {
                    isInvisible = true
                } else {
                    setOnClickListener {
                        viewModel.skipLoading()
                    }
                    isVisible = true
                    text = "${context.getString(R.string.skip_loading)} (${load.linksLoaded})"
                }
            }
        }


        observeNullable(viewModel.episodesCountText) { count ->
            binding.resultEpisodesText.setText(count)
        }

        observe(viewModel.selectedRangeIndex) { selected ->
            binding.resultRangeSelection.select(selected)
        }
        observe(viewModel.selectedSeasonIndex) { selected ->
            binding.resultSeasonSelection.select(selected)
        }
        observe(viewModel.selectedDubStatusIndex) { selected ->
            binding.resultDubSelection.select(selected)
        }
        observe(viewModel.rangeSelections) {
            binding.resultRangeSelection.update(it)
        }
        observe(viewModel.dubSubSelections) {
            binding.resultDubSelection.update(it)
        }
        observe(viewModel.seasonSelections) {
            binding.resultSeasonSelection.update(it)
        }
        observe(viewModel.recommendations) { recommendations ->
            setRecommendations(recommendations, null)
        }

        if (isLayout(TV)) {
            observe(viewModel.episodeSynopsis) { description ->
                context?.let { ctx ->
                    val builder: AlertDialog.Builder =
                        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                    builder.setMessage(description.html())
                        .setTitle(R.string.synopsis)
                        .setOnDismissListener {
                            viewModel.releaseEpisodeSynopsis()
                        }
                        .show()
                }
            }
        }

        // Used to request focus the first time the episodes are loaded.
        var hasLoadedEpisodesOnce = false
        observeNullable(viewModel.episodes) { episodes ->
            if (episodes == null) return@observeNullable
            binding.apply {
                if (comingSoon) resultBookmarkButton.requestFocus()

                //    resultEpisodeLoading.isVisible = episodes is Resource.Loading
                if (episodes is Resource.Success) {
                    val lastWatchedIndex = episodes.value.indexOfLast { ep ->
                        ep.getWatchProgress() >= NEXT_WATCH_EPISODE_PERCENTAGE.toFloat() / 100.0f || ep.videoWatchState == VideoWatchState.Watched
                    }

                    val firstUnwatched =
                        episodes.value.getOrElse(lastWatchedIndex + 1) { episodes.value.firstOrNull() }

                    if (firstUnwatched != null) {
                        if (!inlineSourceEpisodeIsResume && !comingSoon) {
                            setInlineSourcesEpisode(firstUnwatched)
                        }
                        resultPlaySeriesText.text =
                            when {
                                firstUnwatched.season != null ->
                                    "${getString(R.string.season_short)}${firstUnwatched.season}:${
                                        getString(
                                            R.string.episode_short
                                        )
                                    }${firstUnwatched.episode}"

                                else -> "${getString(R.string.episode)} ${firstUnwatched.episode}"
                            }
                        resultPlaySeriesButton.setOnClickListener {
                            viewModel.handleAction(
                                EpisodeClickEvent(
                                    ACTION_CLICK_DEFAULT,
                                    firstUnwatched
                                )
                            )
                        }
                        resultPlaySeriesButton.setOnLongClickListener {
                            viewModel.handleAction(
                                EpisodeClickEvent(ACTION_SHOW_OPTIONS, firstUnwatched)
                            )
                            return@setOnLongClickListener true
                        }
                        if (!hasLoadedEpisodesOnce) {
                            hasLoadedEpisodesOnce = true
                            resultPlaySeries.isVisible = resultResumeSeries.isGone && !comingSoon
                            resultEpisodesShow.isVisible = true && !comingSoon
                            resultPlaySeriesButton.requestFocus()
                        }
                    }


                    (resultEpisodes.adapter as? EpisodeAdapter)?.submitList(episodes.value)
                }
            }
        }

        observeNullable(viewModel.page) { data ->
            if (data == null) return@observeNullable
            binding.apply {
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        resultVpn.setText(d.vpnText)
                        resultInfo.setText(d.metaText)
                        resultNoEpisodes.setText(d.noEpisodesFoundText)
                        resultTitle.setText(d.titleText)
                        resultMetaSite.setText(d.apiName)
                        resultMetaType.setText(d.typeText)
                        resultMetaYear.setText(d.yearText)
                        resultMetaDuration.setText(d.durationText)
                        resultMetaRating.setText(d.ratingText)
                        resultMetaStatus.setText(d.onGoingText)
                        resultMetaContentRating.setText(d.contentRatingText)
                        resultNextAiring.setText(d.nextAiringEpisode)
                        resultNextAiringTime.setText(d.nextAiringDate)
                        resultPoster.loadImage(d.posterImage, headers = d.posterHeaders)

                        var isExpanded = false
                        resultDescription.apply {
                            setTextHtml(d.plotText)
                            setOnClickListener {
                                if (isLayout(EMULATOR)) {
                                    isExpanded = !isExpanded
                                    maxLines = if (isExpanded) {
                                        Integer.MAX_VALUE
                                    } else 10
                                } else {
                                    context?.let { ctx ->
                                        val builder: AlertDialog.Builder =
                                            AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                                        builder.setMessage(d.plotText.asString(ctx).html())
                                            .setTitle(d.plotHeaderText.asString(ctx))
                                            .show()
                                    }
                                }
                            }
                        }

                        val error = listOf(
                            R.drawable.profile_bg_dark_blue,
                            R.drawable.profile_bg_blue,
                            R.drawable.profile_bg_orange,
                            R.drawable.profile_bg_pink,
                            R.drawable.profile_bg_purple,
                            R.drawable.profile_bg_red,
                            R.drawable.profile_bg_teal
                        ).random()

                        backgroundPoster.loadImage(d.posterBackgroundImage, headers = d.posterHeaders) {
                            error { getImageFromDrawable(context ?: return@error null, error) }
                        }

                        bindLogo(
                            url = d.logoUrl,
                            headers = d.posterHeaders,
                            titleView = resultTitle,
                            logoView = backgroundPosterWatermarkBadgeHolder
                        )

                        comingSoon = d.comingSoon
                        resultTvComingSoon.isVisible = d.comingSoon
                        if (d.comingSoon) setInlineSourcesEpisode(null)

                        populateChips(resultTag, d.tags)
                        val prefs =
                            androidx.preference.PreferenceManager.getDefaultSharedPreferences(root.context)
                        val showCast = prefs.getBoolean(
                            root.context.getString(R.string.show_cast_in_details_key),
                            true
                        )

                        resultCastText.setText(if (showCast) d.actorsText else null)
                        resultCastItems.isGone = !showCast || d.actors.isNullOrEmpty()
                        (resultCastItems.adapter as? ActorAdaptor)?.submitList(if (showCast) d.actors else emptyList())

                        if (d.contentRatingText == null) {
                            // If there is no rating to display, we don't want an empty gap
                            resultMetaContentRating.width = 0
                        }

                        resultSearchButton.setOnClickListener {
                            QuickSearchFragment.pushSearch(activity, d.title)
                        }
                    }

                    is Resource.Loading -> {}

                    is Resource.Failure -> {
                        resultErrorText.text =
                            storedData.url.plus("\n") + data.errorString
                    }
                }

                resultFinishLoading.isVisible = data is Resource.Success

                resultLoading.isVisible = data is Resource.Loading

                resultLoadingError.isVisible = data is Resource.Failure
                //resultReloadConnectionOpenInBrowser.isVisible = data is Resource.Failure
            }
        }
    }

    private fun setInlineSourcesEpisode(
        episode: ResultEpisode?,
        fromResume: Boolean = false
    ) {
        if (isSameInlineSourceEpisode(inlineSourceEpisode, episode)) {
            if (fromResume && episode != null) inlineSourceEpisodeIsResume = true
            return
        }

        viewModel.cancelInlineSourceSearch()
        inlineSourceEpisode = episode
        inlineSourceEpisodeIsResume = fromResume && episode != null
        inlineSourceResults = null
        inlineSourceShowAll = false

        this.binding?.apply {
            resultInlineSources.isVisible = episode != null
            resultInlineSources.setOnClickListener {
                if (inlineSourceResults == null || inlineSourceResults?.second?.links.isNullOrEmpty()) {
                    findInlineSources(forceReload = true)
                }
            }
            resultSourceOptions.removeAllViews()
            resultSourceOptions.isGone = true
            resultSourceCount.isGone = true
            resultSourceShowAll.isGone = true
            resultRetrySources.isGone = true
            resultSourcesStatusPanel.isVisible = episode != null
            resultSourcesProgress.isVisible = episode != null
            resultSourcesStatusPanel.isFocusable = episode != null
            resultSourcesStatusPanel.isClickable = episode != null
            resultSourcesStatusPanel.nextFocusUpId = resultDescription.id
            resultSourcesStatusPanel.nextFocusDownId = resultRetrySources.id
            resultRetrySources.nextFocusUpId = resultSourcesStatusPanel.id
            resultSourcesEpisode.isVisible = episode?.let { it.season != null || it.episode > 0 } == true
            resultSourcesEpisode.text = episode
                ?.takeIf { it.season != null || it.episode > 0 }
                ?.let { value ->
                    val label = context?.getNameFull(
                        value.name ?: value.headerName,
                        value.episode,
                        value.season
                    ) ?: value.headerName
                    getString(R.string.sources_for_episode, label)
                }
            resultSourcesStatus.apply {
                text = if (episode == null) null else context.getString(R.string.searching_sources)
                isVisible = episode != null
            }
        }

        if (episode != null) findInlineSources()
    }

    private fun findInlineSources(forceReload: Boolean = false) {
        val episode = inlineSourceEpisode ?: return
        val binding = this.binding ?: return

        inlineSourceResults?.takeIf {
            isSameInlineSourceEpisode(it.first, episode) && !forceReload
        }?.let { (_, result) ->
            showInlineSourceOptions(episode, result)
            return
        }

        inlineSourceResults = null
        binding.resultRetrySources.isGone = true
        binding.resultSourceCount.isGone = true
        binding.resultSourceShowAll.isGone = true
        binding.resultSourcesStatus.apply {
            text = context.getString(R.string.searching_sources)
            isVisible = true
        }
        binding.resultSourcesStatusPanel.isVisible = true
        binding.resultSourcesStatusPanel.isFocusable = true
        binding.resultSourcesStatusPanel.isClickable = true
        binding.resultInlineSources.nextFocusDownId = binding.resultSourcesStatusPanel.id
        binding.resultSourcesStatusPanel.setOnClickListener {
            findInlineSources(forceReload = true)
        }
        binding.resultSourcesProgress.isVisible = true
        binding.resultSourceOptions.isGone = true
        binding.resultSourceOptions.removeAllViews()

        viewModel.loadInlineSources(episode, forceReload = forceReload) { result ->
            if (!isSameInlineSourceEpisode(inlineSourceEpisode, episode)) return@loadInlineSources
            inlineSourceResults = episode to result
            showInlineSourceOptions(episode, result)
        }
    }

    private fun showInlineSourceOptions(episode: ResultEpisode, result: LinkLoadingResult) {
        val binding = this.binding ?: return
        if (!isSameInlineSourceEpisode(inlineSourceEpisode, episode)) return

        val links = result.links
        binding.resultSourceOptions.removeAllViews()
        if (links.isEmpty()) {
            binding.resultSourceOptions.isGone = true
            binding.resultSourceCount.isGone = true
            binding.resultSourceShowAll.isGone = true
            binding.resultSourcesProgress.isGone = true
            binding.resultSourcesStatusPanel.isVisible = true
            binding.resultSourcesStatusPanel.isFocusable = true
            binding.resultSourcesStatusPanel.isClickable = true
            binding.resultInlineSources.nextFocusDownId = binding.resultSourcesStatusPanel.id
            binding.resultSourcesStatusPanel.setOnClickListener {
                findInlineSources(forceReload = true)
            }
            binding.resultSourcesStatus.apply {
                text = context.getString(R.string.no_playable_sources_found)
                isVisible = true
            }
            binding.resultRetrySources.apply {
                setText(R.string.retry_sources)
                isEnabled = true
                isVisible = true
            }
            binding.resultCastItems.nextFocusUpId = binding.resultRetrySources.id
            return
        }

        binding.resultSourcesStatusPanel.isGone = true
        binding.resultSourcesStatusPanel.isFocusable = false
        binding.resultSourcesStatusPanel.isClickable = false
        binding.resultSourcesProgress.isGone = true
        binding.resultSourcesStatus.isGone = true
        binding.resultRetrySources.isGone = true
        binding.resultSourceCount.apply {
            text = context.resources.getQuantityString(
                R.plurals.source_links_count,
                links.size,
                links.size
            )
            isVisible = true
        }
        binding.resultSourceOptions.isVisible = true
        val ctx = context ?: return

        val qualityGroups = links.groupBy { it.quality }
            .toList()
            .sortedByDescending { (quality, _) -> quality }
        val visibleGroups = if (inlineSourceShowAll) qualityGroups else qualityGroups.take(2)
        visibleGroups.forEach { (quality, groupLinks) ->
            addInlineSourceQualityGroup(
                ctx,
                binding.resultSourceOptions,
                quality,
                groupLinks,
                result,
                inlineSourceShowAll
            )
        }

        val optionCards = (0 until binding.resultSourceOptions.childCount).mapNotNull { index ->
            (binding.resultSourceOptions.getChildAt(index) as? LinearLayout)?.getChildAt(0)
        }
        optionCards.firstOrNull()?.let { firstCard ->
            firstCard.nextFocusUpId = binding.resultDescription.id
            binding.resultInlineSources.nextFocusDownId = firstCard.id
        }
        binding.resultCastItems.nextFocusUpId = if (links.size > 2) {
            binding.resultSourceShowAll.id
        } else {
            optionCards.lastOrNull()?.id ?: binding.resultInlineSources.id
        }

        binding.resultSourceShowAll.apply {
            isVisible = links.size > 2
            text = if (inlineSourceShowAll) {
                getString(R.string.show_fewer_source_results)
            } else {
                resources.getQuantityString(
                    R.plurals.show_all_source_results,
                    links.size,
                    links.size
                )
            }
            setOnClickListener {
                inlineSourceShowAll = !inlineSourceShowAll
                showInlineSourceOptions(episode, result)
            }
            nextFocusDownId = binding.resultCastItems.id
            nextFocusUpId = optionCards.lastOrNull()?.id ?: binding.resultDescription.id
        }
    }

    private fun addInlineSourceQualityGroup(
        ctx: android.content.Context,
        container: LinearLayout,
        quality: Int,
        links: List<ExtractorLink>,
        result: LinkLoadingResult,
        expanded: Boolean
    ) {
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        val card = MaterialCardView(ctx).apply {
            id = View.generateViewId()
            radius = dp(11).toFloat()
            strokeWidth = dp(1)
            strokeColor = ctx.colorFromAttribute(R.attr.iconColor)
            setCardBackgroundColor(ctx.colorFromAttribute(R.attr.primaryGrayBackground))
            isClickable = true
            isFocusable = true
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(9), dp(8), dp(10), dp(8))
        }
        val qualityBadge = TextView(ctx).apply {
            text = Qualities.getStringByInt(quality)
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(ctx.colorFromAttribute(R.attr.colorPrimary))
            background = GradientDrawable().apply {
                setColor(ctx.colorFromAttribute(R.attr.boxItemBackground))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(36)).apply {
                marginEnd = dp(9)
            }
        }
        val sourceCopy = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sourceNames = links.map { it.name.trim() }.filter(String::isNotBlank).distinct()
        val sourceTitle = TextView(ctx).apply {
            text = if (links.size == 1) {
                sourceNames.firstOrNull() ?: ctx.getString(R.string.source_name)
            } else {
                ctx.resources.getQuantityString(R.plurals.source_count, links.size, links.size)
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 14f
            setTextColor(ctx.colorFromAttribute(R.attr.textColor))
        }
        val sourceMeta = TextView(ctx).apply {
            text = if (links.size == 1) {
                ctx.resources.getQuantityString(R.plurals.source_links_count, 1, 1)
            } else {
                val shownNames = sourceNames.take(2)
                val remaining = (links.size - shownNames.size).coerceAtLeast(0)
                shownNames.joinToString(" · ") + if (remaining > 0) " +$remaining" else ""
            }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 12f
            setTextColor(ctx.colorFromAttribute(R.attr.grayTextColor))
        }
        val action = TextView(ctx).apply {
            text = ctx.getString(if (links.size == 1) R.string.source_row_play else R.string.source_row_view)
            gravity = Gravity.CENTER_VERTICAL
            textSize = 11f
            setTextColor(ctx.colorFromAttribute(R.attr.colorPrimary))
            setPadding(dp(9), 0, 0, 0)
        }

        sourceCopy.addView(sourceTitle)
        sourceCopy.addView(sourceMeta)
        row.addView(qualityBadge)
        row.addView(sourceCopy)
        row.addView(action)
        card.addView(row)
        group.addView(card)

        val detailChips = ChipGroup(ctx).apply {
            isGone = !expanded || links.size < 2
            setChipSpacingHorizontal(dp(5))
            setChipSpacingVertical(dp(2))
            links.forEach { link ->
                addView(createInlineSourceChip(ctx, link.name, link, result))
            }
        }
        if (links.size > 1) group.addView(detailChips)

        card.setOnClickListener {
            if (links.size == 1) {
                playInlineSource(links.first(), result)
            } else {
                detailChips.isVisible = !detailChips.isVisible
            }
        }
        container.addView(group)
    }

    private fun createInlineSourceChip(
        ctx: android.content.Context,
        name: String,
        link: ExtractorLink,
        result: LinkLoadingResult
    ): Chip = Chip(ctx).apply {
        text = name.ifBlank { ctx.getString(R.string.source_name) }
        isCheckable = false
        chipBackgroundColor = ColorStateList.valueOf(ctx.colorFromAttribute(R.attr.boxItemBackground))
        chipStrokeColor = ColorStateList.valueOf(ctx.colorFromAttribute(R.attr.iconColor))
        chipStrokeWidth = ctx.resources.displayMetrics.density
        setTextColor(ctx.colorFromAttribute(R.attr.textColor))
        setOnClickListener { playInlineSource(link, result) }
    }

    private fun playInlineSource(link: ExtractorLink, result: LinkLoadingResult) {
        findNavController().navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(
                ExtractorLinkGenerator(
                    listOf(link),
                    result.subs,
                    result.episode,
                    result.nextEpisode
                ),
                0,
                result.syncData
            )
        )
    }

    private fun isSameInlineSourceEpisode(
        first: ResultEpisode?,
        second: ResultEpisode?
    ): Boolean = when {
        first == null || second == null -> first == second
        else -> first.id == second.id && first.parentId == second.parentId && first.apiName == second.apiName
    }


    private fun openPersonFilmography(person: com.lagradost.cloudstream3.Actor) {
        findNavController().navigate(
            R.id.navigation_person_filmography,
            Bundle().apply {
                putString("personName", person.name)
                putString("personImage", person.image)
            },
        )
    }
}
