package com.lagradost.cloudstream3.extractors

/** Pinned Filemoon alias for filemoon.to. */
class FileMoon : FilemoonV2() {
    private var aliasName = "FileMoon"
    private var aliasMainUrl = "https://filemoon.to"

    override fun getName(): String = aliasName

    override fun setName(value: String) {
        aliasName = value
    }

    override fun getMainUrl(): String = aliasMainUrl

    override fun setMainUrl(value: String) {
        aliasMainUrl = value
    }
}
