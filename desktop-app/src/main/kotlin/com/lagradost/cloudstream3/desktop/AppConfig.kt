package com.lagradost.cloudstream3.desktop

object AppConfig {
    val APP_VERSION = System.getProperty("cloudstream.version")?.takeIf(String::isNotBlank) ?: "1.0.0.00"
    const val GITHUB_REPO = "errorcode26/CS3-desktop-client-unofficial"
}
