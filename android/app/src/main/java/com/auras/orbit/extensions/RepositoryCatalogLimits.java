package com.auras.orbit.extensions;

import java.io.IOException;

/** Hard resource budgets for untrusted repository JSON, independent of transfer byte limits. */
public final class RepositoryCatalogLimits {
    public static final int MAX_REPOSITORY_LISTS = 64;
    public static final int MAX_RECORDS_PER_LIST = 5000;
    public static final int MAX_CATALOG_RECORDS = 10000;

    private RepositoryCatalogLimits() { }

    public static void checkRepositoryLists(int count) throws IOException {
        if (count < 1 || count > MAX_REPOSITORY_LISTS) {
            throw new IOException("Repository descriptor has an invalid number of plugin lists.");
        }
    }

    public static void checkListRecords(int count) throws IOException {
        if (count < 0 || count > MAX_RECORDS_PER_LIST) {
            throw new IOException("Repository plugin list exceeds the supported item count.");
        }
    }

    public static void checkCatalogRecords(int count) throws IOException {
        if (count < 0 || count > MAX_CATALOG_RECORDS) {
            throw new IOException("Repository catalog exceeds the supported item count.");
        }
    }
}
