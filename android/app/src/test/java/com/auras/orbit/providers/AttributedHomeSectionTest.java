package com.auras.orbit.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Collections;
import org.junit.Test;

public final class AttributedHomeSectionTest {
    @Test public void continuationShelfRetainsOriginalProviderOrder() {
        ProviderDescriptor provider = new ProviderDescriptor(
                "provider-b", "Provider B", true, false, false, false, false);
        HomeSection shelf = new HomeSection("catalog-b", "Catalog B",
                Collections.emptyList(), "");
        AttributedHomeSection continuation = new AttributedHomeSection(provider, 0, shelf);

        AttributedHomeSection selectedProviderOrder = continuation.withSourceOrder(3);

        assertEquals(3, selectedProviderOrder.sourceOrder);
        assertSame(provider, selectedProviderOrder.provider);
        assertSame(shelf, selectedProviderOrder.section);
    }
}
