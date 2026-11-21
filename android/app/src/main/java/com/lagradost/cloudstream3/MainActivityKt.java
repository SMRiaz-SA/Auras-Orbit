package com.lagradost.cloudstream3;

import com.lagradost.nicehttp.Requests;
import com.lagradost.nicehttp.UtilsKt;
import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;

/**
 * Orbit-owned implementation of the pinned CloudStream MainActivityKt networking properties.
 *
 * <p>The default instances use bounded public-address DNS, verified TLS for {@code app},
 * the explicitly unsafe trust-all TLS mode for {@code insecureApp}, no ambient cookies,
 * finite request/redirect/body/time limits, and no proxy. As in upstream, the properties are
 * mutable and return mutable NiceHttp Requests objects. A plugin that replaces either property,
 * calls Requests.setBaseClient(), or explicitly requests verifySsl=false can replace/bypass the
 * host-owned transport policy. Orbit cannot wrap those mutations through NiceHttp's final
 * base-client setter; runtime acceptance must treat them as plugin-provided transport behavior.
 */
public final class MainActivityKt {
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    private static final long MAX_CALL_TIMEOUT_SECONDS = 30L;
    private static final long DNS_TIMEOUT_SECONDS = 5L;
    private static final int MAX_DNS_ADDRESSES = 16;
    private static final int MAX_REDIRECTS = 10;
    private static final int MAX_NETWORK_EXCHANGES = MAX_REDIRECTS + 1;
    private static final int MAX_URL_CHARS = 4096;
    private static final long MAX_REQUEST_BODY_BYTES = 1024L * 1024L;
    private static final long MAX_RESPONSE_BODY_BYTES = 2L * 1024L * 1024L;
    private static final String[] BLOCKED_HOST_SUFFIXES = {
            "localhost", "local", "localdomain", "internal", "test", "example", "invalid",
            "onion", "arpa", "home", "lan"
    };

    private static final ThreadPoolExecutor DNS_EXECUTOR = new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            new ThreadFactory() {
                private int index;

                @Override public synchronized Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "orbit-cs3-dns-" + (++index));
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final Map<okhttp3.Call, CallState> CALL_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Dns BOUNDED_PUBLIC_DNS = hostname -> lookupPublicAddresses(hostname);

    private static volatile Requests app;
    private static volatile Requests insecureApp;

    private MainActivityKt() { }

    /** Returns Orbit's bounded NiceHttp client with the pinned JSON parser and user agent. */
    public static Requests getApp() {
        Requests current = app;
        if (current != null) return current;
        synchronized (MainActivityKt.class) {
            if (app == null) app = createDefaultRequests(false);
            return app;
        }
    }

    /**
     * Preserves the upstream mutable Kotlin property setter. A caller-provided Requests object is
     * stored as-is for source/runtime compatibility; its transport is outside Orbit's guarantees.
     */
    public static void setApp(Requests value) {
        app = Objects.requireNonNull(value, "app");
    }

    /**
     * Returns the explicitly unsafe-TLS CloudStream client. Its default transport retains Orbit's
     * DNS, request, redirect, body, cookie, and time bounds; only certificate verification differs.
     */
    public static Requests getInsecureApp() {
        Requests current = insecureApp;
        if (current != null) return current;
        synchronized (MainActivityKt.class) {
            if (insecureApp == null) insecureApp = createDefaultRequests(true);
            return insecureApp;
        }
    }

    /**
     * Preserves the upstream mutable Kotlin property setter. A caller-provided Requests object is
     * stored as-is; Orbit cannot apply its transport bounds to plugin-provided replacements.
     */
    public static void setInsecureApp(Requests value) {
        insecureApp = Objects.requireNonNull(value, "insecureApp");
    }

