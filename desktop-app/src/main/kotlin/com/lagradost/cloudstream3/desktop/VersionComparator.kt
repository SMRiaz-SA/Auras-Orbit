package com.lagradost.cloudstream3.desktop

/** Compares numeric stable release versions. */
internal object VersionComparator {
    private val versionPattern = Regex("^\\d+(?:\\.\\d+){2,3}$")

    fun isStable(version: String): Boolean = parse(version) != null

    fun compare(first: String, second: String): Int {
        val left = parse(first) ?: return 0
        val right = parse(second) ?: return 0
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val difference = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun parse(version: String): List<Long>? =
        version.removePrefix("v")
            .takeIf(versionPattern::matches)
            ?.split('.')
            ?.map { it.toLongOrNull() ?: return null }
}
