package com.lagradost.cloudstream3.desktop.explore.models

data class TorrentSearchResult(
    val name: String,
    val magnetLink: String,
    val infoHash: String?,
    val sizeBytes: Long,
    val humanSize: String?,
    val seeders: Int?,
    val leechers: Int?,
    val isVerified: Boolean,
)
