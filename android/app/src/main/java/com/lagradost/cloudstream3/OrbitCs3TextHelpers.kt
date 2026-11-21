package com.lagradost.cloudstream3

/** Source-backed text helpers shared by the Java CloudStream API facade. */
object OrbitCs3TextHelpers {
    @JvmStatic
    fun fixTitle(str: String): String = str.split(" ").joinToString(" ") {
        it.lowercase().replaceFirstChar(Char::titlecase)
    }
}
