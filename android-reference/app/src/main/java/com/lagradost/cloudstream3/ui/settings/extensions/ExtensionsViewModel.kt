package com.lagradost.cloudstream3.ui.settings.extensions

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryData(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("url") @SerialName("url") val url: String,
) {
    constructor(name: String, url: String): this(null, name, url)
}

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"

class ExtensionsViewModel : ViewModel() {
    data class RepositoryCatalogSummary(
        val providerCount: Int?,
        val isAvailable: Boolean,
    )

    private val _repositories = MutableLiveData<Array<RepositoryData>>()
    val repositories: LiveData<Array<RepositoryData>> = _repositories

    private val _repositoryCatalogSummaries =
        MutableLiveData<Map<String, RepositoryCatalogSummary>>(emptyMap())
    val repositoryCatalogSummaries: LiveData<Map<String, RepositoryCatalogSummary>> =
        _repositoryCatalogSummaries

    private fun repos() = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
        ?: emptyArray()) + PREBUILT_REPOSITORIES

    fun loadRepositories() {
        val urls = repos()
        _repositories.postValue(urls)
        loadRepositoryCatalogSummaries(urls.toList())
    }

    // DO not use viewModelScope.launchSafe, it will ANR on slow internet
    private fun loadRepositoryCatalogSummaries(repositories: List<RepositoryData>) = ioSafe {
        val summaries = repositories.amap { repository ->
            val providers = RepositoryManager.getRepoPlugins(repository)
            repository.url to RepositoryCatalogSummary(
                providerCount = providers?.size,
                isAvailable = providers != null,
            )
        }
        _repositoryCatalogSummaries.postValue(summaries.toMap())
    }
}
