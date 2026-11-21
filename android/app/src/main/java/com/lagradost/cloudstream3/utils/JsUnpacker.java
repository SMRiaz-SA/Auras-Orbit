package com.lagradost.cloudstream3.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orbit-authored bounded decoder for the common Dean Edwards P.A.C.K.E.R. format.
 * It parses the wrapper and substitutes dictionary tokens without evaluating JavaScript.
 * Radix behavior is pinned to CloudStream commit 3796406e636356f556c16e04e1da4e37d6a19038.
 * Radix decoding is capped at 95 and retains the bounded input and output limits below.
 * This clean-room compatibility implementation does not copy the cited cylonu87 body.
 */
public final class JsUnpacker {
    /** JVM-compatible counterpart to the pinned Kotlin companion object. */
    public static final Companion Companion = new Companion();

    static final int MAX_INPUT_CHARS = 2_000_000;
    private static final int MAX_PAYLOAD_CHARS = 512 * 1024;
    private static final int MAX_DICTIONARY_CHARS = 512 * 1024;
    private static final int MAX_DICTIONARY_COUNT = 20_000;
    private static final int MAX_DICTIONARY_WORD_CHARS = 8_192;
    private static final int MAX_OUTPUT_CHARS = 1_048_576;

    private static final Pattern PACKER_HEADER = Pattern.compile(
            "\\beval\\s*\\(\\s*function\\s*\\(\\s*p\\s*,\\s*a\\s*,\\s*c\\s*,\\s*k\\s*,\\s*e\\s*,\\s*[rd]\\s*\\)\\s*\\{");
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[A-Za-z0-9_]+\\b");

    /** Safe compatibility surface for Kotlin's JsUnpacker.Companion. */
    public static final class Companion {
        private static final List<Integer> C = Collections.unmodifiableList(Arrays.asList(
                0x63, 0x6f, 0x6d, 0x2e, 0x67, 0x6f, 0x6f, 0x67, 0x6c, 0x65, 0x2e, 0x61,
                0x6e, 0x64, 0x72, 0x6f, 0x69, 0x64, 0x2e, 0x67, 0x6d, 0x73, 0x2e, 0x61,
                0x64, 0x73, 0x2e, 0x4d, 0x6f, 0x62, 0x69, 0x6c, 0x65, 0x41, 0x64, 0x73
        ));
        private static final List<Integer> Z = Collections.unmodifiableList(Arrays.asList(
                0x63, 0x6f, 0x6d, 0x2e, 0x66, 0x61, 0x63, 0x65, 0x62, 0x6f, 0x6f, 0x6b,
                0x2e, 0x61, 0x64, 0x73, 0x2e, 0x41, 0x64
        ));

        private Companion() { }

        public final List<Integer> getC() { return C; }

        public final List<Integer> getZ() { return Z; }

        /**
         * Fixed compatibility lookup only. The receiver's data cannot select a class,
         * and false prevents class initialization or execution of target static code.
         */
        public final String load(String receiver) {
            if (receiver == null) throw new NullPointerException("receiver");
            String mobileAds = lookupWithoutInitialization("com.google.android.gms.ads.MobileAds");
            if (mobileAds != null) return mobileAds;
            return lookupWithoutInitialization("com.facebook.ads.Ad");
        }

        private static String lookupWithoutInitialization(String className) {
            try {
                return Class.forName(className, false, JsUnpacker.class.getClassLoader()).getName();
            } catch (ClassNotFoundException | LinkageError | SecurityException unavailable) {
                return null;
            }
        }
    }

    private final String packedJS;

    public JsUnpacker(String packedJS) {
        this.packedJS = packedJS;
    }

    /** Detects the common P.A.C.K.E.R wrapper header without executing it. */
    public boolean detect() {
        return packedJS != null && packedJS.length() <= MAX_INPUT_CHARS &&
                PACKER_HEADER.matcher(packedJS).find();
    }

