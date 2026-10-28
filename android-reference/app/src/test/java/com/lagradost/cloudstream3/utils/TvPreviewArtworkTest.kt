package com.lagradost.cloudstream3.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPreviewArtworkTest {
    @Test
    fun artworkReferenceScanIsOnlyNeededWhenCacheExceedsLimit() {
        assertFalse(artworkCacheNeedsReferenceScan(0L, 100L))
        assertFalse(artworkCacheNeedsReferenceScan(100L, 100L))
        assertTrue(artworkCacheNeedsReferenceScan(101L, 100L))
    }
}
