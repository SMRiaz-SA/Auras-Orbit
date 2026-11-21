package audit;

import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderSubtitleCandidate;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Provider header admission must not copy attacker-sized maps or ambiguous request fields. */
public final class ProviderHeaderAdmissionRegression {
    private static long checks;
    private static void check(boolean yes, String detail) {
        checks++;
        if (!yes) throw new AssertionError(detail);
    }
    private static ProviderLinkCandidate link(Map<String, String> headers) {
        return new ProviderLinkCandidate("p:0", "name", "source", "https://example.org/file.mp4",
                "", 720, "VIDEO", "", headers);
    }
    private static ProviderSubtitleCandidate subtitle(Map<String, String> headers) {
        return new ProviderSubtitleCandidate("s:0", "en", "https://example.org/sub.vtt", headers);
    }
    private static void rejected(Map<String, String> headers) {
        boolean first = false, second = false;
        try { link(headers); }
        catch (IllegalArgumentException expected) { first = true; }
        try { subtitle(headers); }
        catch (IllegalArgumentException expected) { second = true; }
        check(first && second, "both provider header boundaries must reject invalid metadata");
    }
    public static void main(String[] args) {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) many.put("X-" + i, "value");
        rejected(many);
        rejected(Collections.singletonMap("Referer", "a".repeat(8193)));
        rejected(Collections.singletonMap("Referer", "good\r\nHost: injected"));
        boolean subtitleUriRejected = false;
        try { new ProviderSubtitleCandidate("id", "en", "https://example.com/" + "x".repeat(5000),
                Collections.emptyMap()); }
        catch (IllegalArgumentException correct) { subtitleUriRejected = true; }
        check(subtitleUriRejected, "Oversized subtitle URI rejected before callback retention");
        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("Referer", "one"); duplicate.put("referer", "two");
        rejected(duplicate);
        Map<String, String> normal = Map.of("Referer", "https://example.org/", "Cookie", "a=b");
        check(link(normal).headers.size() == 2, "valid link headers retained");
        check(subtitle(normal).headers.size() == 2, "valid subtitle headers retained");
        Map<String, String> perpetual = new AbstractMap<>() {
            @Override public int size() { return 1; }
            @Override public Set<Entry<String, String>> entrySet() {
                return new AbstractSet<>() {
                    @Override public int size() { return 1; }
                    @Override public Iterator<Entry<String, String>> iterator() {
                        return new Iterator<>() {
                            int count;
                            @Override public boolean hasNext() { return true; }
                            @Override public Entry<String, String> next() {
                                if (++count > 10_000) throw new IllegalStateException("Unbounded header scan");
                                return Map.entry("X-A", "value");
                            }
                        };
                    }
                };
            }
        };
        rejected(perpetual);
        Random random = new Random(0x9A5F001L);
        for (int i=0;i<20_000;i++) {
            Map<String,String> headers = new HashMap<>();
            int count = random.nextInt(100);
            for (int index=0;index<count;index++) headers.put("X-" + index, "v" + index);
            if (count > 64) rejected(headers);
            else {
                check(link(headers).headers.size() == count, "valid link metadata exact count");
                check(subtitle(headers).headers.size() == count, "valid subtitle metadata exact count");
            }
        }
        System.out.println("ProviderHeaderAdmissionRegression: " + checks + " checks passed");
    }
}
