package audit;

import com.auras.orbit.ui.PageWindow;
import java.util.Random;

/** Exercises credit pagination as a non-truncating window over long TMDB filmographies. */
public final class ActorFilmographyPagingRegression {
    private static long checks;
    private static void check(boolean ok, String msg) {
        checks++;
        if (!ok) throw new AssertionError(msg + " at " + checks);
    }
    private static int visitAll(int count) {
        int visited = 0;
        for (int page = 0;; page++) {
            PageWindow.Window w = PageWindow.of(count, page, 256);
            check(w.start == visited, "gap or repeated credit");
            check(w.end - w.start <= 256, "too many view rows");
            for (int index = w.start; index < w.end; index++) {
                check(index == visited++, "wrong credit order");
            }
            if (!w.hasNext()) break;
        }
        check(visited == count, "filmography was truncated");
        return visited;
    }
    public static void main(String[] args) {
        for (int total : new int[] {0, 1, 200, 201, 256, 257, 512, 521, 3000, 12000})
            visitAll(total);
        PageWindow.Window first = PageWindow.of(521, 0, 256);
        check(first.start <= 200 && first.end > 200, "201st credit absent from first page");
        check(first.end <= 256, "first page overflow");
        PageWindow.Window second = PageWindow.of(521, 1, 256);
        check(second.start == 256 && second.end == 512, "second credit page");
        PageWindow.Window third = PageWindow.of(521, 2, 256);
        check(third.start == 512 && third.end == 521, "521st credit unreachable");
        check(third.hasPrevious() && !third.hasNext(), "last page arrows");
        Random rng = new Random(0xA07C7EDL);
        for (int i = 0; i < 4000; i++) {
            int count = rng.nextInt(3000);
            visitAll(count);
        }
        System.out.println("ACTOR_FILMOGRAPHY_PAGING_PASS checks=" + checks);
    }
}
