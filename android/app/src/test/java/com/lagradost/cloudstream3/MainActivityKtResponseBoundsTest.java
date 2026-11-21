package com.lagradost.cloudstream3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Test;

public final class MainActivityKtResponseBoundsTest {
    @Test public void readsUnknownLengthMetadataResponseLargerThan512KiB() throws Exception {
        byte[] payload = bytesOfSize(1024 * 1024);
        Response response = unknownLengthResponse(payload);
        Response bounded = MainActivityKt.boundedResponse(response);

        byte[] actual;
        try {
            actual = bounded.body().bytes();
        } finally {
            bounded.close();
        }

        assertEquals(payload.length, actual.length);
        assertEquals(payload[0], actual[0]);
        assertEquals(payload[payload.length - 1], actual[actual.length - 1]);
    }

    @Test public void rejectsUnknownLengthResponseLargerThanTwoMiB() throws Exception {
        Response response = unknownLengthResponse(bytesOfSize(2 * 1024 * 1024 + 1));
        Response bounded = MainActivityKt.boundedResponse(response);

        try {
            assertThrows(IOException.class, () -> bounded.body().bytes());
        } finally {
            bounded.close();
        }
    }

    private static Response unknownLengthResponse(byte[] payload) {
        BufferedSource source = new Buffer().write(payload);
        ResponseBody body = new ResponseBody() {
            @Override public okhttp3.MediaType contentType() {
                return null;
            }

            @Override public long contentLength() {
                return -1L;
            }

            @Override public BufferedSource source() {
                return source;
            }
        };
        return new Response.Builder()
                .request(new Request.Builder().url("https://example.com/details").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build();
    }

    private static byte[] bytesOfSize(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) i;
        return bytes;
    }
}
