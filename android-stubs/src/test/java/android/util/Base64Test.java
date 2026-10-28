package android.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class Base64Test {
    private static final byte[] EXPECTED = new byte[] {(byte) 0xfb, (byte) 0xff};

    @Test
    public void stringDecoderAcceptsWhitespaceForBothAlphabets() {
        assertArrayEquals(EXPECTED, Base64.decode(" \t+\n/8=\r ", Base64.DEFAULT));
        assertArrayEquals(EXPECTED, Base64.decode(" \t-\n_8=\r ", Base64.URL_SAFE));
    }

    @Test
    public void byteArrayDecoderAcceptsWhitespaceForBothAlphabets() {
        assertArrayEquals(EXPECTED, Base64.decode(" \t+\n/8=\r ".getBytes(StandardCharsets.US_ASCII), Base64.DEFAULT));
        assertArrayEquals(EXPECTED, Base64.decode(" \t-\n_8=\r ".getBytes(StandardCharsets.US_ASCII), Base64.URL_SAFE));
    }

    @Test
    public void decoderStillRejectsNonWhitespacePunctuation() {
        assertInvalid("YWJj!", Base64.DEFAULT);
        assertInvalid("YWJj!", Base64.URL_SAFE);
        assertInvalid("YWJj!".getBytes(StandardCharsets.US_ASCII), Base64.DEFAULT);
        assertInvalid("YWJj!".getBytes(StandardCharsets.US_ASCII), Base64.URL_SAFE);
    }

    private static void assertInvalid(String input, int flags) {
        try {
            Base64.decode(input, flags);
            fail("Expected invalid Base64 input to be rejected");
        } catch (IllegalArgumentException expected) {
            // Strict alphabet validation is intentional; only whitespace is ignored.
        }
    }

    private static void assertInvalid(byte[] input, int flags) {
        try {
            Base64.decode(input, flags);
            fail("Expected invalid Base64 input to be rejected");
        } catch (IllegalArgumentException expected) {
            // Strict alphabet validation is intentional; only whitespace is ignored.
        }
    }
}
