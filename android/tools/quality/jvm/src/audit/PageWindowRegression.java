package audit;

import com.auras.orbit.ui.PageWindow;
import java.util.Random;

/** Failure-first generated page-boundary and huge-queue property tests. */
public final class PageWindowRegression {
    private static long checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message + "; check=" + checks);
    }
    public static void main(String[] args) {
        int rejected = 0;
        for (int[] x : new int[][]{{-1,0,40},{2,1,0},{2,1,501}}) {
            try { PageWindow.of(x[0],x[1],x[2]); }
            catch (IllegalArgumentException expected) { rejected++; }
        }
        check(rejected == 3, "bad page parameters rejected");
        int[] sizes = {1,2,3,10,40,41,100,500};
        int[] totals = {0,1,2,3,10,39,40,41,80,81,200,10000,
            Integer.MAX_VALUE-1,Integer.MAX_VALUE};
        int[] pages = {Integer.MIN_VALUE,-100,-1,0,1,2,3,4,100,500,10000,
            Integer.MAX_VALUE};
        for (int size : sizes) for (int total : totals) for (int requested : pages) {
            PageWindow.Window w = PageWindow.of(total,requested,size);
            long expectedLast = total == 0 ? 0 : ((long)total-1)/size;
            long expectedPage = Math.min(expectedLast,Math.max(0L,requested));
            check(w.total==total,"total conserved");
            check(w.lastPage==expectedLast,"last-page integer arithmetic");
            check(w.page==expectedPage,"page clamping");
            check(w.start==(long)w.page*size,"page starts correctly");
            check(w.end==Math.min((long)total,(long)w.start+size),"page ends correctly");
            check(w.end>=w.start && w.end-w.start<=size,"bounded page count");
            check(w.hasPrevious()==(w.page>0),"previous availability");
            check(w.hasNext()==(w.page<w.lastPage),"next availability");
        }
        Random rnd=new Random(0x97c0a12345L);
        for (int i=0;i<300_000;i++) {
            int total=rnd.nextInt(Integer.MAX_VALUE);
            int size=1+rnd.nextInt(500);
            int requested=rnd.nextInt();
            PageWindow.Window w=PageWindow.of(total,requested,size);
            long last=total==0?0:((long)total-1)/size;
            long p=Math.min(last,Math.max(0L,requested));
            check(w.page==p && w.start==(long)p*size,"randomized clamp and overflow safety");
            check(w.end==Math.min((long)total,(long)w.start+size),"randomized window bounds");
            check(w.end-w.start<=size && w.end<=total,"bounded rendering on large lists");
        }
        for (int n=0;n<10_000;n++) {
            int size=40;
            int page=0;
            int total=n;
            int seen=0;
            do {
                PageWindow.Window w=PageWindow.of(total,page,size);
                check(w.start==seen,"no gaps or overlaps between pages");
                seen+=w.end-w.start;
                if (!w.hasNext())break;
                page++;
            }while(true);
            check(seen==total,"all records available via pagination");
        }
        System.out.println("PageWindowRegression PASS checks="+checks);
    }
}
