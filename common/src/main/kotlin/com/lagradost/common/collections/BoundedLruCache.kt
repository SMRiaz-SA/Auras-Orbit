package com.lagradost.common.collections

import java.util.LinkedHashMap

/** A small thread-safe access-ordered cache with a fixed entry limit. */
class BoundedLruCache<K, V>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, V>(minOf(maxEntries.coerceAtLeast(1), 16), 0.75f, true)

    init {
        require(maxEntries > 0) { "Cache capacity must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = value
        while (entries.size > maxEntries) {
            val eldestKey = entries.keys.iterator().next()
            entries.remove(eldestKey)
        }
    }

    @Synchronized
    fun remove(key: K): V? = entries.remove(key)

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
