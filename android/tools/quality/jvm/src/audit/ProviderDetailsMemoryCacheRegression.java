package audit;

import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderDescriptor;
import com.auras.orbit.providers.ProviderDetailsMemoryCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Real production cache under deterministic boundary/fault-injection tests. */
public final class ProviderDetailsMemoryCacheRegression {
    private static long assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Provider implements MediaProvider {
        final ProviderDescriptor d = new ProviderDescriptor("cs3", "CS3", true, true, true, true, false);
        @Override public ProviderDescriptor descriptor() { return d; }
    }
    private static MediaItem title(String id, String payload) {
        return new MediaItem(id, "An Orbit Movie", MediaKind.MOVIE, "https://example.test/"+id,
                "", "2025", "Descriptive text", payload);
    }
    private static MediaItem series(String id, int episodeCount, int dataLength) {
        List<MediaItem.EpisodeItem> episodes = new ArrayList<>();
        String data = "x".repeat(dataLength);
        for(int n = 0; n < episodeCount; n++) {
            episodes.add(new MediaItem.EpisodeItem("ep-"+n, "Episode " + n, 1, n,
                    "", "", 3600, "", 0L, "", data));
        }
        return new MediaItem(id, "Orbit Series", MediaKind.SERIES, "https://example.test/"+id,
                "", "2025", "Overview", "", Collections.emptyList(), 0, "", "", "",
                Collections.emptyMap(), "", Collections.emptyList(), Collections.emptyList(), episodes);
    }
    private static void basics() {
        Provider a = new Provider(), replaced = new Provider();
        ProviderDetailsMemoryCache cache = new ProviderDetailsMemoryCache();
        MediaItem seed = title("title", ""), full = title("title", "opaque-payload");
        check(cache.get(a, "p1", seed, 0)==null, "empty must miss");
        check(cache.put(a, "p1", seed, full, 100L), "valid put");
        check(cache.get(a, "p1", seed, 110L)==full, "warm hit");
        check(cache.get(a, "p2", seed, 110L)==null, "profile isolation");
        check(cache.get(replaced, "p1", seed, 110L)==null, "adapter generation isolation");
        check(cache.get(a, "p1", title("other", ""), 110L)==null, "title isolation");
        check(cache.get(a, "p1", title("title", "x"), 110L)==full, "seed payload irrelevant");
        check(!cache.put(a, "p1", seed, title("other", ""), 110L), "mismatched result rejected");
        check(cache.size()==0, "malformed new response invalidates old cached details");
        check(cache.put(a, "p1", seed, full, 110L), "fresh restored after rejection");
        check(cache.get(a, "p1", seed, 99L)==null, "clock rollback misses");
        check(cache.size()==0, "clock rollback evicts");
        check(cache.put(a, "p1", seed, full, 1000L), "put after clock rollback");
        check(cache.get(a, "p1", seed, 1000L+ProviderDetailsMemoryCache.MAX_AGE_MS-1)==full,
                "fresh at age boundary minus one");
        check(cache.get(a, "p1", seed, 1000L+ProviderDetailsMemoryCache.MAX_AGE_MS)==null,
                "TTL boundary evicts");
        check(cache.estimatedChars()==0, "charge removed with eviction");
        check(!cache.put(a, "p1", seed, null, 0L), "null details rejected");
        check(!cache.put(a, "", seed, full, 0L), "invalid profile rejected");
        check(!cache.put(null, "p1", seed, full, 0L), "invalid provider rejected");
        check(!cache.put(a, "p1", null, full, 0L), "invalid seed rejected");
        check(!cache.put(a, "p1", seed, series("title",1,1), 0L), "wrong media kind rejected");
        check(cache.size()==0, "rejected remains empty");
    }
    private static void boundsAndState() {
        ProviderDetailsMemoryCache cache = new ProviderDetailsMemoryCache();
        Provider provider = new Provider();
        MediaItem seed = series("series", 0, 0);
        MediaItem huge = series("series", 10, 60_000);
        check(!cache.put(provider, "p1", seed, huge, 1L), "over-budget episodes rejected");
        check(cache.size()==0, "huge no retention");
        MediaItem small = series("series", 3, 10);
        check(cache.put(provider, "p1", seed, small, 1L), "normal episodes admitted");
        ProviderDetailsMemoryCache.ViewState state =
                new ProviderDetailsMemoryCache.ViewState("ep-2", 1, 500, "provider_episode_2");
        cache.rememberViewState(provider, "p1", seed, state);
        check(cache.viewState(provider, "p1", seed, 2L)==state, "semantic state retained");
        check(cache.viewState(provider, "p2", seed, 2L)==null, "semantic state profile isolation");
        check(cache.viewState(new Provider(), "p1", seed, 2L)==null, "semantic state adapter isolation");
        ProviderDetailsMemoryCache.ViewState malicious =
                new ProviderDetailsMemoryCache.ViewState("p".repeat(1000), 1, Integer.MAX_VALUE,
                        "t".repeat(2000));
        check(malicious.selectedEpisodeId.isEmpty(), "oversized episode state rejected");
        check(malicious.focusTag.isEmpty(), "oversized focus rejected");
        check(malicious.scrollY==1_000_000, "scroll bounded");
        for(int n=0;n<100;n++) {
            MediaItem item = title("item"+n, "");
            check(cache.put(provider, "p1", item, item, n+10), "insert item " + n);
            check(cache.size()<=ProviderDetailsMemoryCache.MAX_ENTRIES, "bounded item count");
            check(cache.estimatedChars()<=ProviderDetailsMemoryCache.MAX_TOTAL_CHARS, "bounded chars");
        }
        check(cache.get(provider, "p1", seed, 110L)==null, "old entry LRU evicted");
        cache.clear();
        check(cache.size()==0 && cache.estimatedChars()==0, "clear all scopes");
    }
    private static void nestedRelatedBudget() {
        ProviderDetailsMemoryCache cache = new ProviderDetailsMemoryCache();
        Provider provider = new Provider();
        MediaItem seed = title("nested-parent", "");
        // Provider-related titles can already be full MediaItems, including episode
        // payloads. The shallow cache estimate must not hide these retained bytes.
        MediaItem expensiveRelated = series("nested-related", 8, 60_000);
        MediaItem parent = new MediaItem("nested-parent", "Parent", MediaKind.MOVIE,
                seed.detailUrl, "", "2025", "", "",
                Collections.emptyList(), 0, "", "", "", Collections.emptyMap(), "",
                Collections.emptyList(), Collections.singletonList(expensiveRelated),
                Collections.emptyList());
        check(!cache.put(provider, "p1", seed, parent, 100),
                "nested related episode payload must count toward memory budget");
        check(cache.size() == 0, "rejected nested payload must not be retained");
        MediaItem safeRelated = title("safe-related", "");
        MediaItem normal = new MediaItem("nested-parent", "Parent", MediaKind.MOVIE,
                seed.detailUrl, "", "2025", "", "",
                Collections.emptyList(), 0, "", "", "", Collections.emptyMap(), "",
                Collections.emptyList(), Collections.singletonList(safeRelated),
                Collections.emptyList());
        check(cache.put(provider, "p1", seed, normal, 101),
                "ordinary related titles must remain cacheable");
        Random random = new Random(0xCA7E11L);
        for (int n = 0; n < 300; n++) {
            int episodeCount = 4 + random.nextInt(7);
            int dataLength = 60_000 + random.nextInt(3_000);
            MediaItem oversizedChild = series("fuzz-child-"+n, episodeCount, dataLength);
            MediaItem candidate = new MediaItem("nested-parent", "Parent", MediaKind.MOVIE,
                    seed.detailUrl, "", "2025", "", "",
                    Collections.emptyList(), 0, "", "", "", Collections.emptyMap(), "",
                    Collections.emptyList(), Collections.singletonList(oversizedChild),
                    Collections.emptyList());
            check(!cache.put(provider, "p1", seed, candidate, 102+n),
                    "nested fuzz payload over budget must be rejected");
            check(cache.size()==0, "rejected fuzz payload must not remain cached");
        }
    }
    private static void randomized() {
        ProviderDetailsMemoryCache cache=new ProviderDetailsMemoryCache();
        Provider p=new Provider(), replacement=new Provider();
        Random random = new Random(0xA0B17L);
        for(int n=0;n<70_000;n++) {
            int title=random.nextInt(40);
            String profile = random.nextBoolean()?"a":"b";
            MediaItem seed = title("t"+title, "");
            long now=300_000L+n;
            int operation=random.nextInt(7);
            if(operation==0) cache.clear();
            else if(operation<=3) cache.put(p, profile, seed, title("t"+title, "valid"), now);
            else {
                MediaItem hit=cache.get(operation==6?replacement:p, profile, seed, now);
                check(hit==null || hit.stableId.equals(seed.stableId), "no wrong-title reuse");
            }
            check(cache.size()<=ProviderDetailsMemoryCache.MAX_ENTRIES, "entry cap");
            check(cache.estimatedChars()>=0 &&
                    cache.estimatedChars()<=ProviderDetailsMemoryCache.MAX_TOTAL_CHARS, "char cap");
        }
    }
    private static void repeatOpenContract() {
        ProviderDetailsMemoryCache cache = new ProviderDetailsMemoryCache();
        Provider provider = new Provider();
        MediaItem seed = title("repeat", "");
        MediaItem verified = title("repeat", "ephemeral-verified-action-data");
        int underlyingLoads = 0;
        for (int open=0;open<100;open++) {
            MediaItem cached=cache.get(provider, "p1",seed, 42_000L+open);
            if (cached==null) {
                underlyingLoads++;
                check(cache.put(provider,"p1",seed,verified,42_000L+open),"load cached");
            }
            check(cache.get(provider,"p1",seed,42_000L+open)==verified,"warm result");
        }
        check(underlyingLoads==1,"100 valid warm opens -> one underlying load in contract");
        check(cache.get(new Provider(),"p1",seed,42_100L)==null,
                "new adapter never inherits old action context");
        check(cache.get(provider,"p2",seed,42_100L)==null,
                "new profile never inherits old data");
        check(cache.get(provider,"p1",seed,42_000L+ProviderDetailsMemoryCache.MAX_AGE_MS)==null,
                "expired context cannot be used");
    }
    private static void concurrent() throws Exception {
        ProviderDetailsMemoryCache cache = new ProviderDetailsMemoryCache();
        Provider provider = new Provider();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(4), start = new CountDownLatch(1);
        Thread[] threads = new Thread[4];
        for(int i=0;i<threads.length;i++) {
            final int index=i;
            threads[i]=new Thread(()->{
                ready.countDown();
                try {
                    start.await();
                    for(int n=0;n<10_000;n++) {
                        MediaItem item=title("thread"+index+"-"+(n%24), "payload");
                        if(n%13==0) cache.clear();
                        else if(n%3==0) cache.put(provider, "p"+index,item,item,n);
                        else cache.get(provider,"p"+index,item,n);
                    }
                } catch(Throwable t) { error.compareAndSet(null,t); }
            }, "details-cache-test-"+i);
            threads[i].start();
        }
        ready.await();start.countDown();
        for(Thread t:threads) t.join(15_000L);
        for(Thread t:threads) check(!t.isAlive(), "no deadlock");
        if(error.get()!=null) throw new AssertionError("concurrent failure",error.get());
        check(cache.size()<=ProviderDetailsMemoryCache.MAX_ENTRIES,"final concurrent count cap");
        check(cache.estimatedChars()<=ProviderDetailsMemoryCache.MAX_TOTAL_CHARS,"final concurrent char cap");
    }
    public static void main(String[] args) throws Exception {
        basics();boundsAndState();nestedRelatedBudget();randomized();repeatOpenContract();concurrent();
        System.out.println("PROVIDER_DETAILS_MEMORY_CACHE_PASS assertions="+assertions);
    }
}
