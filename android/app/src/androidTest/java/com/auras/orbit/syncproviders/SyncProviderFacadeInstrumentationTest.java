package com.auras.orbit.syncproviders;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.lagradost.cloudstream3.syncproviders.AccountManager;
import com.lagradost.cloudstream3.syncproviders.SyncAPI;
import com.lagradost.cloudstream3.syncproviders.SyncRepo;
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies the Orbit sync-provider ABI used during StreamPlay provider construction; loads no plugin. */
@RunWith(AndroidJUnit4.class)
public final class SyncProviderFacadeInstrumentationTest {
    @Test
    public void aniListFacadeMatchesSyncRepoConstructionContract() {
        AniListApi api = AccountManager.Companion.getAniListApi();

        assertNotNull(api);
        assertSame(api, AccountManager.Companion.getAniListApi());
        assertTrue(SyncAPI.class.isAssignableFrom(AniListApi.class));
        assertNotNull(new SyncRepo(api));
    }
}
