package org.junit;
public class Assert {
    public static void assertTrue(boolean value) { if (!value) throw new AssertionError("expected true"); }
    public static void assertTrue(String reason, boolean value) { if (!value) throw new AssertionError(reason); }
    public static void assertEquals(int expected, int actual) { if (expected != actual) throw new AssertionError(expected + " != " + actual); }
    public static void assertEquals(String message, int expected, int actual) { if (expected != actual) throw new AssertionError(message + ": " + expected + " != " + actual); }
}
