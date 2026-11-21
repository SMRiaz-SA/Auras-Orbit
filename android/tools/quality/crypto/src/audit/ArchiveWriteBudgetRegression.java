package audit;
import com.auras.orbit.backup.OrbitLibraryArchiveCodec;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;

/** Actual archive writer, under a small heap, must reject huge JSON before UTF-8 allocation. */
public final class ArchiveWriteBudgetRegression {
    static final char[] PASS = "correct horse battery staple".toCharArray();
    static class HugeJson extends JSONObject {
        final int chars;
        final String repeated;
        HugeJson(int chars, String repeated) { super("{}"); this.chars=chars; this.repeated=repeated; }
        @Override public String toString() { return repeated.repeat(chars); }
    }
    private static void rejected(HugeJson huge) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            OrbitLibraryArchiveCodec.write(huge, output, PASS);
            throw new AssertionError("Oversized archive JSON was accepted");
        } catch (OrbitLibraryArchiveCodec.ArchiveException expected) {
            if (output.size()!=0) throw new AssertionError("Invalid backup was partially written");
        }
    }
    public static void main(String[] args) throws Exception {
        rejected(new HugeJson(70_000_000, "a"));
        System.gc();
        rejected(new HugeJson(25_000_000, "€"));
        System.out.println("ARCHIVE_WRITE_PREALLOCATION_BUDGET_PASS cases=2 heap=128MiB");
    }
}
