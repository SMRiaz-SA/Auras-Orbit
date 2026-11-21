package com.lagradost.cloudstream3.syncproviders.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.Collections;
import org.junit.Test;

public final class Addic7edSearchPagePolicyTest {
    @Test public void parsedSearchPageWithNoTitleMatchIsAnEmptyResult() {
        assertEquals(Collections.emptyList(), Addic7edSearchPagePolicy.successfulTitleMatch(() -> null));
    }

    @Test public void titlePageParseFailureIsNotConvertedToNoMatch() {
        IllegalStateException expected = new IllegalStateException("page parse failed");
        try {
            Addic7edSearchPagePolicy.successfulTitleMatch(() -> { throw expected; });
            fail("Expected the page parse failure to propagate.");
        } catch (IllegalStateException actual) {
            assertSame(expected, actual);
        }
    }

    @Test public void unsuccessfulHttpResponseRemainsProviderFailure() {
        try {
            Addic7edSearchPagePolicy.requireSuccessfulResponse(false);
            fail("Expected unsuccessful HTTP status to remain a provider failure.");
        } catch (IllegalStateException expected) {
            assertEquals("Addic7ed returned an unsuccessful HTTP response.", expected.getMessage());
        }
    }

    @Test public void successfulHttpResponsePassesStatusGuard() {
        Addic7edSearchPagePolicy.requireSuccessfulResponse(true);
    }
}
