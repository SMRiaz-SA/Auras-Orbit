/* Orbit binding stub for the GPL-3.0 Torrentserver source described in UPSTREAM.md. */
package torrServer;

/** Keeps Go mobile initialization explicit and exposes only Orbit's required native entry point. */
public abstract class TorrServer {
    private TorrServer() {}

    public static void touch() {}

    public static native void _init();

    public static native long startTorrentServer(String pathdb, long port, String authToken);
}
