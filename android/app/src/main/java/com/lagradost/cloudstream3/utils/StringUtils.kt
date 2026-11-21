package com.lagradost.cloudstream3.utils

import io.ktor.http.encodeURLParameter

object StringUtils {
    fun String.encodeUrl(): String = encodeURLParameter()

    fun String.encodeUri(): String = encodeUrl()
}
