package com.auras.orbit.player;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Progress checks shared by the origin-bound player data source and its JDK-only regression. */
final class OriginBoundStreamProgress {
    private OriginBoundStreamProgress() { }

    static int read(InputStream stream, byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        int count = stream.read(buffer, offset, length);
        if (count == 0) throw new IOException("Media input stream made no progress.");
        return count;
    }

    static void skipFully(InputStream stream, long bytes) throws IOException {
        byte[] discard = new byte[8192];
        long skipped = 0L;
        while (skipped < bytes) {
            long count = stream.skip(bytes - skipped);
            if (count <= 0L) {
                int read = read(stream, discard, 0, (int) Math.min(discard.length, bytes - skipped));
                if (read < 0) throw new EOFException("The media server returned an incomplete byte range.");
                count = read;
            }
            skipped += count;
        }
    }
}
