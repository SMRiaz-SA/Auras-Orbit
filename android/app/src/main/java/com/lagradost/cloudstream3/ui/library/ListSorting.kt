package com.lagradost.cloudstream3.ui.library

/** Library sort values mirrored for the CloudStream sync-provider ABI. */
enum class ListSorting(val stringRes: Int) {
    Query(0),
    RatingHigh(0),
    RatingLow(0),
    UpdatedNew(0),
    UpdatedOld(0),
    AlphabeticalA(0),
    AlphabeticalZ(0),
    ReleaseDateNew(0),
    ReleaseDateOld(0),
}
