package com.lagradost.cloudstream3.ui.settings.extensions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.RepositoryItemBinding
import com.lagradost.cloudstream3.databinding.RepositoryItemTvBinding
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt

class RepoAdapter(
    val isSetup: Boolean,
    val clickCallback: RepoAdapter.(RepositoryData) -> Unit,
    val imageClickCallback: RepoAdapter.(RepositoryData) -> Unit,
    /** In setup mode the trash icons will be replaced with download icons */
) :
    NoStateAdapter<RepositoryData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
        a.url == b.url
    })) {

    private var catalogSummaries: Map<String, ExtensionsViewModel.RepositoryCatalogSummary> = emptyMap()

    fun updateCatalogSummaries(summaries: Map<String, ExtensionsViewModel.RepositoryCatalogSummary>) {
        catalogSummaries = summaries
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val layout = if (isLayout(TV)) RepositoryItemTvBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ) else RepositoryItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolderState(layout)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is RepositoryItemBinding -> clearImage(binding.entryIcon)
            is RepositoryItemTvBinding -> clearImage(binding.entryIcon)
        }
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: RepositoryData, position: Int) {
        val isPrebuilt = PREBUILT_REPOSITORIES.contains(item)
        val drawable =
            if (isSetup) R.drawable.netflix_download else R.drawable.ic_baseline_delete_outline_24
        when (val binding = holder.view) {
            is RepositoryItemTvBinding -> {
                binding.apply {
                    val hasAction = !isPrebuilt || isSetup
                    actionButton.isVisible = hasAction
                    actionButton.isFocusable = hasAction
                    actionButton.isClickable = hasAction
                    if (hasAction) {
                        actionButton.setImageResource(drawable)
                        actionButton.contentDescription = root.context.getString(
                            if (isSetup) R.string.download else R.string.delete_repository
                        )
                        actionButton.setOnClickListener {
                            imageClickCallback(item)
                        }
                    }

                    repositoryItemRoot.setOnClickListener {
                        clickCallback(item)
                    }
                    mainText.text = item.name
                    subText.text = item.catalogSubtitle(binding.root.context)
                    if (!item.iconUrl.isNullOrEmpty()) {
                        entryIcon.loadImage(item.iconUrl) {
                            error(
                                getImageFromDrawable(
                                    binding.root.context,
                                    R.drawable.ic_github_logo
                                )
                            )
                        }
                    } else {
                        entryIcon.loadImage(R.drawable.ic_github_logo)
                    }
                }
            }

            is RepositoryItemBinding -> {
                binding.apply {
                    val hasAction = !isPrebuilt || isSetup
                    actionButton.isVisible = hasAction
                    actionButton.isFocusable = hasAction
                    actionButton.isClickable = hasAction
                    if (hasAction) {
                        actionButton.setImageResource(drawable)
                        actionButton.contentDescription = root.context.getString(
                            if (isSetup) R.string.download else R.string.delete_repository
                        )
                        actionButton.setOnClickListener {
                            imageClickCallback(item)
                        }
                    }

                    repositoryItemRoot.setOnClickListener {
                        clickCallback(item)
                    }

                    repositoryItemRoot.setOnLongClickListener {
                        val shareableRepoData =
                            "${item.name}$SHAREABLE_REPO_SEPARATOR\n ${item.url}"
                        clipboardHelper(txt(R.string.repo_copy_label), shareableRepoData)
                        true
                    }

                    mainText.text = item.name
                    subText.text = item.catalogSubtitle(binding.root.context)
                    if (!item.iconUrl.isNullOrEmpty()) {
                        entryIcon.loadImage(item.iconUrl) {
                            error(
                                getImageFromDrawable(
                                    binding.root.context,
                                    R.drawable.ic_github_logo
                                )
                            )
                        }
                    } else {
                        entryIcon.loadImage(R.drawable.ic_github_logo)
                    }
                }
            }
        }
    }

    private fun RepositoryData.catalogSubtitle(context: android.content.Context): String {
        val summary = catalogSummaries[url] ?: return url
        if (!summary.isAvailable) return context.getString(R.string.repository_connection_unavailable)

        val providerCount = summary.providerCount ?: 0
        return context.resources.getQuantityString(
            R.plurals.repository_connected_provider_count,
            providerCount,
            providerCount,
        )
    }

    companion object {
        const val SHAREABLE_REPO_SEPARATOR = " : "
    }
}
