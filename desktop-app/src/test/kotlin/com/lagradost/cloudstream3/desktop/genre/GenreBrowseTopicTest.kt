package com.lagradost.cloudstream3.desktop.genre

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GenreBrowseTopicTest {
    @Test
    fun `curated story topics have keyword bundles for TMDB lookup`() {
        assertEquals(
            listOf(
                "Murder",
                "True Crime",
                "Heist",
                "Revenge",
                "Survival",
                "Time Travel",
                "Supernatural",
                "Post-apocalyptic",
            ),
            GenreBrowseTopic.entries.map { it.label },
        )
        assertFalse(GenreBrowseTopic.entries.any { it.keywordSearchTerms.isEmpty() })
    }
}
