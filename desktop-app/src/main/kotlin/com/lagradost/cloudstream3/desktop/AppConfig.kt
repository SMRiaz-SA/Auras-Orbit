package com.lagradost.cloudstream3.desktop

object AppConfig {
    val APP_VERSION = System.getProperty("cloudstream.version")?.takeIf(String::isNotBlank) ?: "1.0.0.06"
    const val GITHUB_REPO = "SMRiaz-SA/Auras-Orbit"
}
