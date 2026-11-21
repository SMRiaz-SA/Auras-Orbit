package com.lagradost.cloudstream3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import kotlin.jvm.internal.DefaultConstructorMarker;

/** Orbit-owned score facade implementing the pinned CS3 score conversions used by providers. */
public final class Score {
    public static final int MAX = 1_000_000_000;
    public static final int MIN = 0;
    public static final int MAX_ZEROS = 9;
    public static final Companion Companion = new Companion();

    private final int data;

    private Score(int data) {
        this.data = data;
    }

    /** Pinned synthetic constructor descriptor used by Kotlin callers. */
    public Score(int data, DefaultConstructorMarker marker) {
        this(data);
    }

    @Override public int hashCode() { return Integer.hashCode(data); }

    @Override public boolean equals(Object other) {
        return this == other || (other instanceof Score && data == ((Score) other).data);
    }

    public final int toOld() { return toInt(10000); }
    public final byte toByte(int maxScore) { return (byte) toLong(maxScore); }
    public final int toInt(int maxScore) { return (int) toLong(maxScore); }
    public static int toInt$default(Score self, int maxScore, int mask, Object marker) {
        return self.toInt((mask & 1) != 0 ? 10 : maxScore);
    }
    public final long toLong(int maxScore) { return ((long) data * (long) maxScore) / MAX; }
    public static long toLong$default(Score self, int maxScore, int mask, Object marker) {
        return self.toLong((mask & 1) != 0 ? 10 : maxScore);
    }
    public final float toFloat(int maxScore) { return ((float) data / (float) MAX) * (float) maxScore; }
    public static float toFloat$default(Score self, int maxScore, int mask, Object marker) {
        return self.toFloat((mask & 1) != 0 ? 10 : maxScore);
    }
    public final double toDouble(int maxScore) { return ((double) data / (double) MAX) * (double) maxScore; }
    public static double toDouble$default(Score self, int maxScore, int mask, Object marker) {
        return self.toDouble((mask & 1) != 0 ? 10 : maxScore);
    }

    @Override public String toString() { return toString(10, 1, true, '.'); }

    public final String toStringNull(double minScore, int maxScore, int decimals,
            boolean removeTrailingZeros, char decimalChar) {
        if (toDouble(10) < minScore) return null;
        return toString(maxScore, decimals, removeTrailingZeros, decimalChar);
    }

    public static String toStringNull$default(Score self, double minScore, int maxScore,
            int decimals, boolean removeTrailingZeros, char decimalChar, int mask, Object marker) {
        if ((mask & 4) != 0) decimals = 1;
        if ((mask & 8) != 0) removeTrailingZeros = true;
        if ((mask & 16) != 0) decimalChar = '.';
        return self.toStringNull(minScore, maxScore, decimals, removeTrailingZeros, decimalChar);
    }

    public final String toString(int maxScore, int decimals,
            boolean removeTrailingZeros, char decimalChar) {
        if (maxScore < 1 || maxScore > 1000) {
            throw new IllegalArgumentException("maxScore ∈ [1,1000]");
        }
        if (decimals < 0 || decimals > MAX_ZEROS) {
            throw new IllegalArgumentException("decimals ∈ [0," + MAX_ZEROS + "]");
        }
        BigDecimal value = BigDecimal.valueOf((long) data * (long) maxScore, MAX_ZEROS)
                .setScale(decimals, RoundingMode.DOWN);
        if (removeTrailingZeros) value = value.stripTrailingZeros();
        String formatted = value.toPlainString();
        if (decimalChar != '.') formatted = formatted.replace('.', decimalChar);
        return formatted;
    }

    public static String toString$default(Score self, int maxScore, int decimals,
            boolean removeTrailingZeros, char decimalChar, int mask, Object marker) {
        if ((mask & 2) != 0) decimals = 1;
        if ((mask & 4) != 0) removeTrailingZeros = true;
        if ((mask & 8) != 0) decimalChar = '.';
        return self.toString(maxScore, decimals, removeTrailingZeros, decimalChar);
    }

