package audit;

import com.auras.orbit.metadata.ActorMetadata;
import java.util.*;

/** Fault injection into the real TMDB filmography model's list admission. */
public final class ActorCreditCollectionRegression {
    private static long checks;
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    private static final ActorMetadata.Credit CREDIT =
            new ActorMetadata.Credit("Title", "2026", "Movie", "Lead");
    private static ActorMetadata actor(List<ActorMetadata.Credit> credits) {
        return new ActorMetadata("Actor", "", "", "", "", "", credits);
    }
    private static void inconsistentIterator() {
        List<ActorMetadata.Credit> untrusted = new AbstractList<ActorMetadata.Credit>() {
            @Override public int size() { return 1; }
            @Override public ActorMetadata.Credit get(int index) {
                if (index != 0) throw new IndexOutOfBoundsException();
                return CREDIT;
            }
            @Override public Iterator<ActorMetadata.Credit> iterator() {
                return new Iterator<ActorMetadata.Credit>() {
                    int reads;
                    @Override public boolean hasNext() { return true; }
                    @Override public ActorMetadata.Credit next() {
                        if (++reads > 10_000) throw new IllegalStateException("unbounded credit iterator");
                        return CREDIT;
                    }
                };
            }
        };
        check(actor(untrusted).credits.size() == 1, "inconsistent iterator is not consumed");
    }
    private static void countLimit() {
        List<ActorMetadata.Credit> changingSize = new AbstractList<ActorMetadata.Credit>() {
            int sizeReads;
            @Override public int size() { return ++sizeReads <= 2 ? 1 : Integer.MAX_VALUE; }
            @Override public ActorMetadata.Credit get(int index) { return CREDIT; }
        };
        check(actor(changingSize).credits.size() == 1,
                "credit count is captured once before allocation");
        List<ActorMetadata.Credit> oversized = new AbstractList<ActorMetadata.Credit>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public ActorMetadata.Credit get(int index) { return CREDIT; }
        };
        checks++;
        try { actor(oversized); throw new AssertionError("huge reported size accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void fuzz() {
        Random rng = new Random(0x9a17L);
        for (int iteration = 0; iteration < 100_000; iteration++) {
            int count = rng.nextInt(200);
            ArrayList<ActorMetadata.Credit> credits = new ArrayList<>();
            int expected = 0;
            for (int i = 0; i < count; i++) {
                ActorMetadata.Credit c = rng.nextInt(13) == 0 ? null : CREDIT;
                credits.add(c);
                if (c != null) expected++;
            }
            check(actor(credits).credits.size() == expected, "filmography retained without nulls");
        }
    }
    public static void main(String[] args) {
        inconsistentIterator();
        countLimit();
        fuzz();
        System.out.println("ACTOR_CREDIT_COLLECTION checks=" + checks + " failures=0");
    }
}
