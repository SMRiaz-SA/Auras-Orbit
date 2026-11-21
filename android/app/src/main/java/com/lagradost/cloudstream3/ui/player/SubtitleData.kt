package com.lagradost.cloudstream3.ui.player

import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

enum class SubtitleOrigin { URL, DOWNLOADED_FILE, EMBEDDED_IN_VIDEO }

@Serializable
data class SubtitleData(
    @SerialName("originalName") val originalName: String,
    @SerialName("nameSuffix") val nameSuffix: String,
    @SerialName("url") val url: String,
    @SerialName("origin") val origin: SubtitleOrigin,
    @SerialName("mimeType") val mimeType: String,
    @SerialName("headers") val headers: Map<String, String>,
    @SerialName("languageCode") val languageCode: String?,
) {
    @SerialName("name") val name = "$originalName $nameSuffix"
    @JsonIgnore fun getId(): String = if (origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) url else "$url|$name"
    fun matchesLanguageCode(langCode: String): Boolean = getIETF_tag() == langCode
    @JsonIgnore fun getIETF_tag(): String? = languageTag(languageCode) ?: languageTag(originalName)
    @JsonIgnore fun getFixedUrl(): String = if (url.startsWith("//")) "https:$url" else url

    private fun languageTag(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.matches(Regex("[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8})*"))) {
            val tag = Locale.forLanguageTag(text.replace('_', '-')).toLanguageTag()
            if (tag != "und") return tag
        }
        return Locale.getAvailableLocales().firstOrNull {
            it.language.isNotEmpty() && (it.getDisplayLanguage(Locale.ENGLISH).equals(text, true)
                || it.getDisplayLanguage(it).equals(text, true))
        }?.language
    }
}