    public static final class Companion {
        private Companion() { }
        public Companion(DefaultConstructorMarker marker) { }

        public Score fromOld(Integer value) {
            if (value == null) return null;
            if (value < 0 || value > 10000) {
                com.lagradost.api.Log.INSTANCE.w(
                        "Score", "old: " + value + " ∉ [0, 10000]");
                return null;
            }
            return new Score(value * 100_000);
        }

        public Score from(Integer value, int maxScore) {
            if (value == null) return null;
            if (value < 0 || value > maxScore) {
                warnOutOfRange("fromInt", value, maxScore);
                return null;
            }
            return new Score((MAX / maxScore) * value);
        }
        public Score from(Double value, int maxScore) {
            if (value == null) return null;
            if (value < 0.0d || value > maxScore) {
                warnOutOfRange("fromDouble", value, maxScore);
                return null;
            }
            return new Score(roundToInt((MAX / maxScore) * value.doubleValue()));
        }
        public Score from(Float value, int maxScore) {
            if (value == null) return null;
            if (value < 0.0f || value > maxScore) {
                warnOutOfRange("fromFloat", value, maxScore);
                return null;
            }
            return new Score(roundToInt((MAX / maxScore) * value.floatValue()));
        }
        public Score from(String value, int maxScore) {
            if (value == null) return null;
            Double parsed;
            try {
                parsed = Double.valueOf(trimWhitespace(value));
            } catch (RuntimeException ignored) {
                return null;
            }
            return from(Math.abs(parsed), maxScore);
        }

        public Score from5(Integer value) { return from(value, 5); }
        public Score from10(Integer value) { return from(value, 10); }
        public Score from100(Integer value) { return from(value, 100); }
        public Score from5(Double value) { return from(value, 5); }
        public Score from10(Double value) { return from(value, 10); }
        public Score from100(Double value) { return from(value, 100); }
        public Score from5(Float value) { return from(value, 5); }
        public Score from10(Float value) { return from(value, 10); }
        public Score from100(Float value) { return from(value, 100); }
        public Score from5(String value) { return from(value, 5); }
        public Score from10(String value) { return from(value, 10); }
        public Score from100(String value) { return from(value, 100); }

        private void warnOutOfRange(String operation, Object value, int maxScore) {
            com.lagradost.api.Log.INSTANCE.w(
                    "Score", operation + ": " + value + " ∉ [0, " + maxScore + "]");
        }

        private int roundToInt(double value) {
            if (Double.isNaN(value)) throw new IllegalArgumentException("Cannot round NaN value.");
            if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) Math.floor(value + 0.5d);
        }

        private int roundToInt(float value) {
            if (Float.isNaN(value)) throw new IllegalArgumentException("Cannot round NaN value.");
            return Math.round(value);
        }

        private String trimWhitespace(String value) {
            int start = 0;
            int end = value.length();
            while (start < end && isKotlinWhitespace(value.charAt(start))) start++;
            while (end > start && isKotlinWhitespace(value.charAt(end - 1))) end--;
            return value.substring(start, end);
        }

        private boolean isKotlinWhitespace(char value) {
            return Character.isWhitespace(value) || Character.isSpaceChar(value);
        }
    }

    /** Static Kotlin-source bridge matching Score.from5/from10/from100 call syntax. */
    public static Score from10(Integer value) { return Companion.from10(value); }
    public static Score from10(Double value) { return Companion.from10(value); }
    public static Score from10(Float value) { return Companion.from10(value); }
    public static Score from10(String value) { return Companion.from10(value); }
    public static Score from5(Integer value) { return Companion.from5(value); }
    public static Score from5(Double value) { return Companion.from5(value); }
    public static Score from5(Float value) { return Companion.from5(value); }
    public static Score from5(String value) { return Companion.from5(value); }
    public static Score from100(Integer value) { return Companion.from100(value); }
    public static Score from100(Double value) { return Companion.from100(value); }
    public static Score from100(Float value) { return Companion.from100(value); }
    public static Score from100(String value) { return Companion.from100(value); }
}
