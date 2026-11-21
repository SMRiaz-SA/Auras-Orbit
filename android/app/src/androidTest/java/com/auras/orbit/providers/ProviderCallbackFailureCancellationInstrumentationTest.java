package com.auras.orbit.providers;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises provider errors and cancellation using in-process MediaProvider implementations only. */
@RunWith(AndroidJUnit4.class)
public final class ProviderCallbackFailureCancellationInstrumentationTest {
    private static final long WAIT_MS = 5_000L;
    private static final String PROVIDER_ID = "fixture-provider-callback-lifecycle";
    private ProviderOperationCoordinator coordinator;

    @After
    public void closeCoordinator() {
        if (coordinator != null) coordinator.close();
    }

    @Test
    public void providerExceptionBecomesFixedSanitizedOutcome() throws Exception {
        coordinator = new ProviderOperationCoordinator(1);
        String privateDiagnostic = "fixture-secret=token-91 https://private.invalid/path";
        FakeMediaProvider provider = new FakeMediaProvider(request -> {
            throw new IllegalStateException(privateDiagnostic);
        });
        OutcomeCapture capture = new OutcomeCapture();

        ProviderBatch<List<AttributedSearchHit>> batch = search(provider, capture.listener());

        assertTrue("the provider failure must become terminal", capture.terminal.await(WAIT_MS,
                TimeUnit.MILLISECONDS));
        ProviderOutcome<List<AttributedSearchHit>> outcome = capture.latest.get();
        assertNotNull("the failure outcome must be observable", outcome);
        assertEquals(ProviderOutcome.Status.FAILURE, outcome.status);
        assertEquals("Provider request failed", outcome.message);
        assertFalse("provider exception content must not enter the user diagnostic",
                outcome.message.contains(privateDiagnostic));
        assertNull("failed provider calls must not retain a result", outcome.value);
        batch.close();
    }