    private static Requests createDefaultRequests(boolean ignoreCertificateErrors) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .dns(BOUNDED_PUBLIC_DNS)
                .cookieJar(CookieJar.NO_COOKIES)
                .connectionPool(new ConnectionPool())
                .proxy(Proxy.NO_PROXY)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(MAX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(MAX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(MAX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(MAX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .addInterceptor(new RequestBoundsInterceptor())
                .addNetworkInterceptor(new ExchangeBoundsInterceptor());
        if (ignoreCertificateErrors) {
            // Mirrors the pinned @UnsafeSSL property contract; callers must opt into this property.
            UtilsKt.ignoreAllSSLErrors(builder);
        }

        Requests requests = new Requests();
        requests.setBaseClient(builder.build());
        requests.setDefaultHeaders(Collections.singletonMap("user-agent", MainAPIKt.USER_AGENT));
        requests.setResponseParser(com.lagradost.cloudstream3.network.OrbitCs3ResponseParser.INSTANCE);
        requests.setDefaultTimeOut(DEFAULT_TIMEOUT_SECONDS);
        return requests;
    }

    private static final class RequestBoundsInterceptor implements Interceptor {
        @Override public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            validateRequestUrl(request.url());

            okhttp3.Call call = chain.call();
            long currentTimeout = call.timeout().timeoutNanos();
            long maxTimeout = TimeUnit.SECONDS.toNanos(MAX_CALL_TIMEOUT_SECONDS);
            if (currentTimeout <= 0L || currentTimeout > maxTimeout) {
                call.timeout().timeout(MAX_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            RequestBody body = request.body();
            if (body != null) {
                long contentLength = body.contentLength();
                if (contentLength > MAX_REQUEST_BODY_BYTES || body.isDuplex()) {
                    throw new IOException("CS3 HTTP request body is outside Orbit's request bounds");
                }
                request = request.newBuilder().method(request.method(), boundedRequestBody(body)).build();
            }
            Response response = chain.proceed(request);
            try {
                // Application interceptors see BridgeInterceptor's decompressed response body.
                return boundedResponse(response);
            } catch (IOException bodyLimit) {
                response.close();
                throw bodyLimit;
            }
        }
    }

    private static RequestBody boundedRequestBody(RequestBody body) {
        return new RequestBody() {
            @Override public MediaType contentType() {
                return body.contentType();
            }

            @Override public long contentLength() throws IOException {
                return body.contentLength();
            }

            @Override public boolean isOneShot() {
                return body.isOneShot();
            }

            @Override public boolean isDuplex() {
                return body.isDuplex();
            }

            @Override public void writeTo(BufferedSink sink) throws IOException {
                BufferedSink boundedSink = Okio.buffer(new ForwardingSink(sink) {
                    private long written;

                    @Override public void write(Buffer source, long byteCount) throws IOException {
                        if (byteCount < 0L || byteCount > MAX_REQUEST_BODY_BYTES - written) {
                            throw new IOException("CS3 HTTP request body limit reached");
                        }
                        written += byteCount;
                        super.write(source, byteCount);
                    }
                });
                body.writeTo(boundedSink);
                boundedSink.flush();
            }
        };
    }

    private static final class ExchangeBoundsInterceptor implements Interceptor {
        @Override public Response intercept(Chain chain) throws IOException {
            okhttp3.Call call = chain.call();
            Request request = chain.request();
            // Revalidate each network exchange because OkHttp redirects bypass application
            // interceptors while re-entering the network interceptor chain.
            validateRequestUrl(request.url());
            CallState state = stateFor(call, request.url());
            int exchanges = state.incrementAndGet();
            if (exchanges > MAX_NETWORK_EXCHANGES) {
                CALL_STATES.remove(call);
                throw new IOException("CS3 HTTP request/redirect limit reached");
            }

            if ("https".equals(state.origin.scheme) && !request.url().isHttps()) {
                CALL_STATES.remove(call);
                throw new IOException("CS3 HTTPS redirect cannot downgrade to HTTP");
            }

            Request originBoundRequest = removeSensitiveCrossOriginHeaders(request, state.origin);
            Response response;
            try {
                response = chain.proceed(originBoundRequest);
            } catch (IOException | RuntimeException failure) {
                CALL_STATES.remove(call);
                throw failure;
            }

            boolean redirect = isRedirect(response.code()) && response.header("Location") != null;
            if (redirect && exchanges >= MAX_NETWORK_EXCHANGES) {
                response.close();
                CALL_STATES.remove(call);
                throw new IOException("CS3 HTTP redirect limit reached");
            }
            if (!redirect) CALL_STATES.remove(call);
            try {
                return boundedResponse(response);
            } catch (IOException bodyLimit) {
                CALL_STATES.remove(call);
                response.close();
                throw bodyLimit;
            }
        }
    }

    private static CallState stateFor(okhttp3.Call call, HttpUrl firstUrl) {
        synchronized (CALL_STATES) {
            CallState state = CALL_STATES.get(call);
            if (state == null) {
                state = new CallState(firstUrl);
                CALL_STATES.put(call, state);
            }
            return state;
        }
    }

    private static Request removeSensitiveCrossOriginHeaders(Request request, Origin original) {
        HttpUrl url = request.url();
        if (original.matches(url)) return request;
        Request.Builder builder = request.newBuilder();
        for (int i = 0; i < request.headers().size(); i++) {
            String name = request.headers().name(i);
            String normalized = name.toLowerCase(Locale.ROOT);
            if (isSensitiveHeaderName(normalized)) builder.removeHeader(name);
        }
        return builder.build();
    }

    private static boolean isSensitiveHeaderName(String normalizedName) {
        return normalizedName.contains("authorization") ||
                normalizedName.contains("cookie") ||
                normalizedName.contains("token") ||
                normalizedName.contains("api-key") ||
                normalizedName.contains("apikey") ||
                normalizedName.contains("secret") ||
                normalizedName.contains("credential") ||
                normalizedName.contains("password");
    }

    private static boolean isRedirect(int code) {
        return code == 300 || code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static void validateRequestUrl(HttpUrl url) throws IOException {
        String rawUrl = url.toString();
        if (rawUrl.length() > MAX_URL_CHARS ||
                (!"http".equals(url.scheme()) && !"https".equals(url.scheme())) ||
                !url.username().isEmpty() || !url.password().isEmpty()) {
            throw new IOException("CS3 HTTP URL is outside Orbit's request bounds");
        }
    }

    static Response boundedResponse(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return response;
        long contentLength = body.contentLength();
        if (contentLength > MAX_RESPONSE_BODY_BYTES) {
            body.close();
            throw new IOException("CS3 HTTP response body limit reached");
        }
        BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
            private long readBytes;

            @Override public long read(Buffer sink, long byteCount) throws IOException {
                if (byteCount == 0L) return 0L;
                long remaining = MAX_RESPONSE_BODY_BYTES - readBytes;
                Buffer temporary = new Buffer();
                long count = super.read(temporary, Math.min(byteCount, remaining + 1L));
                if (count == -1L) return -1L;
                if (count > remaining) {
                    throw new IOException("CS3 HTTP response body limit reached");
                }
                readBytes += count;
                sink.write(temporary, count);
                return count;
            }
        });
        ResponseBody boundedBody = new ResponseBody() {
            @Override public MediaType contentType() {
                return body.contentType();
            }

            @Override public long contentLength() {
                return body.contentLength();
            }

            @Override public BufferedSource source() {
                return source;
            }
        };
        return response.newBuilder().body(boundedBody).build();
    }

    private static List<InetAddress> lookupPublicAddresses(String rawHostname) throws UnknownHostException {
        String host = rawHostname == null ? "" : rawHostname.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || host.endsWith(".") || host.indexOf('%') >= 0 ||
                (host.indexOf('.') < 0 && host.indexOf(':') < 0) || hasBlockedSuffix(host)) {
            throw new UnknownHostException("CS3 HTTP target is not a public host");
        }
        if (host.indexOf(':') < 0) {
            try {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            } catch (IllegalArgumentException invalid) {
                UnknownHostException failure = new UnknownHostException("CS3 HTTP target is invalid");
                failure.initCause(invalid);
                throw failure;
            }
        }
        if (host.length() > 253 || hasBlockedSuffix(host)) {
            throw new UnknownHostException("CS3 HTTP target is not a public host");
        }

        final String lookupHost = host;
        Future<List<InetAddress>> future;
        try {
            future = DNS_EXECUTOR.submit(() -> resolveAndValidate(lookupHost));
        } catch (RuntimeException rejected) {
            UnknownHostException failure = new UnknownHostException("CS3 DNS capacity is exhausted");
            failure.initCause(rejected);
            throw failure;
        }
        try {
            return future.get(DNS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            UnknownHostException failure = new UnknownHostException("CS3 DNS lookup timed out");
            failure.initCause(timeout);
            throw failure;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            UnknownHostException failure = new UnknownHostException("CS3 DNS lookup was interrupted");
            failure.initCause(interrupted);
            throw failure;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            UnknownHostException failure = new UnknownHostException("CS3 target did not resolve to public IPs");
            if (cause != null) failure.initCause(cause);
            throw failure;
        }
    }

    private static List<InetAddress> resolveAndValidate(String host) throws UnknownHostException {
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0 || resolved.length > MAX_DNS_ADDRESSES) {
            throw new UnknownHostException("CS3 target returned an invalid number of addresses");
        }
        List<InetAddress> addresses = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            if (!isPublicAddress(address)) {
                throw new UnknownHostException("CS3 target resolved to a non-public address");
            }
            addresses.add(address);
        }
        return Collections.unmodifiableList(addresses);
    }

    private static boolean hasBlockedSuffix(String host) {
        for (String suffix : BLOCKED_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address || bytes.length == 4) return isPublicIpv4(bytes);
        if (!(address instanceof Inet6Address) || bytes.length != 16) return false;

        if (isIpv4MappedIpv6(bytes)) {
            try {
                byte[] ipv4 = new byte[4];
                System.arraycopy(bytes, 12, ipv4, 0, ipv4.length);
                return isPublicIpv4(ipv4);
            } catch (RuntimeException malformed) {
                return false;
            }
        }

        int first = bytes[0] & 0xff;
        if ((first & 0xe0) != 0x20) return false; // global-unicast 2000::/3 only
        if (matchesCidr(bytes, new int[] {0x20, 0x01, 0x00, 0x10}, 28) ||
                matchesCidr(bytes, new int[] {0x20, 0x01, 0x00, 0x20}, 28)) return false;
        return !matchesCidr(bytes, new int[] {0x20, 0x01, 0x00}, 23) &&
                !matchesCidr(bytes, new int[] {0x20, 0x01, 0x0d, 0xb8}, 32) &&
                !matchesCidr(bytes, new int[] {0x20, 0x02}, 16) &&
                !matchesCidr(bytes, new int[] {0x3f, 0xff, 0x00}, 20);
    }

    private static boolean isIpv4MappedIpv6(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        if (bytes.length != 4) return false;
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        int c = bytes[2] & 0xff;
        if (matchesCidr(bytes, new int[] {192, 31, 196}, 24) ||
                matchesCidr(bytes, new int[] {192, 52, 193}, 24) ||
                matchesCidr(bytes, new int[] {192, 175, 48}, 24)) return false;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && (b == 0 || b == 168)) return false;
        if (a == 192 && b == 88 && c == 99) return false;
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
        return !(a == 203 && b == 0 && c == 113);
    }

    private static boolean matchesCidr(byte[] address, int[] prefix, int prefixLength) {
        if (prefixLength < 0 || prefixLength > address.length * 8 || prefixLength > prefix.length * 8) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if ((address[i] & 0xff) != prefix[i]) return false;
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) return true;
        int mask = (0xff << (8 - remainingBits)) & 0xff;
        return ((address[fullBytes] & 0xff) & mask) == (prefix[fullBytes] & mask);
    }

    private static final class CallState {
        final Origin origin;
        int exchanges;

        CallState(HttpUrl url) {
            origin = new Origin(url);
        }

        synchronized int incrementAndGet() {
            return ++exchanges;
        }
    }

    private static final class Origin {
        final String scheme;
        final String host;
        final int port;

        Origin(HttpUrl url) {
            scheme = url.scheme();
            host = url.host();
            port = url.port();
        }

        boolean matches(HttpUrl url) {
            return scheme.equals(url.scheme()) && host.equalsIgnoreCase(url.host()) && port == url.port();
        }
    }
}
