package com.lagradost.cloudstream3.desktop

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateChannelTest {
    @Test
    fun onlyTheNewestStableReleaseIsSelected() {
        val olderStable = GitHubRelease("v0.9.0", "older stable", null, "https://github.com/example", "2026-10-08")
        val stable = GitHubRelease("v1.0.0.00", "stable", null, "https://github.com/example", "2026-10-10")
        val prerelease = stable.copy(tag_name = "v1.1.0-beta.1", prerelease = true)
        val mislabeledPrerelease = stable.copy(tag_name = "v1.1.0-rc.1")
        val draft = stable.copy(tag_name = "v2.0.0", draft = true)

        assertEquals(stable, AppUpdater.selectRelease(listOf(olderStable, prerelease, mislabeledPrerelease, draft, stable)))
        assertNull(AppUpdater.selectRelease(listOf(prerelease, mislabeledPrerelease, draft)))
    }

    @Test
    fun windowsUpdateRequiresAnInstallerOrMatchingPortableArchive() {
        val release = GitHubRelease("v1.0.0.01", "stable", null, "https://github.com/example", "2026-10-14")

        assertFalse(release.hasWindowsPackage())
        assertTrue(
            release.copy(
                assets = listOf(ReleaseAsset("Auras-Orbit-Portable-1.0.0.01.zip", "https://example.invalid/app.zip")),
            ).hasWindowsPackage(),
        )
        assertTrue(
            release.copy(
                assets = listOf(ReleaseAsset("Auras-Orbit-Setup.exe", "https://example.invalid/setup.exe")),
            ).hasWindowsPackage(),
        )
        assertFalse(
            release.copy(
                assets = listOf(ReleaseAsset("Auras-Orbit-Portable-1.0.0.00.zip", "https://example.invalid/old.zip")),
            ).hasWindowsPackage(),
        )
    }

    @Test
    fun newestEligibleWindowsReleaseSkipsAssetlessAndAlreadyInstalledReleases() {
        val older = GitHubRelease(
            "v1.0.0.01",
            "older packaged",
            null,
            "https://github.com/example/older",
            "2026-10-14",
            assets = listOf(ReleaseAsset("Auras-Orbit-Portable-1.0.0.01.zip", "https://example.invalid/older.zip")),
        )
        val latestWithoutWindowsAssets = older.copy(
            tag_name = "v1.0.0.03",
            name = "newer source-only release",
            assets = emptyList(),
        )
        val newestPackaged = older.copy(
            tag_name = "v1.0.0.02",
            name = "newest packaged",
            assets = listOf(ReleaseAsset("Auras-Orbit-Setup.exe", "https://example.invalid/setup.exe")),
        )
        val releases = listOf(older, latestWithoutWindowsAssets, newestPackaged)

        assertEquals(newestPackaged, AppUpdater.selectWindowsUpdate(releases, "1.0.0.00"))
        assertNull(AppUpdater.selectWindowsUpdate(releases, "1.0.0.02"))
    }

    @Test
    fun oneThousandGeneratedReleaseListsChooseTheNewestEligibleWindowsUpdate() {
        val random = Random(0x0B17)
        repeat(1_000) { caseNumber ->
            val installedBuild = random.nextInt(1_000, 5_000)
            val installedVersion = "1.2.3.$installedBuild"
            val releases = (-3..5).map { offset ->
                val build = installedBuild + offset
                val version = "1.2.3.$build"
                val withInstaller = random.nextBoolean()
                val withPortable = random.nextBoolean()
                val assets = buildList {
                    if (withInstaller) {
                        add(ReleaseAsset("Auras-Orbit-Setup.exe", "https://example.invalid/$caseNumber/setup.exe"))
                    }
                    if (withPortable) {
                        add(ReleaseAsset("Auras-Orbit-Portable-$version.zip", "https://example.invalid/$caseNumber/$version.zip"))
                    }
                    if (random.nextBoolean()) {
                        add(ReleaseAsset("Auras-Orbit-Portable-9.9.9.99.zip", "https://example.invalid/stale.zip"))
                    }
                }
                GitHubRelease(
                    tag_name = (if (random.nextBoolean()) "v" else "") + version,
                    name = "generated $caseNumber/$offset",
                    body = null,
                    html_url = "https://github.com/example/$caseNumber/$offset",
                    published_at = "2026-10-14",
                    assets = assets,
                ) to (withInstaller || withPortable)
            }
            val expected = releases
                .filter { (release, hasPackage) -> hasPackage && release.tag_name.removePrefix("v").substringAfterLast('.').toInt() > installedBuild }
                .maxByOrNull { (release, _) ->
                    // Builds share the same major, minor, and patch components in each generated list.
                    release.tag_name.removePrefix("v").substringAfterLast('.').toInt()
                }
                ?.first

            val actual = AppUpdater.selectWindowsUpdate(releases.map { it.first }, installedVersion)
            assertEquals(expected?.tag_name, actual?.tag_name, "generated release selection case $caseNumber")
        }
    }

    @Test
    fun desktopUpdaterTargetsTheOrbitReleaseRepository() {
        assertEquals("SMRiaz-SA/Auras-Orbit", AppConfig.GITHUB_REPO)
    }
}
