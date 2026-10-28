package com.lagradost.cloudstream3.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Test

class AurasLazyKeysTest {
    @Test
    fun duplicateProviderIdentitiesReceiveDistinctLazyKeysAcrossPages() {
        val duplicateIdentityGroups = listOf(
            listOf("catalog:42", "catalog:42"),
            listOf("catalog:https://provider.example/title", "catalog:https://provider.example/title"),
        )

        duplicateIdentityGroups.forEach { providerIdentities ->
            val keys = providerIdentities.mapIndexed { index, identity ->
                aurasUniqueLazyKey(identity, index)
            }

            assertEquals(providerIdentities.size, keys.toSet().size)
        }
    }
}
