package com.auras.orbit.extensions;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.profile.LocalProfileStore;
import com.lagradost.cloudstream3.plugins.RepositoryManager;
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the CS3 RepositoryManager API without activating or loading a plugin package. */
@RunWith(AndroidJUnit4.class)
public final class RepositoryManagerPluginPersistenceInstrumentationTest {
    private static final long CONTINUATION_TIMEOUT_SECONDS = 10L;

    private OrbitApplication application;
    private CloudStreamExtensionManager manager;
    private final List<String> fixtureUrls = new ArrayList<>();
    private final List<Completion> completions = new ArrayList<>();

    @Before
    public void attachToOrbitRepositoryStore() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        application = (OrbitApplication) context.getApplicationContext();
        manager = application.getCloudStreamExtensionManager();
        assertTrue("repository metadata storage must be ready", manager.isStorageRecoveryReady());
    }

    @After
    public void removeOnlyRepositoryFixtures() throws InterruptedException {
        if (manager == null) return;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CONTINUATION_TIMEOUT_SECONDS);
        for (Completion completion : completions) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) completion.await(remaining, TimeUnit.MILLISECONDS);
        }
        for (String url : fixtureUrls) {
            if (manager.findRepository(url) != null) {
                assertTrue("remove repository fixture " + url, manager.removeRepository(url));
            }
        }
        fixtureUrls.clear();
    }

    @Test
    public void pluginRequestsPersistCanonicalMetadataWithoutConsentOrCatalogFetch() throws Exception {
        String fixtureId = UUID.randomUUID().toString();
        String firstRequestUrl = "HTTPS://CS3-PLUGIN-ONE.invalid:443/" + fixtureId + "/catalog///";
        String firstCanonicalUrl = "https://cs3-plugin-one.invalid/" + fixtureId + "/catalog";
        String secondRequestUrl = "http://CS3-PLUGIN-TWO.invalid:80/" + fixtureId + "/repo/";
        String secondCanonicalUrl = "http://cs3-plugin-two.invalid/" + fixtureId + "/repo";
        assertNull("first repository fixture URL must not exist before this test", manager.findRepository(firstCanonicalUrl));
        assertNull("second repository fixture URL must not exist before this test", manager.findRepository(secondCanonicalUrl));

        List<String> installedBefore = installedPluginIds();
        Map<String, Set<String>> enabledPluginGrantsBefore = enabledPluginIdsBySavedProfile();
        RepositoryManager api = RepositoryManager.INSTANCE;
        Completion first = new Completion();
        Completion second = new Completion();
        Completion canonicalDuplicate = new Completion();

        Object firstCall = addRepositoryWithoutSynchronousConsent(api, new RepositoryData(
                "https://icons.invalid/" + fixtureId + "/one.png", "Plugin repository one",
                firstRequestUrl, "Metadata from plugin one"), first);
        assertSame("the suspend API returns while storage work runs on Orbit's extension executor",
                IntrinsicsKt.getCOROUTINE_SUSPENDED(), firstCall);
        fixtureUrls.add(firstCanonicalUrl);
        completions.add(first);

        Object secondCall = addRepositoryWithoutSynchronousConsent(api, new RepositoryData(
                "https://icons.invalid/" + fixtureId + "/two.png", "Plugin repository two",
                secondRequestUrl, "Metadata from plugin two"), second);
        assertSame("each call suspends independently while its metadata is saved",
                IntrinsicsKt.getCOROUTINE_SUSPENDED(), secondCall);
        fixtureUrls.add(secondCanonicalUrl);
        completions.add(second);

        Object duplicateCall = addRepositoryWithoutSynchronousConsent(api, new RepositoryData(
                "https://icons.invalid/" + fixtureId + "/replacement.png", "Replacement metadata",
                firstCanonicalUrl + "/", "This duplicate must not replace existing metadata"), canonicalDuplicate);
        assertSame("canonical duplicates still complete through the plugin API",
                IntrinsicsKt.getCOROUTINE_SUSPENDED(), duplicateCall);
        completions.add(canonicalDuplicate);
        assertTrue("the first request resumes without waiting for a consent choice",
                first.await(CONTINUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("the second request resumes without waiting for a consent choice",
                second.await(CONTINUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("the canonical duplicate resumes without waiting for a consent choice",
                canonicalDuplicate.await(CONTINUATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertSame("repository persistence resumes the first coroutine with Unit", Unit.INSTANCE, first.result());
        assertSame("repository persistence resumes the second coroutine with Unit", Unit.INSTANCE, second.result());
        assertSame("a canonical duplicate completes successfully", Unit.INSTANCE, canonicalDuplicate.result());

        Map<String, RepositoryData> saved = new HashMap<>();
        int savedFixtureCount = 0;
        for (RepositoryData repository : api.getRepositories()) {
            if (repository.getUrl().contains(fixtureId)) savedFixtureCount++;
            saved.put(repository.getUrl(), repository);
        }
        assertEquals("canonical URL aliases must not create duplicate repository records", 2, savedFixtureCount);
        RepositoryData firstSaved = saved.get(firstCanonicalUrl);
        RepositoryData secondSaved = saved.get(secondCanonicalUrl);
        assertNotNull("first plugin repository is persisted once under its canonical URL", firstSaved);
        assertNotNull("second plugin repository is persisted once under its canonical URL", secondSaved);
        assertEquals("Plugin repository one", firstSaved.getName());
        assertEquals("https://icons.invalid/" + fixtureId + "/one.png", firstSaved.getIconUrl());
        assertEquals("Metadata from plugin one", firstSaved.getDescription());
        assertTrue("a duplicate must preserve the original repository metadata",
                !"Replacement metadata".equals(firstSaved.getName()));
        assertEquals("Plugin repository two", secondSaved.getName());
        assertEquals("https://icons.invalid/" + fixtureId + "/two.png", secondSaved.getIconUrl());
        assertEquals("Metadata from plugin two", secondSaved.getDescription());

        assertTrue("saving metadata must not fetch the first catalog", manager.getCatalog(firstCanonicalUrl).isEmpty());
        assertTrue("saving metadata must not fetch the second catalog", manager.getCatalog(secondCanonicalUrl).isEmpty());
        assertEquals("repository metadata requests must not install or enable plugins",
                installedBefore, installedPluginIds());
        assertEquals("repository metadata requests must not change plugin enable grants for any saved profile",
                enabledPluginGrantsBefore, enabledPluginIdsBySavedProfile());
    }

    private static Object addRepositoryWithoutSynchronousConsent(
            RepositoryManager api, RepositoryData repository, Completion completion) {
        try {
            return api.addRepository(repository, completion);
        } catch (IllegalStateException consentRequired) {
            return consentRequired;
        }
    }

    private List<String> installedPluginIds() {
        List<String> result = new ArrayList<>();
        for (CloudStreamExtensionManager.InstalledPlugin plugin : manager.getInstalledPlugins()) {
            result.add(plugin.id);
        }
        return result;
    }

    private Map<String, Set<String>> enabledPluginIdsBySavedProfile() {
        Map<String, Set<String>> result = new HashMap<>();
        for (LocalProfileStore.Profile profile : application.getLocalProfileStore().getProfiles()) {
            result.put(profile.id, new HashSet<>(manager.getEnabledPluginIds(profile.id)));
        }
        return result;
    }

    private static final class Completion implements Continuation<Object> {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<Object> value = new AtomicReference<>();

        @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }

        @Override public void resumeWith(Object result) {
            value.set(result);
            completed.countDown();
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completed.await(timeout, unit);
        }

        Object result() { return value.get(); }
    }
}
