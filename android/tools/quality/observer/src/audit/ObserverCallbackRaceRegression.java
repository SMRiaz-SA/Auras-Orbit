package audit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import com.auras.orbit.jellyfin.*;
import com.auras.orbit.jellyfin.JellyfinModels.PlaybackEvent;
import com.auras.orbit.player.OrbitPlayerActivity.*;
public final class ObserverCallbackRaceRegression {
    private static long checks;
    private static final PlaybackSnapshot POSITION = new PlaybackSnapshot(2000L, true);
    private static ExecutorService executor(JellyfinPlaybackObserver observer) throws Exception {
        Field field = JellyfinPlaybackObserver.class.getDeclaredField("reporter");
        field.setAccessible(true); return (ExecutorService) field.get(observer);
    }
    private static void markProgressQueued(JellyfinPlaybackObserver observer) throws Exception {
        Field field = JellyfinPlaybackObserver.class.getDeclaredField("progressQueued");
        field.setAccessible(true); ((AtomicBoolean) field.get(observer)).set(true);
    }
    private static Object pendingProgress(JellyfinPlaybackObserver observer) throws Exception {
        Field field = JellyfinPlaybackObserver.class.getDeclaredField("latestProgress");
        field.setAccessible(true); return ((AtomicReference<?>) field.get(observer)).get();
    }
    private static void publishProgress(JellyfinPlaybackObserver observer, PlaybackEvent event)
            throws Exception {
        Method method = JellyfinPlaybackObserver.class.getDeclaredMethod("publishProgress", PlaybackEvent.class);
        method.setAccessible(true); method.invoke(observer, event);
    }
    private static void check(boolean good, String message) {
        checks++; if(!good) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        // A completion can race with any callback already admitted by the player.
        for (int i=0; i<1000; i++) {
            JellyfinClient client = new JellyfinClient();
            JellyfinPlaybackObserver observer = ObserverProbeFactory.create(client);
            executor(observer).shutdownNow();
            observer.onPlaybackProgress(POSITION); // must never throw on shutdown
            observer.onPlaybackStarted(POSITION);
            observer.onPlaybackStopped(POSITION, StopReason.USER_EXIT);
            check(true,"late callback did not escape");
        }
        JellyfinPlaybackObserver terminalObserver = ObserverProbeFactory.create(new JellyfinClient());
        executor(terminalObserver).shutdownNow();
        markProgressQueued(terminalObserver);
        PlaybackEvent pendingBeforeStop =
                new PlaybackEvent("item", "source", "session", 0L, true, false, false, null, null);
        publishProgress(terminalObserver, pendingBeforeStop);
        check(pendingProgress(terminalObserver) == pendingBeforeStop,
                "progress fixture did not establish a pending event before stop");
        terminalObserver.onPlaybackStopped(POSITION, StopReason.USER_EXIT);
        check(pendingProgress(terminalObserver) == null,
                "stop did not clear a previously pending progress event");
        publishProgress(terminalObserver,
                new PlaybackEvent("item", "source", "session", 0L, true, false, false, null, null));
        check(pendingProgress(terminalObserver) == null,
                "post-stop progress publication retained a pending event");

        for (int i=0;i<5000;i++) {
            JellyfinClient client = new JellyfinClient();
            JellyfinPlaybackObserver observer = ObserverProbeFactory.create(client);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<Throwable> producerFailure = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    gate.await();
                    for (int n=0;n<12;n++) {
                        observer.onPlaybackStarted(POSITION);
                        observer.onPlaybackProgress(POSITION);
                    }
                } catch (Throwable error) {
                    producerFailure.compareAndSet(null, error);
                }
            });
            producer.setDaemon(true);
            producer.start(); gate.countDown();
            observer.onPlaybackStopped(POSITION, StopReason.USER_EXIT);
            producer.join(1500L);
            check(!producer.isAlive(),"callback worker hangs");
            check(producerFailure.get() == null,
                    "callback producer failed: " + producerFailure.get());
            check(client.stopped.get() <= 1,"duplicate stop events");
            executor(observer).shutdownNow();
        }
        System.out.println("JELLYFIN_OBSERVER_CALLBACK_RACE_PASS checks="+checks);
    }
}
