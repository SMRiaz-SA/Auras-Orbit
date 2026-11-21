package com.auras.orbit.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lagradost.cloudstream3.syncproviders.AccountManager;
import com.lagradost.cloudstream3.syncproviders.AuthRepo;
import com.lagradost.cloudstream3.syncproviders.SubtitleRepo;

import org.junit.Test;

public final class SubtitleProviderRegistryTest {
    @Test public void cloudStreamSubtitleProvidersUseTheProfileAwareAuthRegistry() {
        SubtitleRepo[] providers = AccountManager.Companion.getSubtitleProviders();
        assertEquals(4, providers.length);
        assertEquals("opensubtitles", providers[0].getIdPrefix());
        assertEquals("addic7ed", providers[1].getIdPrefix());
        assertEquals("subdl", providers[2].getIdPrefix());
        assertEquals("subsource", providers[3].getIdPrefix());

        AuthRepo[] authProviders = AccountManager.Companion.getAllApis();
        for (SubtitleRepo provider : providers) {
            boolean registered = false;
            for (AuthRepo authProvider : authProviders) {
                if (provider.getIdPrefix().equals(authProvider.getIdPrefix())) {
                    registered = authProvider == provider;
                    break;
                }
            }
            assertTrue(provider.getIdPrefix() + " must be visible to the existing Connections UI", registered);
        }
    }
}
