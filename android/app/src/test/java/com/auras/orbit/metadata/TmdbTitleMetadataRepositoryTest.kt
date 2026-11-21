package com.auras.orbit.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TmdbTitleMetadataRepositoryTest {
    @Test
    fun rejectsInvalidTypedIdsAndLanguageTagsBeforeCallingSource() {
        val source = FakeSource { _, _, _ -> success(603) }
        val repository = TmdbTitleMetadataRepository(source)

        assertIllegalArgument { repository.load(0, "MOVIE") }
        assertIllegalArgument { repository.load(-1, "MOVIE") }
        assertIllegalArgument { repository.load(603, "movie") }
        assertIllegalArgument { repository.load(603, "MOVIE", "not a locale") }
        assertIllegalArgument { repository.load(603, "MOVIE", "en-u") }
        assertIllegalArgument { repository.load(603, "MOVIE", "und") }
        assertIllegalArgument { repository.load(603, "MOVIE", "x-private") }

        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun acceptsAndCanonicalizesGrandfatheredLanguageTagBeforeSourceCall() {
        val source = FakeSource { id, _, _ ->
            success(id, TmdbTitleMetadataBody(id, title = "Localized title", overview = "Localized overview"))
        }

        val record = TmdbTitleMetadataRepository(source).load(603, "MOVIE", "i-klingon")

        assertEquals(603, record?.catalogId)
        assertEquals(listOf(Request(603, "MOVIE", "tlh")), source.requests)
    }

    @Test
    fun returnsDisplayOnlyRecordForTheExactRequestedCatalogIdAndKind() {
        val source = FakeSource { _, _, _ ->
            success(603, TmdbTitleMetadataBody(603, title = "The Matrix"))
        }
        val repository = TmdbTitleMetadataRepository(source)

        val record = repository.load(603, "MOVIE")

        assertEquals(603, record?.catalogId)
        assertEquals("MOVIE", record?.kind)
        assertEquals("The Matrix", record?.title)
        assertEquals(listOf(Request(603, "MOVIE", "en-US")), source.requests)
    }

    @Test
    fun rejectsResponseWithAnIdDifferentFromTheRequestedId() {
        val source = FakeSource { _, _, _ ->
            success(604, TmdbTitleMetadataBody(604, title = "Wrong record"))
        }

        assertNull(TmdbTitleMetadataRepository(source).load(603, "MOVIE", "fr-FR"))
        assertEquals(listOf(Request(603, "MOVIE", "fr-FR")), source.requests)
    }

    @Test
    fun usesEnglishOnlyToFillMissingLocalizedDisplayText() {
        val source = FakeSource { id, kind, language ->
            when (language) {
                "fr-FR" -> success(
                    id,
                    TmdbTitleMetadataBody(
                        id = id,
                        title = "Le Matrix",
                        overview = " ",
                        posterPath = "/localized.jpg",
                    ),
                )
                "en-US" -> success(
                    id,
                    TmdbTitleMetadataBody(
                        id = id,
                        title = "The Matrix",
                        overview = "A computer hacker learns the truth.",
                    ),
                )
                else -> error("Unexpected language $language for $kind")
            }
        }

        val record = TmdbTitleMetadataRepository(source).load(603, "MOVIE", "fr_FR")

        assertEquals("Le Matrix", record?.title)
        assertEquals("A computer hacker learns the truth.", record?.synopsis)
        assertEquals("https://image.tmdb.org/t/p/w500/localized.jpg", record?.posterUrl)
        assertEquals(
            listOf(Request(603, "MOVIE", "fr-FR"), Request(603, "MOVIE", "en-US")),
            source.requests,
        )
    }

    @Test
    fun rateLimitIsTypedAndCarriesNumericRetryAfter() {
        val repository = TmdbTitleMetadataRepository(
            FakeSource { _, _, _ -> TmdbTitleMetadataResponse(429, "45") },
        )

        val failure = captureFailure { repository.load(603, "MOVIE") }

        assertTrue(failure is TmdbTitleMetadataFailure.RateLimited)
        assertEquals(429, (failure as TmdbTitleMetadataFailure).statusCode)
        assertEquals(45L, (failure as TmdbTitleMetadataFailure.RateLimited).retryAfterSeconds)
    }

    @Test
    fun otherHttpFailuresRetainTheirStatusCode() {
        val repository = TmdbTitleMetadataRepository(
            FakeSource { _, _, _ -> TmdbTitleMetadataResponse(503) },
        )

        val failure = captureFailure { repository.load(603, "SERIES") }

        assertTrue(failure is TmdbTitleMetadataFailure.HttpStatus)
        assertEquals(503, (failure as TmdbTitleMetadataFailure).statusCode)
    }

    @Test
    fun transportFailuresAreWrappedAsTypedFailures() {
        val repository = TmdbTitleMetadataRepository(
            FakeSource { _, _, _ -> throw IOException("fixture transport failure") },
        )

        val failure = captureFailure { repository.load(603, "MOVIE") }

        assertTrue(failure is TmdbTitleMetadataFailure.Transport)
        assertEquals("fixture transport failure", failure.cause?.message)
    }

    private fun success(
        id: Int,
        body: TmdbTitleMetadataBody = TmdbTitleMetadataBody(id),
    ) = TmdbTitleMetadataResponse(200, body = body)

    private fun assertIllegalArgument(action: () -> Unit) {
        val failure = captureFailure(action)
        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    private fun captureFailure(action: () -> Unit): Throwable {
        try {
            action()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected operation to fail")
    }

    private class FakeSource(
        private val response: (Int, String, String) -> TmdbTitleMetadataResponse,
    ) : TmdbTitleMetadataSource {
        val requests = mutableListOf<Request>()

        override fun load(id: Int, kind: String, languageTag: String): TmdbTitleMetadataResponse {
            requests += Request(id, kind, languageTag)
            return response(id, kind, languageTag)
        }
    }

    private data class Request(val id: Int, val kind: String, val languageTag: String)
}
