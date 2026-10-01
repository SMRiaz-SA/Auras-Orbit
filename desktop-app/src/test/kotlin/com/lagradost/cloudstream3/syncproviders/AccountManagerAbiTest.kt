package com.lagradost.cloudstream3.syncproviders

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountManagerAbiTest {
    @Test
    fun `AniList getter matches the Android extension JVM signature`() {
        val companionClass = Class.forName(
            "com.lagradost.cloudstream3.syncproviders.AccountManager\$Companion",
            false,
            AccountManager::class.java.classLoader,
        )

        val getter = companionClass.getMethod("getAniListApi")

        assertEquals(
            "com.lagradost.cloudstream3.syncproviders.providers.AniListApi",
            getter.returnType.name,
        )
        assertTrue(SyncAPI::class.java.isAssignableFrom(getter.returnType))
    }
}
