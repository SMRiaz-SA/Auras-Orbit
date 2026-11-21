package audit;

import com.auras.orbit.extensions.RepositoryCatalogLimits;
import java.io.IOException;
import java.util.Random;

/** Fuzzes the actual production limits with bounded synthetic JSON index sizes. */
public final class RepositoryBudgetRegression {
    private static int checks;
    private static void check(boolean shouldPass, int count, int max, String kind) throws Exception {
        boolean accepted;
        try {
            if (kind.equals("lists")) RepositoryCatalogLimits.checkRepositoryLists(count);
            else if (kind.equals("items")) RepositoryCatalogLimits.checkListRecords(count);
            else RepositoryCatalogLimits.checkCatalogRecords(count);
            accepted = true;
        } catch (IOException denied) { accepted = false; }
        if (accepted != shouldPass) throw new AssertionError("Bad " + kind + " budget for " + count);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Random random = new Random(0xA6C0FFEE);
        for (int i = 0; i < 500000; i++) {
            int a = random.nextInt(200000) - 20;
            check(a >= 1 && a <= RepositoryCatalogLimits.MAX_REPOSITORY_LISTS,
                    a, RepositoryCatalogLimits.MAX_REPOSITORY_LISTS, "lists");
            int b = random.nextInt(200000) - 20;
            check(b >= 0 && b <= RepositoryCatalogLimits.MAX_RECORDS_PER_LIST,
                    b, RepositoryCatalogLimits.MAX_RECORDS_PER_LIST, "items");
            int c = random.nextInt(200000) - 20;
            check(c >= 0 && c <= RepositoryCatalogLimits.MAX_CATALOG_RECORDS,
                    c, RepositoryCatalogLimits.MAX_CATALOG_RECORDS, "catalog");
        }
        System.out.println("REPOSITORY_BUDGET_REGRESSION checks=" + checks + " failures=0");
    }
}
