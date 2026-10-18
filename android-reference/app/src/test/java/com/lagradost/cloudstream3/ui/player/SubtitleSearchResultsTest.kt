package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSearchResultsTest {
    @Test
    fun noProviderResultsProduceAnEmptyResultList() {
        assertEquals(emptyList<String>(), interleaveSubtitleResults<String>(emptyList()))
        assertEquals(
            emptyList<String>(),
            interleaveSubtitleResults<String>(listOf(emptyList<String>(), emptyList<String>())),
        )
    }

    @Test
    fun providerResultsStayInterleavedWhenListsHaveDifferentSizes() {
        assertEquals(
            listOf("first-a", "second-a", "third-a", "first-b", "third-b", "first-c"),
            interleaveSubtitleResults(
                listOf(
                    listOf("first-a", "first-b", "first-c"),
                    listOf("second-a"),
                    listOf("third-a", "third-b"),
                )
            ),
        )
    }
}
