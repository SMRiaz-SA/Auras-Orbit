#!/usr/bin/env python3
"""JDK direct-extraction boundary test of the production URL canonicalizer."""
from pathlib import Path
import subprocess, tempfile
root=Path(__file__).resolve().parents[3]
source=(root/'app/src/main/java/com/auras/orbit/extensions/CloudStreamExtensionManager.java').read_text()
start=source.index('    public static String canonicalize(String raw) {')
end=source.index('    private static URL parseWebUrl(String value) {', start)
method=source[start:end]
test='''
    static int checks;
    static void ok(boolean condition, String detail) {
        checks++;
        if (!condition) throw new AssertionError(detail);
    }
    public static void main(String[] args) {
        for (int port = 0; port < 70000; port++) {
            for (String scheme : new String[]{"http", "https"}) {
                String candidate = scheme + "://example.com:" + port + "/catalog";
                String actual = canonicalize(candidate);
                ok((port >= 1 && port <= 65535) == (actual != null), candidate + " => " + actual);
                if (actual != null) {
                    ok(actual.equals(canonicalize(actual)), "not idempotent: " + actual);
                    if (port == 80 && scheme.equals("http") || port == 443 && scheme.equals("https")) {
                        ok(!actual.contains(":" + port + "/"), "default port must be omitted");
                    }
                }
            }
        }
        java.util.Random random = new java.util.Random(20261108L);
        for (int i = 0; i < 100000; i++) {
            int port = 1 + random.nextInt(65535);
            String candidate = (i % 2 == 0 ? "HTTPS://EXAMPLE.COM:" : "HTTP://EXAMPLE.COM:")
                    + port + "/repo/../items/?q=" + i;
            String actual = canonicalize(candidate);
            ok(actual != null && !actual.contains("EXAMPLE") && !actual.contains("/../"),
                    "normalization failed: " + candidate);
            ok(actual.equals(canonicalize(actual)), "canonical URL not stable");
        }
        ok(canonicalize("file:///etc/passwd") == null, "local files forbidden");
        ok(canonicalize("https://user:password@example.com/secret") == null, "credentials forbidden");
        ok(canonicalize("https://[2001:db8::1]:8080/plugins") != null, "IPv6 host broken");
        ok(canonicalize("https://example.com:65536") == null, "invalid port allowed");
        System.out.println("URL_CANONICALIZATION_REGRESSION checks=" + checks + " failures=0");
    }
'''
with tempfile.TemporaryDirectory() as folder:
    file=Path(folder)/'CanonicalUrlSourceProbe.java'
    file.write_text('import java.net.*; import java.util.*;\npublic final class CanonicalUrlSourceProbe {\n'+method+test+'\n}')
    subprocess.run(['javac','--release','17',str(file)],check=True,capture_output=True,text=True)
    subprocess.run(['java','-Xmx512m','-cp',folder,'CanonicalUrlSourceProbe'],check=True)
