package audit;

import com.auras.orbit.providers.HomeSection;
import com.auras.orbit.providers.MediaItem;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/** The actual HomeSection class must not trust plugin-controlled subList()/toArray(). */
public final class HomeSectionAdversarialAdmissionRegression {
    private static long checks;
    private static void check(boolean passed, String reason) {
        checks++;
        if (!passed) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        AtomicInteger reads = new AtomicInteger();
        List<MediaItem> lyingSubList = new AbstractList<MediaItem>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public MediaItem get(int index) {
                reads.incrementAndGet();
                return null;
            }
            @Override public List<MediaItem> subList(int from, int to) {
                throw new AssertionError("Unexpected provider-controlled subList() invocation");
            }
        };
        try {
            new HomeSection("s", "Shelf", lyingSubList, "next");
            throw new AssertionError("Oversized Home must fail explicitly");
        } catch (IllegalArgumentException expected) {
            check(reads.get() == 0, "No reads from impossible provider list");
        }
        AtomicInteger sizeReads = new AtomicInteger();
        List<MediaItem> changingSize = new AbstractList<MediaItem>() {
            @Override public int size() { return sizeReads.incrementAndGet() == 1 ? 1 : Integer.MAX_VALUE; }
            @Override public MediaItem get(int index) { return null; }
            @Override public List<MediaItem> subList(int a, int b) {
                throw new AssertionError("Provider subList called after changing size");
            }
        };
        HomeSection second = new HomeSection("s", "Shelf", changingSize, "next");
        check(second.items.size() == 1 && sizeReads.get() == 1, "Unstable size read more than once");
        Random random = new Random(2026110903L);
        for (int cycle = 0; cycle < 2_000; cycle++) {
            int reported = random.nextInt(1025);
            AtomicInteger visits = new AtomicInteger();
            List<MediaItem> custom = new AbstractList<MediaItem>() {
                @Override public int size() { return reported; }
                @Override public MediaItem get(int index) {
                    if (visits.incrementAndGet() > reported) throw new AssertionError("Unbounded Home copy");
                    return null;
                }
                @Override public List<MediaItem> subList(int start, int end) {
                    throw new AssertionError("Custom provider subList() unexpectedly called");
                }
            };
            HomeSection shelf = new HomeSection("id", "title", custom, "cursor");
            check(shelf.items.size() == reported, "Normal Home output was truncated");
            check(visits.get() == reported, "Incorrect number of source accesses");
        }
        try {
            new HomeSection("overflow", "Overflow",
                java.util.Collections.nCopies(HomeSection.MAX_DISPLAY_ITEMS + 1, null), "");
            throw new AssertionError("Home emergency ceiling must fail explicitly");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("HOME_SECTION_ADVERSARIAL_ADMISSION_PASS checks=" + checks);
    }
}
