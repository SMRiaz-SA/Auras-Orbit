package com.lagradost.common.collections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BoundedLruCacheTest {
    @Test
    fun accessPromotesEntryAndInsertionEvictsLeastRecentlyUsed() {
        val cache = BoundedLruCache<String, Int>(2)
        cache["first"] = 1
        cache["second"] = 2

        assertEquals(1, cache["first"])
        cache["third"] = 3

        assertEquals(1, cache["first"])
        assertNull(cache["second"])
        assertEquals(3, cache["third"])
        assertEquals(2, cache.size())
    }

    @Test
    fun rejectsZeroCapacity() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BoundedLruCache<String, Int>(0)
        }
    }
}
