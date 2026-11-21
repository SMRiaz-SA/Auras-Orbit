package audit;

import com.auras.orbit.jellyfin.JellyfinPageBounds;
import java.util.Random;

/** Server pagination totals can be missing, negative, stale, or near integer overflow. */
public final class JellyfinServerMetadataRegression {
    public static void main(String[] args) {
        long checks = 0;
        int[] starts = {-1, 0, 1, 100, Integer.MAX_VALUE - 100, Integer.MAX_VALUE};
        int[] counts = {0, 1, 20, 100, 20_000, Integer.MAX_VALUE};
        int[] totals = {-1, -100, 0, 1, 100, Integer.MAX_VALUE};
        for (int start : starts) for (int count : counts) for (int total : totals) {
            int computed = JellyfinPageBounds.effectiveTotal(start, count, total);
            int expected = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(Math.max(0L, start) + (long) count, Math.max(0L, total)));
            if (computed != expected) throw new AssertionError(start + " " + count + " " + total);
            checks++;
        }
        Random random = new Random(9161024L);
        for (int i = 0; i < 100_000; i++) {
            int start = random.nextInt();
            int count = random.nextInt(Integer.MAX_VALUE);
            int total = random.nextInt();
            int result = JellyfinPageBounds.effectiveTotal(start, count, total);
            long floor = Math.max(0L, start) + count;
            if (result < 0 || result < Math.max(0, total)
                    || result != (int)Math.min(Integer.MAX_VALUE, Math.max(floor, Math.max(0L,total)))) {
                throw new AssertionError("Server total overflow or inconsistent page result");
            }
            checks++;
        }
        System.out.println("JellyfinServerMetadataRegression PASS " + checks + " checks");
    }
}
