package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SubdlLanguageTagTest {
    @Test public void normalizesCaseAndSeparatorsBeforeRegionalAliasLookup() {
        assertEquals("pt-br", SubdlLanguageTag.normalize("pt-BR"));
        assertEquals("zh-hant", SubdlLanguageTag.normalize("zh_Hant"));
        assertEquals("en", SubdlLanguageTag.normalize("EN"));
        assertEquals("", SubdlLanguageTag.normalize("  "));
    }
}
