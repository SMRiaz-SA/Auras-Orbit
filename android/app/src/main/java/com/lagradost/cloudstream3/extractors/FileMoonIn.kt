package com.lagradost.cloudstream3.extractors

/** Pinned Filemoon alias for filemoon.in. */
class FileMoonIn : FilemoonV2() {
    private var aliasName = "FileMoon"
    private var aliasMainUrl = "https://filemoon.in"

    override fun getName(): String = aliasName

    override fun setName(value: String) {
        aliasName = value
    }

    override fun getMainUrl(): String = aliasMainUrl

    override fun setMainUrl(value: String) {
        aliasMainUrl = value
    }
}
