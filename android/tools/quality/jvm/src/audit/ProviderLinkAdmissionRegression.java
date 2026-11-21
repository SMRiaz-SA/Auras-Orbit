package audit;

import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderLinkCandidate;
import com.auras.orbit.providers.ProviderSubtitleCandidate;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Attack the pre-player link admission boundary with pathological provider lists. */
public final class ProviderLinkAdmissionRegression {
    private static long checks;
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static <T> List<T> sized(int count) {
        return new AbstractList<T>() {
            @Override public int size() { return count; }
            @Override public T get(int position) {
                if (position < 0 || position >= count) throw new IndexOutOfBoundsException();
                return null;
            }
        };
    }
    private static void rejects(List<ProviderLinkCandidate> links, List<ProviderSubtitleCandidate> subtitles) {
        boolean rejected = false;
        try { new ProviderLinkResult(false, links, subtitles, "provider test"); }
        catch (IllegalArgumentException correct) { rejected = true; }
        catch (OutOfMemoryError unboundedAllocation) {
            throw new AssertionError("Untrusted provider cardinality caused heap exhaustion", unboundedAllocation);
        }
        check(rejected, "Oversized result must fail before allocation");
    }
    public static void main(String[] args) {
        rejects(sized(Integer.MAX_VALUE), Collections.emptyList());
        rejects(Collections.emptyList(), sized(Integer.MAX_VALUE));
        List<ProviderLinkCandidate> deceptive = new AbstractList<>() {
            @Override public int size() { return 2; }
            @Override public ProviderLinkCandidate get(int index) { return null; }
            @Override public Object[] toArray() {
                throw new AssertionError("Plugin-owned toArray must not be invoked");
            }
        };
        check(new ProviderLinkResult(false, deceptive, Collections.emptyList(), "test")
                .links.size() == 2, "constructor does not call hostile toArray");
        ProviderLinkResult allowed = new ProviderLinkResult(false, sized(512), sized(256), "ok");
        check(allowed.links.size() == 512 && allowed.subtitles.size() == 256,
                "Ordinary bounded results preserved");
        Random rng = new Random(0xA0B170B17L);
        for (int i = 0; i < 40_000; i++) {
            int linkCount = rng.nextInt(768);
            int subtitleCount = rng.nextInt(300);
            if (linkCount > ProviderLinkResult.MAX_LINKS || subtitleCount > ProviderLinkResult.MAX_SUBTITLES) {
                rejects(sized(linkCount), sized(subtitleCount));
            } else {
                ProviderLinkResult result = new ProviderLinkResult(false,
                        sized(linkCount), sized(subtitleCount), "ok");
                check(result.links.size() == linkCount, "Link count preserved");
                check(result.subtitles.size() == subtitleCount, "Subtitle count preserved");
            }
        }
        rejects(sized(ProviderLinkResult.MAX_LINKS + 1), Collections.emptyList());
        rejects(Collections.emptyList(), sized(ProviderLinkResult.MAX_SUBTITLES + 1));
        System.out.println("ProviderLinkAdmissionRegression: " + checks + " checks passed");
    }
}
