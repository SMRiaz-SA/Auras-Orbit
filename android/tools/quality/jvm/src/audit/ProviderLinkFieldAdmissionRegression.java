package audit;

import com.auras.orbit.providers.ProviderLinkCandidate;
import java.util.*;

/** Real-source field budgets: fail before retaining provider-supplied huge link strings. */
public final class ProviderLinkFieldAdmissionRegression {
    private static long checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static ProviderLinkCandidate create(String id, String name, String source, String uri,
            String referer, String type, String extractorData) {
        return new ProviderLinkCandidate(id, name, source, uri, referer, 1080, type,
                extractorData, Collections.emptyMap());
    }
    private static void reject(Runnable action, String name) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(name + " accepted a field exceeding its downstream budget");
    }
    private static void bounds() {
        String longId = "a".repeat(4097);
        String longUri = "https://example.com/" + "a".repeat(16385);
        String longReferer = "https://example.com/" + "a".repeat(4097);
        String longData = "a".repeat(16385);
        String longLabel = "a".repeat(513);
        reject(() -> create(longId,"n","s","https://example.com/v.mp4","","VIDEO",""), "ID");
        reject(() -> create("id",longLabel,"s","https://example.com/v.mp4","","VIDEO",""), "name");
        reject(() -> create("id","n",longLabel,"https://example.com/v.mp4","","VIDEO",""), "source");
        reject(() -> create("id","n","s",longUri,"","VIDEO",""), "URI");
        reject(() -> create("id","n","s","https://example.com/v.mp4",longReferer,"VIDEO",""), "referer");
        reject(() -> create("id","n","s","https://example.com/v.mp4","","a".repeat(129),""), "type");
        reject(() -> create("id","n","s","https://example.com/v.mp4","","VIDEO",longData), "verifier data");
    }
    private static void roundtrip() {
        Random random = new Random(0x41555241L);
        for (int i=0; i<100_000; i++) {
            int length = random.nextInt(4096);
            String id = "x".repeat(length + 1);
            ProviderLinkCandidate c = create(id,"Label","Source","https://example.com/v.mp4",
                    "https://example.com", "VIDEO", "token");
            check(id.equals(c.id) && c.uri.equals("https://example.com/v.mp4")
                    && c.extractorData.equals("token"), "normal metadata survives admission");
        }
    }
    public static void main(String[] args) {
        bounds();
        roundtrip();
        System.out.println("PROVIDER_LINK_FIELD_ADMISSION checks=" + checks + " failures=0");
    }
}
