package com.auras.orbit.jellyfin;

import java.io.IOException;
import java.util.Random;

/** Mutation-sensitive boundary oracle for all Jellyfin array admission limits. */
public final class JellyfinResponseBudgetsRegression {
    private static long checks;
    private static void check(int actual, int max) {
        boolean expectedAccept = actual >= 0 && actual <= max;
        boolean accepted;
        try { JellyfinResponseBudgets.requireCount(actual, max, "fixture"); accepted = true; }
        catch (IOException expected) { accepted = false; }
        if (accepted != expectedAccept) throw new AssertionError(actual + " max " + max);
        checks++;
    }
    public static void main(String[] args) {
        int[] bounds = {0,1,64,100,256,JellyfinPageAccumulator.MAX_ITEMS_PER_RESPONSE,
                JellyfinResponseBudgets.MAX_LIBRARIES,
                JellyfinResponseBudgets.MAX_RECENT_ITEMS,
                JellyfinResponseBudgets.MAX_PLAYBACK_SOURCES,
                JellyfinResponseBudgets.MAX_MEDIA_STREAMS_PER_SOURCE};
        for (int max: bounds) for(int n: new int[]{-1,0,1,max-1,max,max+1,1000,Integer.MAX_VALUE}) check(n,max);
        Random random=new Random(124812L);
        for (int i=0;i<100_000;i++) check(random.nextInt(),random.nextInt(1000));
        System.out.println("JELLYFIN_ARRAY_ADMISSION_PASS checks="+checks);
    }
}
