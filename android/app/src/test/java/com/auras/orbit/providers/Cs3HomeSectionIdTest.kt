package com.auras.orbit.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Cs3HomeSectionIdTest {
    @Test fun parsesStableMainPageAndShelfIndices() {
        assertEquals(Pair(4, 2), parseCs3HomeSectionId("sample.plugin", "sample.plugin:home:4:2"))
    }

    @Test fun rejectsOtherProvidersMalformedIndicesAndExtraSegments() {
        assertNull(parseCs3HomeSectionId("sample.plugin", "other.plugin:home:4:2"))
        assertNull(parseCs3HomeSectionId("sample.plugin", "sample.plugin:home:x:2"))
        assertNull(parseCs3HomeSectionId("sample.plugin", "sample.plugin:home:4:2:1"))
        assertNull(parseCs3HomeSectionId("sample.plugin", "sample.plugin:home:-1:2"))
    }
}
