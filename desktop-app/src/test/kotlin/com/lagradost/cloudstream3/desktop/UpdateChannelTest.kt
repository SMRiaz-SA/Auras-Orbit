package com.lagradost.cloudstream3.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateChannelTest {
    @Test
    fun stableAndPreviewChannelsSelectTheirOwnNewestRelease() {
        val stable = GitHubRelease("v0.2.0.24", "stable", null, "https://github.com/example", "2026-10-07")
        val preview = stable.copy(tag_name = "v0.3.0.00", prerelease = true)
        assertEquals(stable, AppUpdater.selectRelease(listOf(preview, stable), false))
        assertEquals(preview, AppUpdater.selectRelease(listOf(preview, stable), true))
        assertNull(AppUpdater.selectRelease(listOf(preview.copy(draft = true)), true))
    }
}
