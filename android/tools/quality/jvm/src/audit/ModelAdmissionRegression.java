package audit;

import com.auras.orbit.metadata.ActorMetadata;
import com.auras.orbit.providers.HomeSection;
import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Reproduces hostile provider-model sizes without allocating a giant backing list. */
public final class ModelAdmissionRegression {
    private static long checks;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static <T> List<T> huge(T value, AtomicInteger visits, int failAfter) {
        return new AbstractList<T>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public T get(int index) {
                if (visits.incrementAndGet() > failAfter) {
                    throw new AssertionError("Input collection scanned beyond budget: " + index);
                }
                return value;
            }
        };
    }

    private static MediaItem itemWith(List<String> tags, List<MediaItem.CastMember> cast,
            List<MediaItem> related, List<MediaItem.EpisodeItem> episodes,
            List<MediaItem.SeasonInfo> seasons) {
        return new MediaItem("id", "A title", MediaKind.MOVIE, "https://example.com/detail",
                "", "", "", "", tags, 0, "", "", "", Collections.emptyMap(),
                "", cast, related, episodes, null, seasons, null);
    }

    public static void main(String[] args) {
        MediaItem valid = itemWith(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        AtomicInteger homeVisits = new AtomicInteger();
        try {
            new HomeSection("main", "Main", huge(valid, homeVisits, 65), "");
            throw new AssertionError("Impossible Home list should fail explicitly");
        } catch (IllegalArgumentException expected) {
            require(homeVisits.get() == 0,
                    "Home admission must not iterate an impossible list");
        }
        AtomicInteger tagVisits = new AtomicInteger();
        itemWith(huge("", tagVisits, 201), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        require(tagVisits.get() <= 200, "Tags must have an input scan budget");
        AtomicInteger castVisits = new AtomicInteger();
        itemWith(Collections.emptyList(), huge(null, castVisits, 121), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        require(castVisits.get() <= 120, "Cast must have an input scan budget");
        AtomicInteger relatedVisits = new AtomicInteger();
        itemWith(Collections.emptyList(), Collections.emptyList(), huge(null, relatedVisits, 121),
                Collections.emptyList(), Collections.emptyList());
        require(relatedVisits.get() <= 120, "Related titles must have an input scan budget");
        // The old behaviour silently truncated legitimate 501+ episode lists.
        // Admitted episodes must survive intact; obviously impossible list sizes fail
        // explicitly without preallocating or touching attacker-controlled getters.
        AtomicInteger episodeVisits = new AtomicInteger();
        boolean oversizedRejected = false;
        try {
            itemWith(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    huge(null, episodeVisits, 1), Collections.emptyList());
        } catch (IllegalArgumentException expected) { oversizedRejected = true; }
        require(oversizedRejected && episodeVisits.get() == 0,
                "Pathological episode list rejected without scanning or silent truncation");
        ArrayList<MediaItem.EpisodeItem> completeSeries = new ArrayList<>();
        for (int number = 1; number <= 1200; number++) {
            completeSeries.add(new MediaItem.EpisodeItem("series-" + number,
                    "Episode " + number, 1, number, "", "", 0, "", 0L, "", "payload"));
        }
        MediaItem retainedSeries = itemWith(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), completeSeries, Collections.emptyList());
        require(retainedSeries.episodes.size() == 1200
                && retainedSeries.episodes.get(1199).number == 1200,
                "A 1,200-episode series must not be truncated to 500 episodes");
        AtomicInteger seasonVisits = new AtomicInteger();
        itemWith(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), huge(null, seasonVisits, 401));
        require(seasonVisits.get() <= 400, "Season labels must have an input scan budget");
        Map<String, String> hostileHeaders = new LinkedHashMap<>();
        hostileHeaders.put("Bad Header", "a");
        hostileHeaders.put("Host", "misdirect.example");
        hostileHeaders.put("Proxy-Authorization", "secret");
        hostileHeaders.put("Connection", "keep-alive");
        hostileHeaders.put("Referer", "https://site.example/");
        hostileHeaders.put("referer", "https://other.example/");
        MediaItem withHeaders = new MediaItem("x", "Title", MediaKind.MOVIE, "", "", "", "", "",
                Collections.emptyList(), 0, "", "", "", hostileHeaders);
        require(withHeaders.posterHeaders.size() == 1 && withHeaders.posterHeaders.containsKey("Referer"),
                "Dangerous and duplicate-case HTTP headers must be rejected before network handling");
        AtomicInteger headerVisits = new AtomicInteger();
        Map<String, String> hostileMap = new AbstractMap<String, String>() {
            @Override public Set<Entry<String, String>> entrySet() {
                return new AbstractSet<Entry<String, String>>() {
                    @Override public int size() { return Integer.MAX_VALUE; }
                    @Override public Iterator<Entry<String, String>> iterator() {
                        return new Iterator<Entry<String, String>>() {
                            @Override public boolean hasNext() { return true; }
                            @Override public Entry<String, String> next() {
                                if (headerVisits.incrementAndGet() > 256) {
                                    throw new AssertionError("Scanned unbounded invalid headers");
                                }
                                return new SimpleImmutableEntry<>("Bad Header", "bad");
                            }
                        };
                    }
                };
            }
        };
        MediaItem emptyHeaders = new MediaItem("x", "Title", MediaKind.MOVIE, "", "", "", "", "",
                Collections.emptyList(), 0, "", "", "", hostileMap);
        require(emptyHeaders.posterHeaders.isEmpty() && headerVisits.get() <= 256,
                "Invalid header maps must have bounded input scans");
        Random random = new Random(20261109L);
        for (int n = 0; n < 15_000; n++) {
            int count = random.nextInt(800);
            ArrayList<String> strings = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                strings.add(random.nextBoolean() ? "" : "Genre-" + i);
            }
            MediaItem metadataItem = itemWith(strings, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            int expected = 0;
            for (int i = 0; i < Math.min(200, strings.size()) && expected < 50; i++) {
                if (!strings.get(i).isEmpty()) expected++;
            }
            require(metadataItem.tags.size() == expected, "Tag admission prefix is not deterministic");
            require(metadataItem.tags.size() <= 50, "Accepted tags exceed output budget");
            int homeCount = random.nextInt(150);
            List<MediaItem> homeInput = Collections.nCopies(homeCount, valid);
            HomeSection shelf = new HomeSection("id", "Shelf", homeInput, "next");
            require(shelf.items.size() == Math.min(homeCount, HomeSection.MAX_DISPLAY_ITEMS),
                    "Home shelf size was not bounded deterministically");
            require("next".equals(shelf.nextCursor), "Home cursor must survive bounded copy");
        }
        List<ActorMetadata.Credit> normal = Collections.nCopies(521,
                new ActorMetadata.Credit("Film", "2026", "Movie", "Lead"));
        require(new ActorMetadata("A", "", "", "", "", "", normal).credits.size() == 521,
                "Actor filmography must retain credits beyond earlier 200 limit");
        boolean rejected = false;
        try {
            new ActorMetadata("A", "", "", "", "", "", huge(null, new AtomicInteger(), 20_001));
        } catch (IllegalArgumentException bounded) {
            rejected = true;
        }
        require(rejected, "Unreasonably huge actor filmography must fail predictably instead of OOM");
        System.out.println("ModelAdmissionRegression PASS " + checks + " assertions");
    }
}
