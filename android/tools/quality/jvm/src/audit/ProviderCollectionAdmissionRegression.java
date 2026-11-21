package audit;

import com.auras.orbit.providers.*;
import java.util.*;

/** Exercises real immutable provider-admission boundaries with inconsistent List implementations. */
public final class ProviderCollectionAdmissionRegression {
    private static long checks;
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    private static void reject(Runnable action, String name) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        catch (OutOfMemoryError error) {
            throw new AssertionError(name + " allocated an attacker-provided list size", error);
        }
        throw new AssertionError(name + " accepted oversized collection");
    }
    private static final MediaProvider PROVIDER = new MediaProvider() {
        final ProviderDescriptor descriptor = new ProviderDescriptor("admission", "Admission", false, true, false, false, false);
        @Override public ProviderDescriptor descriptor() { return descriptor; }
        @Override public List<MediaItem> search(SearchRequest q) { return Collections.emptyList(); }
    };
    private static final ProviderLinkCandidate.AudioTrackCandidate TRACK =
            new ProviderLinkCandidate.AudioTrackCandidate("eng", "https://example.com/a.m4a", Collections.emptyMap());
    private static List<MediaProvider> immenseProviderList() {
        return new AbstractList<MediaProvider>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public MediaProvider get(int index) { return PROVIDER; }
        };
    }
    private static <T> List<T> misleadingIterator(T item) {
        return new AbstractList<T>() {
            @Override public int size() { return 1; }
            @Override public T get(int index) { if (index != 0) throw new IndexOutOfBoundsException(); return item; }
            @Override public Iterator<T> iterator() {
                return new Iterator<T>() {
                    int reads;
                    @Override public boolean hasNext() { return true; }
                    @Override public T next() {
                        if (++reads > 10_000) throw new IllegalStateException("nonterminating iterator");
                        return item;
                    }
                };
            }
        };
    }
    private static ProviderLinkCandidate candidate(List<ProviderLinkCandidate.AudioTrackCandidate> tracks) {
        return new ProviderLinkCandidate("id", "name", "source", "https://example.com/movie.mp4",
                "", 1080, "direct", "", Collections.emptyMap(), tracks);
    }
    private static void providerOrder() {
        try (ProviderOperationCoordinator coordinator = new ProviderOperationCoordinator(1)) {
            reject(() -> coordinator.search(immenseProviderList(),
                    new SearchRequest("q", SearchType.ALL, 1), 2000, null), "provider size");
            ProviderBatch<?> ordinary = coordinator.search(misleadingIterator(PROVIDER),
                    new SearchRequest("q", SearchType.ALL, 1), 2000, null);
            check(ordinary.snapshot().size() == 1, "provider copy ignores pathological iterator");
            ordinary.close();
        }
    }
    private static void audioTrackList() {
        List<ProviderLinkCandidate.AudioTrackCandidate> changingSize =
                new AbstractList<ProviderLinkCandidate.AudioTrackCandidate>() {
                    int sizeReads;
                    @Override public int size() { return ++sizeReads <= 2 ? 1 : Integer.MAX_VALUE; }
                    @Override public ProviderLinkCandidate.AudioTrackCandidate get(int index) { return TRACK; }
                };
        check(candidate(changingSize).audioTracks.size() == 1,
                "audio count is captured once before allocation");
        check(candidate(misleadingIterator(TRACK)).audioTracks.size() == 1,
                "audio track snapshot ignores pathological iterator");
        for (int count = 0; count <= ProviderLinkCandidate.MAX_AUDIO_TRACKS; count++) {
            ArrayList<ProviderLinkCandidate.AudioTrackCandidate> normal = new ArrayList<>();
            for (int i=0; i<count; i++) normal.add(TRACK);
            check(candidate(normal).audioTracks.size() == count, "normal tracks retained");
        }
        ArrayList<ProviderLinkCandidate.AudioTrackCandidate> tooMany = new ArrayList<>();
        for (int i=0; i<=ProviderLinkCandidate.MAX_AUDIO_TRACKS; i++) tooMany.add(TRACK);
        reject(() -> candidate(tooMany), "audio track limit");
    }
    /** Deterministic mutation-like fuzz of the actual private admission boundary. */
    @SuppressWarnings("unchecked")
    private static void orderFuzz() throws Exception {
        java.lang.reflect.Method copy = ProviderOperationCoordinator.class
                .getDeclaredMethod("copyProviderOrder", List.class);
        copy.setAccessible(true);
        Random random = new Random(0xC0FFEE);
        for (int iteration = 0; iteration < 100_000; iteration++) {
            int count = random.nextInt(ProviderOperationCoordinator.MAX_PROVIDERS_PER_BATCH + 65);
            List<MediaProvider> input = Collections.nCopies(count, PROVIDER);
            try {
                List<MediaProvider> output = (List<MediaProvider>) copy.invoke(null, input);
                check(count <= ProviderOperationCoordinator.MAX_PROVIDERS_PER_BATCH
                        && output.size() == count, "provider count preserved after admission");
            } catch (java.lang.reflect.InvocationTargetException failure) {
                check(count > ProviderOperationCoordinator.MAX_PROVIDERS_PER_BATCH
                        && failure.getCause() instanceof IllegalArgumentException,
                        "oversized provider order rejected predictably");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        orderFuzz();
        providerOrder();
        audioTrackList();
        System.out.println("PROVIDER_COLLECTION_ADMISSION checks=" + checks + " failures=0");
    }
}
