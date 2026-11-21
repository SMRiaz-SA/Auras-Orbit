package audit;

import com.lagradost.cloudstream3.subtitles.SubtitleArchiveGuard;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public final class SubtitleArchiveGuardRegression {
    private static long checks;
    interface Checked { void run() throws Exception; }
    private static void verify(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void throwsIOException(Checked action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        verify(rejected, message);
    }
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String,byte[]> pair: entries.entrySet()) {
                output.putNextEntry(new ZipEntry(pair.getKey()));
                output.write(pair.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static File write(File dir, byte[] bytes) throws IOException {
        File f = File.createTempFile("source-", ".zip", dir);
        Files.write(f.toPath(), bytes);
        return f;
    }
    private static void cleanup(List<SubtitleArchiveGuard.Extracted> items) {
        for (SubtitleArchiveGuard.Extracted item : items) item.file.delete();
    }
    private static int tempCount(File directory) {
        String[] files = directory.list((dir, name) -> name.startsWith("unzipped-subtitle-"));
        return files == null ? -1 : files.length;
    }
    public static void main(String[] unused) throws Exception {
        File work = Files.createTempDirectory("orbit-subtitle-fuzz").toFile();
        try {
            LinkedHashMap<String, byte[]> valid = new LinkedHashMap<>();
            valid.put("dir/subtitle.srt", "00:00:01,000 --> 00:00:02,000\nHi".getBytes(StandardCharsets.UTF_8));
            valid.put("sub/french.vtt", "WEBVTT\n".getBytes(StandardCharsets.UTF_8));
            File zip = write(work, zip(valid));
            List<SubtitleArchiveGuard.Extracted> got = SubtitleArchiveGuard.extract(zip, work);
            verify(got.size() == 2, "ordinary subtitle count");
            verify("subtitle.srt".equals(got.get(0).name), "safe basename only");
            verify("WEBVTT\n".equals(Files.readString(got.get(1).file.toPath())), "contents intact");
            for (SubtitleArchiveGuard.Extracted item : got) {
                verify(item.file.getCanonicalFile().getParentFile().equals(work.getCanonicalFile()), "private cache ownership");
            }
            cleanup(got);
            verify(tempCount(work) == 0, "no remaining temporary files");

            // Invalid plain bytes used to return an empty successful list; fail closed.
            File invalid = write(work, "not actually a ZIP".getBytes(StandardCharsets.UTF_8));
            throwsIOException(() -> SubtitleArchiveGuard.extract(invalid, work), "invalid ZIP must fail");
            LinkedHashMap<String,byte[]> tooMany = new LinkedHashMap<>();
            for (int index = 0; index < 65; index++) tooMany.put("subtitle" + index + ".srt", new byte[]{1});
            File many = write(work, zip(tooMany));
            throwsIOException(() -> SubtitleArchiveGuard.extract(many, work), "entry cap");
            verify(tempCount(work) == 0, "entry-cap rollback");
            byte[] huge = new byte[(int) SubtitleArchiveGuard.MAX_ENTRY_BYTES + 1];
            Arrays.fill(huge, (byte) 'X');
            File bomb = write(work, zip(Collections.singletonMap("huge.srt", huge)));
            throwsIOException(() -> SubtitleArchiveGuard.extract(bomb, work), "decompression cap");
            verify(tempCount(work) == 0, "decompression rollback");

            for (String name : new String[]{"../steal.srt", "sub/../../steal.srt", "/root.srt",
                    "sub\\..\\steal.srt", "evil\rname.srt", "C:drive.srt"}) {
                File traversal = write(work, zip(Collections.singletonMap(name, new byte[]{1})));
                throwsIOException(() -> SubtitleArchiveGuard.extract(traversal, work), "path reject " + name);
                verify(tempCount(work) == 0, "path reject leaves no file");
                traversal.delete();
            }
            for (String value : new String[]{"http://example.com/a.zip", "file:///etc/passwd",
                    "https://localhost/s.zip", "https://user:pass@example.com/a.zip",
                    "https://example.com:444/a.zip", "https://example.com/a.zip#frag",
                    "https://127.0.0.1/a.zip", "https://example.com.:443/a.zip"}) {
                throwsIOException(() -> SubtitleArchiveGuard.requireHttpsSubtitleUrl(value), "unsafe URI " + value);
            }
            verify("example.com".equals(SubtitleArchiveGuard.requireHttpsSubtitleUrl("https://example.com/test.zip").getHost()), "valid HTTPS URI");
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            verify(SubtitleArchiveGuard.copyLimited(new ByteArrayInputStream(new byte[]{1, 2, 3}), destination, 3) == 3, "exact boundary");
            verify(destination.size() == 3, "boundary bytes retained");
            throwsIOException(() -> SubtitleArchiveGuard.copyLimited(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                    new ByteArrayOutputStream(), 2), "oversized source rejected");
            throwsIOException(() -> SubtitleArchiveGuard.copyLimitedUntil(
                    new ByteArrayInputStream(new byte[]{1}), new ByteArrayOutputStream(), 8,
                    System.nanoTime() - 1L), "expired body deadline rejected");
            throwsIOException(() -> SubtitleArchiveGuard.copyLimited(new InputStream() {
                @Override public int read() { return 0; }
                @Override public int read(byte[] b, int o, int n) { return 0; }
            }, new ByteArrayOutputStream(), 100), "infinite zero-length reads rejected");

            // Randomly shaped but CRC-correct archives prove truncation and ownership invariants.
            Random random = new Random(0xAC0BAD12L);
            for (int pass = 0; pass < 400; pass++) {
                int number = 1 + random.nextInt(8);
                LinkedHashMap<String,byte[]> generated = new LinkedHashMap<>();
                for (int i=0; i<number; i++) {
                    byte[] content = new byte[random.nextInt(16000)];
                    random.nextBytes(content);
                    generated.put("group"+i+"/name"+pass+"_"+i+".srt", content);
                }
                File file = write(work, zip(generated));
                List<SubtitleArchiveGuard.Extracted> extracted = SubtitleArchiveGuard.extract(file, work);
                verify(extracted.size() == number, "generated archive count: " + pass);
                int index=0;
                for (byte[] expected : generated.values()) {
                    verify(Arrays.equals(expected, Files.readAllBytes(extracted.get(index++).file.toPath())),
                            "archive bytes differ: " + pass);
                }
                cleanup(extracted);
                file.delete();
                verify(tempCount(work) == 0, "generated archive cleanup: " + pass);
            }
            for (int pass = 0; pass < 1200; pass++) {
                byte[] garbage = new byte[1 + pass % 203];
                new Random(0xBADD0000L + pass).nextBytes(garbage);
                File file = write(work, garbage);
                try {
                    List<SubtitleArchiveGuard.Extracted> result = SubtitleArchiveGuard.extract(file, work);
                    cleanup(result);
                    throw new AssertionError("Random garbage accepted as ZIP at pass " + pass);
                } catch (IOException expected) {
                    checks++;
                }
                verify(tempCount(work) == 0, "corrupt ZIP cleanup: " + pass);
                file.delete();
            }
            zip.delete(); invalid.delete(); many.delete(); bomb.delete();
            System.out.println("SUBTITLE_ARCHIVE_GUARD_PASS checks="+checks);
        } finally {
            File[] files = work.listFiles();
            if (files != null) for (File file: files) file.delete();
            work.delete();
        }
    }
}
