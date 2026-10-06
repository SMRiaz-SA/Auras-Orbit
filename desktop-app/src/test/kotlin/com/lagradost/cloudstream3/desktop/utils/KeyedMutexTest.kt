package com.lagradost.cloudstream3.desktop.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KeyedMutexTest {
    @Test
    fun sameKeyWaitersStaySerializedAndIdleLockCanBeReacquired() = runBlocking {
        val keyedMutex = KeyedMutex<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0

        val first = launch(Dispatchers.Default) {
            keyedMutex.withLock("same") {
                active++
                maximumActive = maxOf(maximumActive, active)
                entered.complete(Unit)
                release.await()
                active--
            }
        }
        entered.await()

        val second = async(Dispatchers.Default) {
            keyedMutex.withLock("same") {
                active++
                maximumActive = maxOf(maximumActive, active)
                active--
            }
        }
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.join()
        second.await()

        keyedMutex.withLock("same") { }
        assertEquals(1, maximumActive)
    }
}
