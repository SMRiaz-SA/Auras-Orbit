package com.auras.orbit.player;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

public final class OriginBoundStreamProgressRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectIOException(CheckedAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        InputStream stalled = new InputStream() {
            private int nextAttempt() {
                int attempt = reads.incrementAndGet();
                if (attempt > 8) throw new AssertionError("progress guard did not stop the retry loop");
                return attempt;
            }
            @Override public int read() { nextAttempt(); return 0; }
            @Override public int read(byte[] buffer, int offset, int length) {
                nextAttempt();
                return 0;
            }
            @Override public long skip(long count) { nextAttempt(); return 0L; }
        };

        expectIOException(() -> OriginBoundStreamProgress.read(stalled, new byte[4], 0, 4),
                "positive-length zero read must fail");
        int readsBeforeEmptyRequest = reads.get();
        check(OriginBoundStreamProgress.read(stalled, new byte[4], 0, 0) == 0,
                "zero-length read must return zero");
        check(reads.get() == readsBeforeEmptyRequest,
                "zero-length read must not call the underlying stream");
        expectIOException(() -> OriginBoundStreamProgress.skipFully(stalled, 1L),
                "zero skip followed by zero bulk read must fail");

        InputStream fallback = new ByteArrayInputStream(new byte[] { 10, 20, 30 }) {
            @Override public long skip(long count) { return 0L; }
        };
        OriginBoundStreamProgress.skipFully(fallback, 2L);
        byte[] finalByte = new byte[1];
        check(OriginBoundStreamProgress.read(fallback, finalByte, 0, 1) == 1
                        && finalByte[0] == 30,
                "bulk-read fallback must skip the requested bytes");

        InputStream emptyFallback = new ByteArrayInputStream(new byte[0]) {
            @Override public long skip(long count) { return 0L; }
        };
        try {
            OriginBoundStreamProgress.skipFully(emptyFallback, 1L);
            throw new AssertionError("short skip fallback must report EOF");
        } catch (EOFException expected) {
            checks++;
        }
        check(OriginBoundStreamProgress.read(emptyFallback, finalByte, 0, 1) == -1,
                "EOF must pass through unchanged");

        System.out.println("ORIGIN_BOUND_STREAM_PROGRESS_PASS checks=" + checks);
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
