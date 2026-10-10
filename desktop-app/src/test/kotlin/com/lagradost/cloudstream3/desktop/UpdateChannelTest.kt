package com.lagradost.cloudstream3.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
