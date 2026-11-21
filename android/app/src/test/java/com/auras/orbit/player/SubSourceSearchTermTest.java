package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class SubSourceSearchTermTest {
    @Test public void usesAValidReportedImdbIdAndOtherwiseSearchesByTitle() {
        assertEquals("tt1234567", SubSourceSearchTerm.forQuery("tt1234567", "Example title"));
        assertEquals("Example title", SubSourceSearchTerm.forQuery(null, " Example title "));
        assertEquals("Example title", SubSourceSearchTerm.forQuery("1234567", "Example title"));
        assertNull(SubSourceSearchTerm.forQuery(null, "  "));
    }
}
