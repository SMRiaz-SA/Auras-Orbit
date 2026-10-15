package com.lagradost.cloudstream3.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GithubReleaseVersionTest {
    @Test
    fun newerReleaseTagIsAnUpgradeIncludingBuildComponent() {
        assertTrue(GithubReleases.isNewerStableVersion("v1.0.0.01", "1.0.0.00"))
        assertTrue(GithubReleases.isNewerStableVersion("1.0.1", "1.0.0.99"))
    }

    @Test
    fun equalOlderAndNonStableTagsAreNotUpgrades() {
        assertFalse(GithubReleases.isNewerStableVersion("v1.0.0.01", "1.0.0.01"))
        assertFalse(GithubReleases.isNewerStableVersion("v1.0.0.00", "1.0.0.01"))
        assertFalse(GithubReleases.isNewerStableVersion("v1.1.0-beta.1", "1.0.0.01"))
    }

    @Test
    fun versionCodeBoundsMatchTheAndroidBuildConfiguration() {
        assertTrue(GithubReleases.isNewerStableVersion("21.0.0", "20.99.99.9999"))
        assertFalse(GithubReleases.isNewerStableVersion("21.0.0.1", "1.0.0.01"))
        assertFalse(GithubReleases.isNewerStableVersion("0.0.0", "0.0.0.1"))
    }

    @Test
    fun oneThousandGeneratedVersionPairsMatchNumericComponentOrdering() {
        val random = Random(0xA0A5)
        repeat(1_000) { caseNumber ->
            val candidate = randomVersion(random)
            val current = randomVersion(random)
            val candidateTag = candidate.toReleaseTag(random.nextBoolean())
            val currentTag = current.toReleaseTag(random.nextBoolean())

            assertEquals(
                "case $caseNumber: $candidateTag compared with $currentTag",
                candidate.compareToVersion(current) > 0,
                GithubReleases.isNewerStableVersion(candidateTag, currentTag),
            )
        }
    }

    @Test
    fun oneThousandGeneratedAssetSetsChooseThePreferredDownloadableApk() {
        val random = Random(0xA55E7)
        val apkContentType = "application/vnd.android.package-archive"
        repeat(1_000) { caseNumber ->
            val assets = List(random.nextInt(1, 8)) {
                val name = listOf("Auras-Orbit.apk", "backup.APK", "notes.zip", "other.bin")
                    .random(random)
                val contentType = listOf(apkContentType, "application/octet-stream", "text/plain")
                    .random(random)
                val url = if (random.nextInt(5) == 0) "" else "https://example.invalid/$caseNumber/$name"
                GithubReleases.GithubAsset(name, 1, url, contentType)
            }
            val eligible = assets.filter { asset ->
                asset.browserDownloadUrl.isNotBlank() &&
                    (asset.contentType.equals(apkContentType, ignoreCase = true) ||
                        asset.name.endsWith(".apk", ignoreCase = true))
            }
            val expected = eligible.firstOrNull { it.name.equals("Auras-Orbit.apk", ignoreCase = true) }
                ?: eligible.firstOrNull()

            assertEquals(
                "asset selection case $caseNumber",
                expected,
                GithubReleases.selectApkAsset(assets, apkContentType),
            )
        }
    }

    private data class NumericVersion(val major: Int, val minor: Int, val patch: Int, val build: Int) {
        fun toReleaseTag(withVPrefix: Boolean): String {
            val buildSegment = if (build == 0) "" else ".$build"
            return (if (withVPrefix) "v" else "") + "$major.$minor.$patch$buildSegment"
        }

        fun compareToVersion(other: NumericVersion): Int =
            compareValuesBy(this, other, NumericVersion::major, NumericVersion::minor, NumericVersion::patch, NumericVersion::build)
    }

    private fun randomVersion(random: Random) = NumericVersion(
        major = random.nextInt(1, 21),
        minor = random.nextInt(0, 100),
        patch = random.nextInt(0, 100),
        build = random.nextInt(0, 10_000),
    )

}
