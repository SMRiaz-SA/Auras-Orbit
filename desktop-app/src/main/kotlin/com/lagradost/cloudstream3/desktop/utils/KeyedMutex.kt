package com.lagradost.cloudstream3.desktop.utils

import kotlinx.coroutines.sync.Mutex

/** A per-key mutex registry that releases idle locks without splitting active waiters. */
class KeyedMutex<K> {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = HashMap<K, Entry>()

    suspend fun <T> withLock(key: K, action: suspend () -> T): T {
        val entry = synchronized(entries) {
            val current = entries[key] ?: Entry().also { entries[key] = it }
            current.users++
            current
        }

        var locked = false
        try {
            entry.mutex.lock()
            locked = true
            return action()
        } finally {
            if (locked) entry.mutex.unlock()
            synchronized(entries) {
                entry.users--
                if (entry.users == 0 && entries[key] === entry) {
                    entries.remove(key)
                }
            }
        }
    }
}
