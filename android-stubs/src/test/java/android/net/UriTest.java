package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UriTest {
    @Test
    public void queryParametersSplitBeforeDecodingAndDecodeOnce() {
        Uri uri = Uri.parse(
                "https://example.test/?token=a%26b&encoded=%252F&dup=first&dup=second&plus=a+b&encodedPlus=a%2Bb");

        assertEquals("a&b", uri.getQueryParameter("token"));
        assertEquals("%2F", uri.getQueryParameter("encoded"));
        assertEquals("a b", uri.getQueryParameter("plus"));
        assertEquals("a+b", uri.getQueryParameters("plus").get(0));
        assertEquals("a+b", uri.getQueryParameter("encodedPlus"));
        assertEquals(2, uri.getQueryParameters("dup").size());
        assertEquals("first", uri.getQueryParameters("dup").get(0));
        assertEquals("second", uri.getQueryParameters("dup").get(1));
    }

    @Test
    public void queryParameterNamesAreDecoded() {
        Uri uri = Uri.parse("https://example.test/?na%6De=value");

        assertTrue(uri.getQueryParameterNames().contains("name"));
    }

    @Test
    public void queryParameterKeyEncodingMatchesAndroidLookupMethods() {
        Uri plusKey = Uri.parse("https://example.test/?some+key=value");
        assertEquals("value", plusKey.getQueryParameters("some key").get(0));
        assertNull(plusKey.getQueryParameter("some key"));
        assertTrue(plusKey.getQueryParameterNames().contains("some+key"));

        Uri escapedSpaceKey = Uri.parse("https://example.test/?some%20key=value");
        assertEquals("value", escapedSpaceKey.getQueryParameter("some key"));
    }
}
