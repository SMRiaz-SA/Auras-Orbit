package com.auras.orbit.providers;

/** Search shared by the universal Search surface and a provider-scoped Home quick search. */
public final class SearchRequest {
    public final String query;
    public final SearchType type;
    public final int page;

    public SearchRequest(String query, SearchType type, int page) {
        if (query == null || query.trim().isEmpty()) throw new IllegalArgumentException("query is required");
        if (type == null) throw new IllegalArgumentException("title search type is required");
        if (page < 1) throw new IllegalArgumentException("page must be at least 1");
        this.query = query.trim();
        this.type = type;
        this.page = page;
    }
}