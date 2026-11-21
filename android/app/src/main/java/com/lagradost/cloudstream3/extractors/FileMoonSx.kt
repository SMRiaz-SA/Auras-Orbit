package com.lagradost.cloudstream3.extractors

/** Pinned Filemoon alias for filemoon.sx. */
class FileMoonSx : FilemoonV2() {
    private var aliasName = "FileMoonSx"
    private var aliasMainUrl = "https://filemoon.sx"

    override fun getName(): String = aliasName

    override fun setName(value: String) {
        aliasName = value
    }

    override fun getMainUrl(): String = aliasMainUrl

    override fun setMainUrl(value: String) {
        aliasMainUrl = value
    }
}
