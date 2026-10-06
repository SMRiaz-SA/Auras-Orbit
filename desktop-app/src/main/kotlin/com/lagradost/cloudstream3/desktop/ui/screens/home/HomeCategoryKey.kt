package com.lagradost.cloudstream3.desktop.ui.screens.home

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData

internal fun homeCategoryStateKey(provider: MainAPI, pageData: MainPageData): String =
    "${provider.name}_${provider.mainUrl}_${pageData.name}_${pageData.data}"
