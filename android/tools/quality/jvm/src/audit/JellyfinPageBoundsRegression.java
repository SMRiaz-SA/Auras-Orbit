package audit;

import com.auras.orbit.jellyfin.JellyfinPageBounds;
import java.util.Random;

public final class JellyfinPageBoundsRegression {
    public static void main(String[] args) {
        long count = 0;
        if (JellyfinPageBounds.fallbackTotal(Integer.MAX_VALUE, 1) != Integer.MAX_VALUE) {
            throw new AssertionError("overflowed last page");
        }
        count++;
        if (JellyfinPageBounds.fallbackTotal(-100, 10) != 10) {
            throw new AssertionError("negative start was not clamped");
        }
        count++;
        Random random = new Random(20261108L);
        for (int i=0; i<500_000; i++) {
            int start = random.nextInt();
            int items = random.nextInt(101);
            int expected = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, (long) start) + items);
            if (JellyfinPageBounds.fallbackTotal(start, items) != expected) {
                throw new AssertionError("incorrect page bound for start="+start+" count="+items);
            }
            count++;
        }
        boolean rejected = false;
        try { JellyfinPageBounds.fallbackTotal(0, -1); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("accepted negative item count");
        count++;
        System.out.println("JELLYFIN_PAGE_BOUNDS_PASS checks="+count);
    }
}