    @Test
    public void cancellationInterruptsCooperativeProviderAndRemainsCancelled() throws Exception {
        coordinator = new ProviderOperationCoordinator(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseIfCancellationFails = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        CountDownLatch providerExited = new CountDownLatch(1);
        FakeMediaProvider provider = new FakeMediaProvider(request -> {
            started.countDown();
            try {
                releaseIfCancellationFails.await();
                return Collections.emptyList();
            } catch (InterruptedException interrupted) {
                interruptionObserved.countDown();
                throw interrupted;
            } finally {
                providerExited.countDown();
            }
        });
        OutcomeCapture capture = new OutcomeCapture();

        try {
            ProviderBatch<List<AttributedSearchHit>> batch = search(provider, capture.listener());
            assertTrue("the fake provider must enter its callback before cancellation",
                    started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue("the active provider call must be canceled",
                    batch.cancel(provider.descriptor().id));
            assertTrue("cancellation must publish a terminal outcome", capture.terminal.await(WAIT_MS,
                    TimeUnit.MILLISECONDS));
            assertTrue("cancellation must interrupt the cooperative provider callback",
                    interruptionObserved.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue("the cooperative callback must exit", providerExited.await(WAIT_MS,
                    TimeUnit.MILLISECONDS));
            assertTrue("the coordinator must observe the callback exit",
                    coordinator.awaitProviderCallsQuiesced(WAIT_MS));

            ProviderOutcome<List<AttributedSearchHit>> outcome = capture.latest.get();
            assertNotNull("the canceled outcome must be observable", outcome);
            assertEquals(ProviderOutcome.Status.CANCELLED, outcome.status);
            assertEquals("Cancelled", outcome.message);
            assertNull("canceled provider calls must not retain a result", outcome.value);
        } finally {
            releaseIfCancellationFails.countDown();
        }
    }

    @Test
    public void lateProviderResultCannotReplaceCanceledOutcome() throws Exception {
        coordinator = new ProviderOperationCoordinator(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseLateResult = new CountDownLatch(1);
        CountDownLatch interruptionObserved = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        MediaItem lateItem = new MediaItem("late-result", "Late fixture result", MediaKind.MOVIE,
                "fixture://late-result", "", "", "");
        FakeMediaProvider provider = new FakeMediaProvider(request -> {
            started.countDown();
            try {
                releaseLateResult.await();
            } catch (InterruptedException canceled) {
                interruptionObserved.countDown();
                // Model a provider that ignores interruption and returns its result later.
                releaseLateResult.await();
            } finally {
                providerReturned.countDown();
            }
            return Collections.singletonList(lateItem);
        });
        OutcomeCapture capture = new OutcomeCapture();

        try {
            ProviderBatch<List<AttributedSearchHit>> batch = search(provider, capture.listener());
            assertTrue("the fake provider must enter its callback before cancellation",
                    started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue("the active provider call must be canceled",
                    batch.cancel(provider.descriptor().id));
            assertTrue("cancellation must publish a terminal outcome", capture.terminal.await(WAIT_MS,
                    TimeUnit.MILLISECONDS));
            assertTrue("the uncooperative provider must observe the interrupt",
                    interruptionObserved.await(WAIT_MS, TimeUnit.MILLISECONDS));

            ProviderOutcome<List<AttributedSearchHit>> canceled = capture.latest.get();
            assertNotNull("the canceled outcome must be observable", canceled);
            assertEquals(ProviderOutcome.Status.CANCELLED, canceled.status);
            assertNull("no result is present before the provider is released", canceled.value);

            releaseLateResult.countDown();
            assertTrue("the fake provider must return its late result", providerReturned.await(WAIT_MS,
                    TimeUnit.MILLISECONDS));
            assertTrue("the coordinator must observe completion of the late provider call",
                    coordinator.awaitProviderCallsQuiesced(WAIT_MS));

            ProviderOutcome<List<AttributedSearchHit>> afterLateReturn = batch.snapshot().get(0);
            assertEquals("late return must not replace cancellation", ProviderOutcome.Status.CANCELLED,
                    afterLateReturn.status);
            assertEquals("cancellation must remain the terminal diagnostic", "Cancelled",
                    afterLateReturn.message);
            assertNull("a late provider result must not enter the canceled outcome", afterLateReturn.value);
            assertEquals("the canceled operation must not publish a late success", 0,
                    capture.successNotifications.get());
        } finally {
            releaseLateResult.countDown();
        }
    }

    private ProviderBatch<List<AttributedSearchHit>> search(MediaProvider provider,
            ProviderBatch.Listener<List<AttributedSearchHit>> listener) {
        return coordinator.search(Collections.singletonList(provider),
                new SearchRequest("fixture query", SearchType.ALL, 1), 30_000L, listener);
    }

    private interface SearchCall {
        List<MediaItem> run(SearchRequest request) throws Exception;
    }

    private static final class FakeMediaProvider implements MediaProvider {
        private final ProviderDescriptor descriptor = new ProviderDescriptor(PROVIDER_ID,
                "Synthetic provider", false, true, false, false, false, false, false);
        private final SearchCall callback;

        FakeMediaProvider(SearchCall callback) { this.callback = callback; }

        @Override public ProviderDescriptor descriptor() { return descriptor; }
        @Override public List<MediaItem> search(SearchRequest request) throws Exception {
            return callback.run(request);
        }
    }

    private static final class OutcomeCapture {
        final AtomicReference<ProviderOutcome<List<AttributedSearchHit>>> latest = new AtomicReference<>();
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicInteger successNotifications = new AtomicInteger();

        ProviderBatch.Listener<List<AttributedSearchHit>> listener() {
            return outcomes -> {
                if (outcomes.isEmpty()) return;
                ProviderOutcome<List<AttributedSearchHit>> outcome = outcomes.get(0);
                latest.set(outcome);
                if (outcome.status == ProviderOutcome.Status.SUCCESS) successNotifications.incrementAndGet();
                if (outcome.isTerminal()) terminal.countDown();
            };
        }
    }
}