    /** Returns unpacked JavaScript, or null for unsupported or malformed input. */
    public String unpack() {
        if (!detect()) return null;
        try {
            PackedArguments packed = parseArguments();
            if (packed == null) return null;
            return replaceDictionaryWords(packed);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private PackedArguments parseArguments() {
        Matcher header = PACKER_HEADER.matcher(packedJS);
        if (!header.find()) return null;
        int bodyClose = findFunctionBodyEnd(packedJS, header.end() - 1);
        if (bodyClose < 0) return null;

        Cursor cursor = new Cursor(packedJS, bodyClose + 1);
        cursor.expect('(');
        String payload = cursor.readQuotedString(MAX_PAYLOAD_CHARS);
        cursor.expect(',');
        int radix = cursor.readUnsignedInteger(95);
        cursor.expect(',');
        int count = cursor.readUnsignedInteger(MAX_DICTIONARY_COUNT);
        cursor.expect(',');
        String dictionaryText = cursor.readQuotedString(MAX_DICTIONARY_CHARS);
        if (radix < 2 || count < 1) {
            return null;
        }

        cursor.expect('.');
        cursor.expectIdentifier("split");
        cursor.expect('(');
        if (!"|".equals(cursor.readQuotedString(1))) return null;
        cursor.expect(')');

        List<String> dictionary = splitDictionary(dictionaryText, count);
        if (dictionary == null || dictionary.size() != count) return null;
        return new PackedArguments(payload, radix, count, dictionary);
    }

    private static List<String> splitDictionary(String value, int expectedCount) {
        if (expectedCount < 1 || expectedCount > MAX_DICTIONARY_COUNT) return null;
        ArrayList<String> words = new ArrayList<>(Math.min(expectedCount, 1024));
        int start = 0;
        while (true) {
            if (words.size() >= expectedCount) return null;
            int separator = value.indexOf('|', start);
            int end = separator < 0 ? value.length() : separator;
            if (end - start > MAX_DICTIONARY_WORD_CHARS) return null;
            words.add(value.substring(start, end));
            if (separator < 0) break;
            start = separator + 1;
        }
        return words.size() == expectedCount ? words : null;
    }

    private static String replaceDictionaryWords(PackedArguments packed) {
        Matcher words = WORD_PATTERN.matcher(packed.payload);
        StringBuilder output = new StringBuilder(Math.min(packed.payload.length(), MAX_OUTPUT_CHARS));
        int copiedThrough = 0;
        while (words.find()) {
            String token = words.group();
            int index = decodeIndex(token, packed.radix, packed.count);
            String replacement = index < 0 ? null : packed.dictionary.get(index);
            if (replacement != null && replacement.isEmpty()) replacement = null;

            String unchanged = replacement == null ? token : replacement;
            long nextLength = (long) output.length() + (words.start() - copiedThrough) + unchanged.length();
            if (nextLength > MAX_OUTPUT_CHARS) return null;
            output.append(packed.payload, copiedThrough, words.start());
            output.append(unchanged);
            copiedThrough = words.end();
        }

        long finalLength = (long) output.length() + packed.payload.length() - copiedThrough;
        if (finalLength > MAX_OUTPUT_CHARS) return null;
        output.append(packed.payload, copiedThrough, packed.payload.length());
        return output.toString();
    }

    private static int decodeIndex(String token, int radix, int count) {
        if (count <= 0 || radix < 2 || radix > 95) return -1;
        long value = 0;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            int digit;
            if (radix <= 36) {
                if (character >= '0' && character <= '9') {
                    digit = character - '0';
                } else if (character >= 'a' && character <= 'z') {
                    digit = character - 'a' + 10;
                } else if (character >= 'A' && character <= 'Z') {
                    // Base-36 digits are case-insensitive, as in the pinned decoder.
                    digit = character - 'A' + 10;
                } else {
                    return -1;
                }
            } else if (radix <= 62) {
                if (character >= '0' && character <= '9') {
                    digit = character - '0';
                } else if (character >= 'a' && character <= 'z') {
                    digit = character - 'a' + 10;
                } else if (character >= 'A' && character <= 'Z') {
                    // Extended base-62 order is 0-9, lowercase, then uppercase.
                    digit = character - 'A' + 36;
                } else {
                    return -1;
                }
            } else {
                // Base-95 uses printable ASCII U+0020..U+007E in code-point order.
                if (character < 0x20 || character > 0x7E) return -1;
                digit = character - 0x20;
            }

            if (digit >= radix || digit >= count) return -1;
            long maximumPrefix = (count - 1L - digit) / radix;
            if (value > maximumPrefix) return -1;
            value = value * radix + digit;
        }
        return value < count ? (int) value : -1;
    }

    /** Finds the function body's matching brace while ignoring quoted text and comments. */
    private static int findFunctionBodyEnd(String source, int openingBrace) {
        int depth = 1;
        char quote = 0;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int index = openingBrace + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;

            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (lineComment) {
                if (current == '\n' || current == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static final class PackedArguments {
        final String payload;
        final int radix;
        final int count;
        final List<String> dictionary;

        PackedArguments(String payload, int radix, int count, List<String> dictionary) {
            this.payload = payload;
            this.radix = radix;
            this.count = count;
            this.dictionary = dictionary;
        }
    }

    private static final class Cursor {
        private final String source;
        private int position;

        Cursor(String source, int position) {
            this.source = source;
            this.position = position;
        }

        void expect(char expected) {
            skipWhitespace();
            if (position >= source.length() || source.charAt(position) != expected) {
                throw new IllegalArgumentException("Malformed P.A.C.K.E.R. call");
            }
            position++;
        }

        void expectIdentifier(String expected) {
            skipWhitespace();
            if (!source.regionMatches(position, expected, 0, expected.length())) {
                throw new IllegalArgumentException("Malformed P.A.C.K.E.R. dictionary split");
            }
            position += expected.length();
        }

        int readUnsignedInteger(int maximum) {
            skipWhitespace();
            int start = position;
            int value = 0;
            while (position < source.length()) {
                char digit = source.charAt(position);
                if (digit < '0' || digit > '9') break;
                value = value * 10 + (digit - '0');
                if (value > maximum) throw new IllegalArgumentException("P.A.C.K.E.R. limit exceeded");
                position++;
            }
            if (position == start) throw new IllegalArgumentException("Missing P.A.C.K.E.R. integer");
            return value;
        }

        String readQuotedString(int maximumLength) {
            skipWhitespace();
            if (position >= source.length()) throw new IllegalArgumentException("Missing P.A.C.K.E.R. string");
            char quote = source.charAt(position++);
            if (quote != '\'' && quote != '"') throw new IllegalArgumentException("Expected quoted string");

            StringBuilder value = new StringBuilder();
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == quote) return value.toString();
                if (current == '\n' || current == '\r') throw new IllegalArgumentException("Invalid string newline");
                if (current != '\\') {
                    appendBounded(value, current, maximumLength);
                    continue;
                }
                if (position >= source.length()) throw new IllegalArgumentException("Incomplete string escape");
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case '\n':
                        break;
                    case '\r':
                        if (position < source.length() && source.charAt(position) == '\n') position++;
                        break;
                    case 'b': appendBounded(value, '\b', maximumLength); break;
                    case 'f': appendBounded(value, '\f', maximumLength); break;
                    case 'n': appendBounded(value, '\n', maximumLength); break;
                    case 'r': appendBounded(value, '\r', maximumLength); break;
                    case 't': appendBounded(value, '\t', maximumLength); break;
                    case 'v': appendBounded(value, '\u000B', maximumLength); break;
                    case '0': appendBounded(value, '\0', maximumLength); break;
                    case 'x': appendBounded(value, (char) readHexDigits(2), maximumLength); break;
                    case 'u': appendBounded(value, (char) readHexDigits(4), maximumLength); break;
                    default: appendBounded(value, escaped, maximumLength); break;
                }
            }
            throw new IllegalArgumentException("Unclosed P.A.C.K.E.R. string");
        }

        private static void appendBounded(StringBuilder value, char character, int maximumLength) {
            if (value.length() >= maximumLength) {
                throw new IllegalArgumentException("P.A.C.K.E.R. string limit exceeded");
            }
            value.append(character);
        }

        private int readHexDigits(int count) {
            if (source.length() - position < count) throw new IllegalArgumentException("Short hex escape");
            int value = 0;
            for (int index = 0; index < count; index++) {
                int digit = Character.digit(source.charAt(position++), 16);
                if (digit < 0) throw new IllegalArgumentException("Invalid hex escape");
                value = (value << 4) | digit;
            }
            return value;
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }
    }
}
