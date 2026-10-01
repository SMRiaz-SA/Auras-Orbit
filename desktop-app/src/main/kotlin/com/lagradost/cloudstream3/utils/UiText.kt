package com.lagradost.cloudstream3.utils

sealed class UiText {
    data class PlainText(val value: String) : UiText()
}
