package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterRepositoryLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.AddRepoInputBinding
import com.lagradost.cloudstream3.databinding.FragmentExtensionsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.AppContextUtils.addRepositoryDialog
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideProgress
import com.lagradost.cloudstream3.utils.UIHelper.showProgress
import com.lagradost.cloudstream3.utils.setText

class ExtensionsFragment : BaseFragment<FragmentExtensionsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentExtensionsBinding::inflate)
) {

    private val extensionViewModel: ExtensionsViewModel by activityViewModels()
    private val pluginViewModel: PluginsViewModel by activityViewModels()

    override fun onResume() {
        super.onResume()
        afterRepositoryLoadedEvent += ::reloadRepositories
    }

    override fun onStop() {
        super.onStop()
        afterRepositoryLoadedEvent -= ::reloadRepositories
    }

    private fun reloadRepositories(success: Boolean = true) {
        extensionViewModel.loadRepositories()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    override fun onBindingCreated(binding: FragmentExtensionsBinding) {
        setUpToolbar(R.string.extensions)
        setToolBarScrollFlags()

        val repositoryAdapter = RepoAdapter(false, {
            findNavController().navigate(
                R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                PluginsFragment.newInstance(it)
            )
        }, { repo ->
            // Prompt user before deleting repo
            main {
                val uiContext = context ?: binding.root.context
                val builder = AlertDialog.Builder(uiContext)
                val dialogClickListener =
                    DialogInterface.OnClickListener { _, which ->
                        when (which) {
                            DialogInterface.BUTTON_POSITIVE -> {
                                ioSafe {
                                    RepositoryManager.removeRepository(
                                        uiContext.applicationContext,
                                        repo
                                    )
                                    extensionViewModel.loadRepositories()
                                }
                            }

                            DialogInterface.BUTTON_NEGATIVE -> {}
                        }
                    }

                builder.setTitle(R.string.delete_repository)
                    .setMessage(uiContext.getString(R.string.delete_repository_plugins))
                    .setPositiveButton(R.string.delete, dialogClickListener)
                    .setNegativeButton(R.string.cancel, dialogClickListener)
                    .show().setDefaultFocus()
            }
        })
        val repositoryEmptyAdapter = ExtensionsEmptyStateAdapter(
            getString(R.string.repositories_empty_title),
            getString(R.string.repositories_empty_message),
        )
        val installedEmptyAdapter = ExtensionsEmptyStateAdapter(
            getString(R.string.installed_providers_empty_title),
            getString(R.string.installed_providers_empty_message),
        )
        val manageInstalledProviders = {
            findNavController().navigate(
                R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                PluginsFragment.newLocalInstance(getString(R.string.extensions))
            )
        }
        val installedHeaderAdapter = ExtensionsSectionHeaderAdapter(
            title = getString(R.string.installed_providers_title),
            actionText = getString(R.string.manage_installed_providers),
            onAction = manageInstalledProviders,
        )
        val installedProviderAdapter = PluginAdapter(true) {
            val urls = extensionViewModel.repositories.value?.toList() ?: emptyList()
            pluginViewModel.handlePluginAction(activity, urls, it, false)
        }

        binding.repoRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextUp = R.id.settings_toolbar, // FOCUS_SELF, // back has no id so we cant :pensive:
                nextDown = R.id.section_action,
                nextRight = FOCUS_SELF,
                nextLeft = R.id.nav_rail_view
            )
            adapter = ConcatAdapter(
                repositoryAdapter,
                repositoryEmptyAdapter,
                installedHeaderAdapter,
                installedEmptyAdapter,
                installedProviderAdapter,
            )
        }

        observe(extensionViewModel.repositories) { repos ->
            repositoryAdapter.submitList(repos.toList())
            repositoryEmptyAdapter.setVisible(repos.isEmpty())
            pluginViewModel.updatePluginList(binding.root.context, repos.toList())
        }

        observe(extensionViewModel.repositoryCatalogSummaries) { summaries ->
            repositoryAdapter.updateCatalogSummaries(summaries)
        }

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
                nextLeft = R.id.nav_rail_view,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter = PluginAdapter(true) {
                val urls = extensionViewModel.repositories.value?.toList() ?: emptyList()
                pluginViewModel.handlePluginAction(activity, urls, it, false)
            }
        }

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            val installedProviders = list.filter { it.isDownloaded }
            installedProviderAdapter.submitList(installedProviders)
            installedHeaderAdapter.updateCount(installedProviders.size)
            installedEmptyAdapter.setVisible(installedProviders.isEmpty())
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        binding.settingsToolbar.apply {
            val searchItem = menu?.findItem(R.id.search_button)
            val searchView = searchItem?.actionView as? SearchView

            searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionCollapse(p0: MenuItem): Boolean {
                    binding.catalogHeader.isVisible = true
                    binding.pluginRecyclerView.isVisible = false
                    binding.repoRecyclerView.isVisible = true
                    pluginViewModel.search(null)
                    return true

                }

                override fun onMenuItemActionExpand(p0: MenuItem): Boolean {
                    binding.catalogHeader.isGone = true
                    binding.pluginRecyclerView.isVisible = true
                    binding.repoRecyclerView.isVisible = false
                    return true
                }
            })

            // Don't go back if active query
            setNavigationOnClickListener {
                if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }


        val addRepositoryClick = View.OnClickListener {
            val ctx = context ?: return@OnClickListener
            val addBinding = AddRepoInputBinding.inflate(LayoutInflater.from(ctx), null, false)
            val dialog = AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
                .setView(addBinding.root)
                .create()
            dialog.show()

            (activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(
                0
            )?.text?.toString()?.let { copiedText ->
                if (copiedText.contains(RepoAdapter.SHAREABLE_REPO_SEPARATOR)) {
                    // Preserve the existing shareable format: <repository name> : <repository url>.
                    val (name, url) = copiedText.split(
                        RepoAdapter.SHAREABLE_REPO_SEPARATOR,
                        limit = 2
                    )
                    addBinding.repoUrlInput.setText(url.trim())
                    addBinding.repoNameInput.setText(name.trim())
                } else {
                    addBinding.repoUrlInput.setText(copiedText.trim())
                }
            }

            addBinding.applyBtt.setOnClickListener submitListener@{
                val name = addBinding.repoNameInput.text?.toString().orEmpty()
                val address = addBinding.repoUrlInput.text?.toString().orEmpty()
                if (address.isBlank()) {
                    showToast(R.string.error_invalid_url, Toast.LENGTH_SHORT)
                    return@submitListener
                }
                addBinding.applyBtt.showProgress()
                ioSafe {
                    val outcome = try {
                        val url = RepositoryManager.parseRepoUrl(address)
                        if (url.isNullOrBlank()) {
                            CatalogAddOutcome.Failed(ctx.getString(R.string.error_invalid_data))
                        } else {
                            val repository = RepositoryManager.parseRepository(url)
                            if (repository == null) {
                                CatalogAddOutcome.Failed(ctx.getString(R.string.no_repository_found_error))
                            } else {
                                val fixedName = name.ifBlank { repository.name }
                                val newRepo = RepositoryData(repository.iconUrl, fixedName, url)
                                RepositoryManager.addRepository(newRepo)
                                extensionViewModel.loadRepositories()
                                val hasPlugins = !RepositoryManager.getRepoPlugins(newRepo).isNullOrEmpty()
                                CatalogAddOutcome.Added(newRepo, hasPlugins)
                            }
                        }
                    } catch (error: Exception) {
                        CatalogAddOutcome.Failed(
                            error.localizedMessage ?: ctx.getString(R.string.error_invalid_data)
                        )
                    }
                    main {
                        when (outcome) {
                            is CatalogAddOutcome.Failed -> {
                                addBinding.applyBtt.hideProgress()
                                showToast(outcome.message, Toast.LENGTH_LONG)
                            }
                            is CatalogAddOutcome.Added -> {
                                dialog.dismissSafe(activity)
                                if (!outcome.hasPlugins) {
                                    showToast(
                                        R.string.no_plugins_found_error,
                                        Toast.LENGTH_LONG,
                                    )
                                } else {
                                        this@ExtensionsFragment.activity?.addRepositoryDialog(outcome.repository)
                                }
                            }
                        }
                    }
                }
            }
            addBinding.cancelBtt.setOnClickListener { dialog.dismissSafe(activity) }
        }


        val isTv = isLayout(TV)
        binding.apply {
            addRepoButton.isFocusableInTouchMode = isTv
            addRepoButton.setOnClickListener(addRepositoryClick)
        }
        reloadRepositories()
    }
}

private sealed interface CatalogAddOutcome {
    data class Added(val repository: RepositoryData, val hasPlugins: Boolean) : CatalogAddOutcome
    data class Failed(val message: String) : CatalogAddOutcome
}
