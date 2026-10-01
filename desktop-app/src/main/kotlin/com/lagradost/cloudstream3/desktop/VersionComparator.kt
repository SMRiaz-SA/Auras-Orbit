package com.lagradost.cloudstream3.desktop

/** Compares numeric releases and SemVer pre-release identifiers. */
internal object VersionComparator {
    private val versionPattern = Regex("^(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

    fun compare(first: String, second: String): Int {
        val left = parse(first) ?: return 0
        val right = parse(second) ?: return 0
        val count = maxOf(left.first.size, right.first.size)
        for (index in 0 until count) {
            val difference = left.first.getOrElse(index) { 0 }.compareTo(right.first.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }

        val leftPre = left.second
        val rightPre = right.second
        if (leftPre == null) return if (rightPre == null) 0 else 1
        if (rightPre == null) return -1
        for (index in 0 until minOf(leftPre.size, rightPre.size)) {
            val a = leftPre[index]
            val b = rightPre[index]
            val aNumber = a.toLongOrNull()
            val bNumber = b.toLongOrNull()
            val difference = when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
            if (difference != 0) return difference
        }
        return leftPre.size.compareTo(rightPre.size)
    }

    private fun parse(version: String): Pair<List<Long>, List<String>?>? {
        val match = versionPattern.matchEntire(version.removePrefix("v")) ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        val preRelease = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.')
        return numbers to preRelease
    }
}
