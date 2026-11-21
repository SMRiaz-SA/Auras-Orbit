package com.auras.orbit.ui;

/** Overflow-safe fixed-size pages for Android view lists; no Android dependencies. */
public final class PageWindow {
    private PageWindow() { }

    public static Window of(int total, int requestedPage, int pageSize) {
        if (total < 0 || pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("Invalid list or page size");
        }
        int lastPage = total == 0 ? 0 : (total - 1) / pageSize;
        int page = Math.max(0, Math.min(lastPage, requestedPage));
        int start = page * pageSize; // <= total, so never exceeds the supported int range
        int end = (int) Math.min((long) total, (long) start + pageSize);
        return new Window(total, page, lastPage, start, end);
    }

    public static final class Window {
        public final int total, page, lastPage, start, end;
        private Window(int total, int page, int lastPage, int start, int end) {
            this.total = total;
            this.page = page;
            this.lastPage = lastPage;
            this.start = start;
            this.end = end;
        }
        public boolean hasPrevious() { return page > 0; }
        public boolean hasNext() { return page < lastPage; }
    }
}
