package com.auras.orbit.stremio;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import org.junit.Test;

public final class StremioAddonClientSkipValidationTest {
    @Test public void homeSkipCanExceedLegacyLimitWhileSearchAndDiscoveryRemainBounded()
            throws Exception {
        assertEquals(100_100L,
                StremioAddonClient.validateCatalogSkipValue("100100", true));
        assertRejected("100100", false);
        assertRejected("-1", true);
        assertRejected("not-a-number", true);
        assertRejected("9223372036854775808", true);
    }

    private static void assertRejected(String value, boolean allowLargeHomeSkip) throws Exception {
        try {
            StremioAddonClient.validateCatalogSkipValue(value, allowLargeHomeSkip);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Skip should be rejected: " + value);
    }
}
