package com.lagradost.cloudstream3.desktop.ui.screens

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse

data class CategoryGridContent(
    val items: List<SearchResponse>,
    val pageRequest: MainPageRequest? = null,
    val sectionName: String? = null,
    val hasNext: Boolean = false,
    val itemProviders: Map<String, MainAPI> = emptyMap(),
    val mergedSources: List<CategoryGridPageSource> = emptyList(),
)

data class CategoryGridPageSource(
    val provider: MainAPI,
    val request: MainPageRequest,
    val sectionName: String,
    val nextPage: Int = 2,
    val hasNext: Boolean = true,
    val lastError: String? = null,
)

fun categoryGridItemKey(providerName: String, url: String): String = "$providerName\u0000$url"

object CategoryGridCache {
    private const val MAX_ENTRIES = 30
    private val lock = Any()
    private val cache = object : java.util.LinkedHashMap<String, CategoryGridContent>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CategoryGridContent>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun put(providerName: String, title: String, items: List<SearchResponse>) {
        putContent(providerName, title, CategoryGridContent(items))
    }

    fun putHomeCategory(
        providerName: String,
        title: String,
        items: List<SearchResponse>,
        request: MainPageRequest,
        hasNext: Boolean,
        sectionName: String = title,
    ) {
        putContent(
            providerName,
            title,
            CategoryGridContent(items, request, sectionName, hasNext),
        )
    }

    fun putMergedHomeCategory(
        providerName: String,
        title: String,
        items: List<SearchResponse>,
        itemProviders: Map<String, MainAPI>,
        sources: List<CategoryGridPageSource>,
    ) {
        putContent(
            providerName,
            title,
            CategoryGridContent(
                items = items,
                itemProviders = itemProviders,
                mergedSources = sources,
            ),
        )
    }

    private fun putContent(providerName: String, title: String, content: CategoryGridContent) {
        synchronized(lock) {
            cache["$providerName-$title"] = content
        }
    }

    fun get(providerName: String, title: String): List<SearchResponse>? {
        return synchronized(lock) {
            cache["$providerName-$title"]?.items
        }
    }

    fun getContent(providerName: String, title: String): CategoryGridContent? {
        return synchronized(lock) {
            cache["$providerName-$title"]
        }
    }

    fun remove(providerName: String, title: String) {
        synchronized(lock) {
            cache.remove("$providerName-$title")
        }
    }
}
