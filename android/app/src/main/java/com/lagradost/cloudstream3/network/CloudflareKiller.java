package com.lagradost.cloudstream3.network;

import com.lagradost.cloudstream3.MainAPIKt;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** CloudStream-compatible interceptor with host-scoped Cloudflare challenge cookies. */
public class CloudflareKiller implements Interceptor {
    public static final String TAG = "CloudflareKiller";
    public static final Companion Companion = new Companion();

    private static final int[] ERROR_CODES = {403, 503};
    private static final String[] CLOUDFLARE_SERVERS = {"cloudflare-nginx", "cloudflare"};

    private final Map<String, Map<String, String>> savedCookies = new ConcurrentHashMap<>();

    public CloudflareKiller() {
        super();
    }

    /** Kotlin MutableMap properties compile to a java.util.Map getter. */
    public Map<String, Map<String, String>> getSavedCookies() {
        return savedCookies;
    }

    /**
     * Returns the ordinary user-agent and any cookies explicitly present in savedCookies.
     * It reads only the cached WebView user-agent value; it does not create or navigate a WebView.
     */
    public Headers getCookieHeaders(String url) {
        Objects.requireNonNull(url, "url");

        Headers.Builder headers = new Headers.Builder()
                .add("user-agent", MainAPIKt.USER_AGENT);
        String cachedWebViewUserAgent = WebViewResolver.Companion.getWebViewUserAgent();
        if (cachedWebViewUserAgent != null) {
            headers.set("user-agent", cachedWebViewUserAgent);
        }

        String host = URI.create(url).getHost();
        Map<String, String> cookies = host == null ? null : savedCookies.get(host);
        if (cookies != null && !cookies.isEmpty()) {
            StringJoiner cookieHeader = new StringJoiner(" ");
            for (Map.Entry<String, String> cookie : cookies.entrySet()) {
                cookieHeader.add(cookie.getKey() + "=" + cookie.getValue() + ";");
            }
            headers.add("Cookie", cookieHeader.toString());
        }
        return headers.build();
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        Objects.requireNonNull(chain, "chain");
        Request request = chain.request();
        String host = request.url().host();

        Map<String, String> cookies = savedCookies.get(host);
        if (cookies != null) {
            return retryWithCookies(request, cookies);
        }

        Response response = chain.proceed(request);
        if (!isCloudflareFailure(response)) {
            return response;
        }

        response.close();
        Map<String, String> solvedCookies = CloudflareKillerWebViewBridge.resolve(
                request.url().toString(), host);
        if (solvedCookies != null && !solvedCookies.isEmpty()) {
            savedCookies.put(host, solvedCookies);
            return retryWithCookies(request, solvedCookies);
        }

        // No active Activity, a failed/canceled resolver, and an unsolved challenge all
        // retain the original interceptor behavior after releasing the first response.
        return chain.proceed(request);
    }

    private static boolean isCloudflareFailure(Response response) {
        boolean cloudflareServer = false;
        String server = response.header("Server");
        if (server != null) {
            for (String expected : CLOUDFLARE_SERVERS) {
                if (expected.equalsIgnoreCase(server)) {
                    cloudflareServer = true;
                    break;
                }
            }
        }
        if (!cloudflareServer) {
            return false;
        }
        for (int errorCode : ERROR_CODES) {
            if (response.code() == errorCode) {
                return true;
            }
        }
        return false;
    }

    private Response retryWithCookies(Request request, Map<String, String> cookies)
            throws IOException {
        Request.Builder retry = request.newBuilder();

        String cachedWebViewUserAgent = WebViewResolver.Companion.getWebViewUserAgent();
        if (cachedWebViewUserAgent != null) {
            retry.header("User-Agent", cachedWebViewUserAgent);
        }

        Map<String, String> mergedCookies = new LinkedHashMap<>(cookies);
        StringBuilder originalCookieHeader = new StringBuilder();
        for (String value : request.headers("Cookie")) {
            if (originalCookieHeader.length() > 0) {
                originalCookieHeader.append(';');
            }
            originalCookieHeader.append(value);
        }
        if (originalCookieHeader.length() > 0) {
            mergedCookies.putAll(Companion.parseCookieMap(originalCookieHeader.toString()));
        }

        if (!mergedCookies.isEmpty()) {
            StringJoiner cookieHeader = new StringJoiner("; ");
            for (Map.Entry<String, String> cookie : mergedCookies.entrySet()) {
                cookieHeader.add(cookie.getKey() + "=" + cookie.getValue());
            }
            retry.header("Cookie", cookieHeader.toString());
        }

        // The host-key lookup at the call site keeps these cookies scoped to this request host.
        // Request.newBuilder preserves the original method, body, and all other headers.
        return CloudflareKillerWebViewBridge.proceed(retry.build());
    }

    /** Kotlin callers may resolve companion functions through the outer class name. */
    public static Map<String, String> parseCookieMap(String cookie) {
        return Companion.parseCookieMap(cookie);
    }

    /** Companion method retained at the pinned Kotlin JVM owner and descriptor. */
    public static final class Companion {
        private Companion() {
        }

        public Map<String, String> parseCookieMap(String cookie) {
            Objects.requireNonNull(cookie, "cookie");
            Map<String, String> parsed = new LinkedHashMap<>();
            for (String part : cookie.split(";")) {
                int separator = part.indexOf('=');
                String key = (separator < 0 ? part : part.substring(0, separator)).trim();
                String value = separator < 0 || separator + 1 >= part.length()
                        ? ""
                        : part.substring(separator + 1).split("=", -1)[0].trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    parsed.put(key, value);
                }
            }
            return parsed;
        }
    }
}
