package com.auras.orbit.providers;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for playable provider callbacks arriving before link resolution returns. */
public final class ProviderCoordinatorProgressiveLinksTest {
    @Test public void publishesPartialCandidatesAndRetainsThemWhenResolutionTimesOut()
            throws Exception {
        ProviderOperationCoordinator coordinator = new ProviderOperationCoordinator(1);
        CountDownLatch partialPublished = new CountDownLatch(1);
        CountDownLatch timedOut = new CountDownLatch(1);
        CountDownLatch allowProviderReturn = new CountDownLatch(1);
        AtomicBoolean providerWasStillLoadingAtPartial = new AtomicBoolean();
        ProviderDescriptor descriptor = new ProviderDescriptor("progressive-test",
                "Progressive Test", false, false, false, false, true, true, false);
        ProviderLinkResult partial = result("partial");
        ProviderLinkResult completed = result("complete");
        MediaProvider provider = (MediaProvider) Proxy.newProxyInstance(
                MediaProvider.class.getClassLoader(), new Class<?>[]{MediaProvider.class},
                (proxy, method, args) -> {
                    if ("descriptor".equals(method.getName())) return descriptor;
                    if ("resolveLinks".equals(method.getName())) {
                        if (args != null && args.length == 3) {
                            ((MediaProvider.LinkProgressListener) args[2]).onProgress(partial);
                        }
                        boolean interrupted = false;
                        while (allowProviderReturn.getCount() > 0) {
                            try {
                                allowProviderReturn.await();
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                        return completed;
                    }
                    if ("toString".equals(method.getName())) return "Progressive Test Provider";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
        ProviderBatch<ProviderLinkResult> batch = null;
        try {
            batch = coordinator.loadLinksProgressively(provider, "item-1", false, 500L, outcomes -> {
                if (outcomes == null || outcomes.isEmpty()) return;
                ProviderOutcome<ProviderLinkResult> outcome = outcomes.get(0);
                if (outcome.status == ProviderOutcome.Status.LOADING && outcome.value != null
                        && outcome.value.links.size() == 1) {
                    providerWasStillLoadingAtPartial.set(true);
                    partialPublished.countDown();
                }
                if (outcome.status == ProviderOutcome.Status.TIMEOUT) timedOut.countDown();
            });

            assertTrue("a link callback must be visible before resolveLinks returns",
                    partialPublished.await(2, TimeUnit.SECONDS));
            assertTrue(providerWasStillLoadingAtPartial.get());
            assertFalse("partial progress must not complete the provider batch", batch.isComplete());
            assertTrue("the provider timeout should remain distinct from a link callback",
                    timedOut.await(2, TimeUnit.SECONDS));
            List<ProviderOutcome<ProviderLinkResult>> snapshot = batch.snapshot();
            assertEquals(ProviderOutcome.Status.TIMEOUT, snapshot.get(0).status);
            assertEquals("partial", snapshot.get(0).value.links.get(0).id);
        } finally {
            allowProviderReturn.countDown();
            if (batch != null) batch.close();
            coordinator.close();
        }
    }

    private static ProviderLinkResult result(String id) {
        ProviderLinkCandidate candidate = new ProviderLinkCandidate(id, id, "Test provider",
                "https://video.example/" + id + ".m3u8", "", 720, "M3U8", "",
                Collections.emptyMap());
        return new ProviderLinkResult(false, Collections.singletonList(candidate),
                Collections.emptyList(), "");
    }
}
