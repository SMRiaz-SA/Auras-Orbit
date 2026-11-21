package com.lagradost.cloudstream3.extractors.helper;

import com.lagradost.cloudstream3.MainAPIKt;
import com.lagradost.cloudstream3.MainActivityKt;
import com.lagradost.cloudstream3.mvvm.Resource;
import com.lagradost.cloudstream3.utils.ExtractorApiKt;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.M3u8Helper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.DisposableHandle;
import kotlinx.coroutines.Job;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Source-shaped implementation of CloudStream's Gogo Vidstream helper. */
public class GogoHelper {
    private static final long REQUEST_TIMEOUT_SECONDS = 8L;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HTTP_REQUESTS = 8;
    private static final long REQUEST_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EXTRA_QUERY_PARAMS = 32;
    private static final int MAX_EXTRA_QUERY_CHARS = 16 * 1024;
    // Aggregate callback limit across both source arrays, after HLS expansion.
    private static final int MAX_SOURCE_ITEMS = 64;
    private static final String CAPTCHA_MESSAGE =
            "The Gogo host requires CAPTCHA or anti-bot verification; Orbit has no CAPTCHA service configured.";
    private static final Pattern ID_PARAMETER = Pattern.compile("id=([^&]+)");
    private static final AtomicInteger DNS_THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor DNS_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "OrbitGogoDns-" + DNS_THREAD_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            }, new ThreadPoolExecutor.AbortPolicy()
    );

    private GogoHelper() { super(); }
    public static final GogoHelper INSTANCE = new GogoHelper();

    /**
     * Mirrors the pinned suspend helper. Page and AJAX requests are restricted
     * to the original public HTTPS origin and use a DNS-pinned Orbit client.
     * CAPTCHA responses are returned as safe Resource failures because no
     * CAPTCHA provider is configured by the host.
     */
    public Object extractVidstream(
            final String iframeUrl,
            final String mainApiName,
            final Function1<? super ExtractorLink, Unit> callback,
            final String iv,
            final String secretKey,
            final String secretDecryptKey,
            final boolean isUsingAdaptiveKeys,
            final boolean isUsingAdaptiveData,
            final Document iframeDocument,
            final Continuation<? super Resource<Unit>> continuation
    ) {
        Objects.requireNonNull(continuation, "continuation");
        CoroutineContext context = continuation.getContext().plus(Dispatchers.getIO());
        CoroutineScope scope = CoroutineScopeKt.CoroutineScope(context);
        final AtomicBoolean continuationResumed = new AtomicBoolean(false);
        Job launchedJob = BuildersKt.launch(scope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
                new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                    @Override public Object invoke(CoroutineScope scopeValue,
                                                   Continuation<? super Unit> ignored) {
                        Object outcome;
                        Job job = (Job) scopeValue.getCoroutineContext().get(Job.Key);
                        try {
                            Objects.requireNonNull(callback, "callback");
                            extract(iframeUrl, mainApiName, callback, iv, secretKey,
                                    secretDecryptKey, isUsingAdaptiveKeys, isUsingAdaptiveData,
                                    iframeDocument, job);
                            outcome = new Resource.Success<>(Unit.INSTANCE);
                        } catch (Throwable failure) {
                            outcome = failureOutcome(failure, job);
                        }
                        resumeContinuationOnce(continuation, continuationResumed, outcome);
                        return Unit.INSTANCE;
                    }
                });
        launchedJob.invokeOnCompletion(true, true, new Function1<Throwable, Unit>() {
            @Override public Unit invoke(Throwable cause) {
                Object outcome = cause == null
                        ? new Resource.Success<>(Unit.INSTANCE)
                        : failureOutcome(cause, null);
                resumeContinuationOnce(continuation, continuationResumed, outcome);
                return Unit.INSTANCE;
            }
        });
        return IntrinsicsKt.getCOROUTINE_SUSPENDED();
    }

    private static Object failureOutcome(Throwable failure, Job job) {
        if (failure instanceof CaptchaChallengeException) {
            return new Resource.Failure(false, CAPTCHA_MESSAGE);
        }
        if (failure instanceof java.util.concurrent.CancellationException ||
                (job != null && !job.isActive())) {
            Throwable cancellation = failure instanceof java.util.concurrent.CancellationException
                    ? failure
                    : new java.util.concurrent.CancellationException("Gogo extraction cancelled");
            return ResultKt.createFailure(cancellation);
        }
        try {
            com.lagradost.cloudstream3.mvvm.ArchComponentExtKt.logError(failure);
            return com.lagradost.cloudstream3.mvvm.ArchComponentExtKt.throwAbleToResource(failure);
        } catch (Throwable mappedFailure) {
            return ResultKt.createFailure(mappedFailure);
        }
    }

    private static void resumeContinuationOnce(Continuation<? super Resource<Unit>> continuation,
                                              AtomicBoolean continuationResumed,
                                              Object outcome) {
        if (!continuationResumed.compareAndSet(false, true)) return;
        try {
            continuation.resumeWith(outcome);
        } catch (Throwable ignoredResumeFailure) {
            // A caller may cancel while a bounded blocking request is unwinding.
        }
    }

    private static void extract(
            String iframeUrl, String mainApiName, Function1<? super ExtractorLink, Unit> callback,
            String iv, String secretKey, String secretDecryptKey,
            boolean useAdaptiveKeys, boolean useAdaptiveData,
            Document iframeDocument, Job job
    ) throws Exception {
        if ((iv == null || secretKey == null || secretDecryptKey == null) && !useAdaptiveKeys) {
            return;
        }

        Matcher idMatch = ID_PARAMETER.matcher(iframeUrl == null ? "" : iframeUrl);
        if (!idMatch.find()) throw new IllegalArgumentException("Gogo iframe URL has no media id");
        String id = idMatch.group(1);
        RequestContext requestContext = new RequestContext(iframeUrl, job);
        Document document = iframeDocument;
        String foundIv = iv;
        if (foundIv == null) {
            if (document == null) {
                document = Jsoup.parse(requestContext.getText(iframeUrl, Collections.emptyMap()), iframeUrl);
            }
            String className = document.select("div.wrapper[class*=container]").attr("class");
            int lastDash = className.lastIndexOf('-');
            foundIv = lastDash >= 0 ? className.substring(lastDash + 1) : className;
            if (foundIv.isEmpty()) return;
        }

        String foundKey = secretKey;
        if (foundKey == null) {
            foundKey = getEncryptionKey(MainAPIKt.base64Decode(id) + foundIv);
            if (foundKey == null) return;
        }
        String foundDecryptKey = secretDecryptKey == null ? foundKey : secretDecryptKey;

        String requestData = "id=" + urlEncode(cryptoHandler(id, foundIv, foundKey, true))
                + "&alias=" + urlEncode(id);
        if (useAdaptiveData) {
            if (document == null) {
                document = Jsoup.parse(requestContext.getText(iframeUrl, Collections.emptyMap()), iframeUrl);
            }
            String encryptedData = document.select("script[data-name='episode']")
                    .attr("data-value");
            String decryptedHeaders = cryptoHandler(encryptedData, foundIv, foundKey, false);
            int firstAmpersand = decryptedHeaders.indexOf('&');
            String extraQuery = firstAmpersand < 0
                    ? decryptedHeaders : decryptedHeaders.substring(firstAmpersand + 1);
            requestData += appendSafeQuery(extraQuery);
        }

        HttpUrl ajaxUrl = HttpUrl.get(requestContext.mainUrl + "/encrypt-ajax.php?" + requestData);
        if (!requestContext.sameOrigin(ajaxUrl)) throw new IOException("Gogo AJAX URL left its origin");
        String ajaxBody = requestContext.getText(ajaxUrl.toString(),
                Collections.singletonMap("X-Requested-With", "XMLHttpRequest"));

        JSONObject json;
        try {
            json = new JSONObject(ajaxBody);
        } catch (JSONException malformedResponse) {
            return; // Mirrors parsedSafe<GogoJsonData>() returning no data.
        }
        Object encryptedSources = json.opt("data");
        if (!(encryptedSources instanceof String)) return;
        String sourceJson = cryptoHandler((String) encryptedSources, foundIv, foundDecryptKey, false);
        JSONObject sources = new JSONObject(sourceJson);
        int emitted = emitSources(sources.optJSONArray("source"), mainApiName,
                requestContext.mainUrl, callback, job, 0);
        emitSources(sources.optJSONArray("sourceBk"), mainApiName,
                requestContext.mainUrl, callback, job, emitted);
    }

    private static int emitSources(JSONArray sources, String apiName, String mainUrl,
                                   Function1<? super ExtractorLink, Unit> callback,
                                   Job job, int emitted) throws Exception {
        if (sources == null || emitted >= MAX_SOURCE_ITEMS) return emitted;
        for (int index = 0;
             index < Math.min(sources.length(), MAX_SOURCE_ITEMS) && emitted < MAX_SOURCE_ITEMS;
             index++) {
            ensureActive(job);
            JSONObject source = sources.optJSONObject(index);
            if (source == null) continue;
            String file = source.optString("file", "");
            if (file.isEmpty() || "null".equals(file)) continue;
            String label = source.isNull("label") ? null : source.optString("label", null);
            if (file.contains(".m3u8")) {
                Map<String, String> headers = Collections.singletonMap("Origin", "https://plyr.link");
                List<? extends ExtractorLink> links = generateM3u8(apiName, file, mainUrl, headers, job);
                for (ExtractorLink link : links) {
                    if (emitted >= MAX_SOURCE_ITEMS) break;
                    ensureActive(job);
                    callback.invoke(link);
                    emitted++;
                }
            } else {
                emitted += emitValidatedLink(apiName, file, mainUrl, label, callback,
                        MAX_SOURCE_ITEMS - emitted, job);
            }
        }
        return emitted;
    }

    private static List<? extends ExtractorLink> generateM3u8(
            final String apiName, final String url, final String referer,
            final Map<String, String> headers, Job job
    ) throws Exception {
        List<? extends ExtractorLink> value = runBlockingOnIO(
                new Function2<CoroutineScope,
                        Continuation<? super List<? extends ExtractorLink>>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                    Continuation<? super List<? extends ExtractorLink>> continuation) {
                return M3u8Helper.Companion.generateM3u8(
                        apiName, url, referer, null, headers, apiName, continuation);
            }
        }, job);
        return value;
    }

    private static int emitValidatedLink(
            final String apiName, final String url, final String referer,
            final String label, final Function1<? super ExtractorLink, Unit> callback,
            final int remaining,
            Job job
    ) throws Exception {
        final AtomicBoolean forwarded = new AtomicBoolean(false);
        Function1<ExtractorLink, Unit> setQualityAndForward = new Function1<ExtractorLink, Unit>() {
            @Override public Unit invoke(ExtractorLink link) {
                if (!forwarded.compareAndSet(false, true)) return Unit.INSTANCE;
                link.setQuality(ExtractorApiKt.getQualityFromName(label));
                callback.invoke(link);
                return Unit.INSTANCE;
            }
        };
        runBlockingOnIO(new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
            @Override public Object invoke(CoroutineScope scope,
                                          Continuation<? super Unit> continuation) {
                return ExtractorApiKt.emitValidatedExtractorLink(
                        apiName, apiName, url, referer, setQualityAndForward,
                        continuation);
            }
        }, job);
        return forwarded.get() ? Math.min(1, remaining) : 0;
    }

    private static <T> T runBlockingOnIO(
            Function2<? super CoroutineScope, ? super Continuation<? super T>, ? extends Object> block,
            Job job) throws Exception {
        final Thread worker = Thread.currentThread();
        final InterruptOnCancellation interrupt = new InterruptOnCancellation(worker);
        DisposableHandle cancellation = job == null ? null : job.invokeOnCompletion(true, true,
                new Function1<Throwable, Unit>() {
                    @Override public Unit invoke(Throwable cause) {
                        if (cause != null) interrupt.interrupt();
                        return Unit.INSTANCE;
                    }
                });
        try {
            ensureActive(job);
            return BuildersKt.runBlocking(Dispatchers.getIO(), block);
        } catch (InterruptedException interrupted) {
            if (job != null && !job.isActive()) {
                throw new java.util.concurrent.CancellationException("Gogo extraction cancelled");
            }
            throw interrupted;
        } finally {
            interrupt.finish();
            if (cancellation != null) cancellation.dispose();
        }
    }

    /** Serializes cancellation interrupts against worker cleanup and pool reuse. */
    private static final class InterruptOnCancellation {
        private final Thread worker;
        private boolean finished;
        private boolean interrupted;

        InterruptOnCancellation(Thread worker) {
            this.worker = worker;
        }

        synchronized void interrupt() {
            if (finished) return;
            interrupted = true;
            worker.interrupt();
        }

        synchronized void finish() {
            finished = true;
            if (interrupted) Thread.interrupted();
        }
    }

    private static String getEncryptionKey(String value) {
        StringBuilder hex = new StringBuilder(value.length() * 2);
        for (int index = 0; index < value.length(); index++) {
            hex.append(Integer.toString(value.charAt(index), 16));
        }
        return hex.length() < 32 ? null : hex.substring(0, 32);
    }

    private static String cryptoHandler(String value, String iv, String key, boolean encrypt)
            throws GeneralSecurityException {
        byte[] ivBytes = iv.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
        byte[] input = encrypt
                ? value.getBytes(StandardCharsets.UTF_8)
                : MainAPIKt.base64DecodeArray(value);
        byte[] result = cipher.doFinal(input);
        return encrypt
                ? MainAPIKt.base64Encode(result)
                : new String(result, StandardCharsets.UTF_8);
    }

    private static String urlEncode(String value) {
        return android.net.Uri.encode(value);
    }

    private static String appendSafeQuery(String query) throws IOException {
        if (query == null || query.isEmpty()) return "";
        if (query.length() > MAX_EXTRA_QUERY_CHARS || query.indexOf('\r') >= 0 ||
                query.indexOf('\n') >= 0 || query.indexOf('#') >= 0) {
            throw new IOException("Gogo response query is outside supported bounds");
        }
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (String part : query.split("&", -1)) {
            if (part.isEmpty()) continue;
            if (++count > MAX_EXTRA_QUERY_PARAMS) {
                throw new IOException("Gogo response has too many query parameters");
            }
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            if (name.isEmpty() || name.length() > 1024 || value.length() > 4096) {
                throw new IOException("Gogo response query parameter is outside supported bounds");
            }
            result.append('&').append(urlEncode(decodeQueryComponent(name)))
                    .append('=').append(urlEncode(decodeQueryComponent(value)));
        }
        return result.toString();
    }

    private static String decodeQueryComponent(String value) throws IOException {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception malformed) {
            throw new IOException("Gogo response query is malformed");
        }
    }

    private static void ensureActive(Job job) {
        if (job != null && !job.isActive()) {
            throw new java.util.concurrent.CancellationException("Gogo extraction cancelled");
        }
    }

    private static final class RequestContext {
        private final String host;
        private final String mainUrl;
        private final List<InetAddress> addresses;
        private final Job job;
        private final OkHttpClient client;
        private final IsolationCookieJar cookieJar = new IsolationCookieJar();
        private final long deadlineNanos = System.nanoTime() + REQUEST_DEADLINE_NANOS;
        private int requestCount;

        RequestContext(String iframeUrl, Job job) throws Exception {
            HttpUrl url = parseHttpsUrl(iframeUrl);
            host = url.host();
            mainUrl = "https://" + host;
            this.job = job;
            addresses = resolvePublicAddresses(host, job);
            Dns pinnedDns = new Dns() {
                @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                    if (host.equalsIgnoreCase(hostname)) return addresses;
                    throw new UnknownHostException("Gogo request tried to resolve another host");
                }
            };
            client = MainActivityKt.getApp().getBaseClient().newBuilder()
                    .dns(pinnedDns)
                    .cookieJar(cookieJar)
                    .connectionPool(new ConnectionPool())
                    .proxy(Proxy.NO_PROXY)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
        }

        boolean sameOrigin(HttpUrl url) {
            return url != null && url.isHttps() && url.port() == 443 &&
                    host.equals(url.host()) && url.username().isEmpty() && url.password().isEmpty();
        }

        String getText(String initialUrl, Map<String, String> headers) throws Exception {
            HttpUrl current = parseHttpsUrl(initialUrl);
            if (!sameOrigin(current)) throw new IOException("Gogo request left its original origin");
            for (int redirects = 0; ; redirects++) {
                ensureActive(job);
                if (++requestCount > MAX_HTTP_REQUESTS) {
                    throw new IOException("Gogo request limit reached");
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) throw new IOException("Gogo request deadline reached");
                Request.Builder builder = new Request.Builder().url(current).get()
                        .header("User-Agent", MainAPIKt.USER_AGENT);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
                Call call = client.newCall(builder.build());
                DisposableHandle cancellation = job == null ? null : job.invokeOnCompletion(true, true,
                        new Function1<Throwable, Unit>() {
                            @Override public Unit invoke(Throwable cause) {
                                if (cause != null) call.cancel();
                                return Unit.INSTANCE;
                            }
                        });
                call.timeout().timeout(
                        Math.min(remainingNanos, TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS)),
                        TimeUnit.NANOSECONDS);
                try (Response response = call.execute()) {
                    int code = response.code();
                    if (code >= 300 && code < 400) {
                        if (redirects >= MAX_REDIRECTS) throw new IOException("Gogo redirect limit reached");
                        String location = response.header("Location");
                        HttpUrl next = location == null ? null : current.resolve(location);
                        if (!sameOrigin(next)) throw new IOException("Gogo redirect left its original origin");
                        current = next;
                        continue;
                    }
                    ResponseBody body = response.body();
                    String text = body == null ? "" : readBounded(body, job);
                    if (isChallenge(code, text)) throw new CaptchaChallengeException();
                    if (code < 200 || code >= 300) throw new IOException("Gogo request failed");
                    return text;
                } finally {
                    if (cancellation != null) cancellation.dispose();
                }
            }
        }
    }

    private static HttpUrl parseHttpsUrl(String rawUrl) throws IOException {
        if (rawUrl == null || rawUrl.length() > 4096 || !rawUrl.equals(rawUrl.trim())) {
            throw new IOException("Gogo URL is invalid");
        }
        HttpUrl url = HttpUrl.parse(rawUrl);
        if (url == null || !url.isHttps() || url.port() != 443 ||
                !url.username().isEmpty() || !url.password().isEmpty() || url.host().endsWith(".") ||
                isIpLiteral(url.host())) {
            throw new IOException("Gogo requests require a credential-free public HTTPS host");
        }
        try {
            URI uri = new URI(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
                throw new IOException("Gogo URL is invalid");
            }
        } catch (java.net.URISyntaxException malformed) {
            throw new IOException("Gogo URL is invalid");
        }
        return url;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            try {
                if (Integer.parseInt(part) > 255) return false;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        return true;
    }

    private static List<InetAddress> resolvePublicAddresses(final String host, Job job)
            throws Exception {
        ensureActive(job);
        Future<List<InetAddress>> future = DNS_EXECUTOR.submit(() -> {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            List<InetAddress> values = Arrays.asList(resolved);
            if (values.isEmpty()) throw new UnknownHostException("No DNS addresses were returned");
            for (InetAddress address : values) {
                if (!isPublicAddress(address)) {
                    throw new UnknownHostException("Gogo host resolved to a non-public address");
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(values));
        });
        DisposableHandle cancellation = job == null ? null : job.invokeOnCompletion(true, true,
                new Function1<Throwable, Unit>() {
                    @Override public Unit invoke(Throwable cause) {
                        if (cause != null) future.cancel(true);
                        return Unit.INSTANCE;
                    }
                });
        try {
            return future.get(5L, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IOException("Gogo host DNS lookup timed out");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            if (job != null && !job.isActive()) {
                throw new java.util.concurrent.CancellationException("Gogo extraction cancelled");
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (java.util.concurrent.CancellationException cancelled) {
            future.cancel(true);
            if (job != null && !job.isActive()) throw cancelled;
            throw new IOException("Gogo host DNS lookup was cancelled");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Gogo host could not be resolved");
        } finally {
            if (cancellation != null) cancellation.dispose();
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b >= 64 && b <= 127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b >= 16 && b <= 31) ||
                    (a == 192 && (b == 0 || b == 2 || b == 168)) ||
                    (a == 198 && (b == 18 || b == 19 || b == 51)) ||
                    (a == 203 && b == 0 && c == 113)) return false;
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            if ((first & 0xFE) == 0xFC || (first == 0xFE && (second & 0xC0) == 0x80) ||
                    first == 0xFF ||
                    (first == 0x20 && (bytes[1] & 255) == 0x01 &&
                            (bytes[2] & 255) == 0x0D && (bytes[3] & 255) == 0xB8)) return false;
            boolean mappedV4 = true;
            for (int i = 0; i < 10; i++) mappedV4 &= bytes[i] == 0;
            mappedV4 &= (bytes[10] & 255) == 255 && (bytes[11] & 255) == 255;
            if (mappedV4) {
                try {
                    return isPublicAddress(InetAddress.getByAddress(
                            Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException impossible) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String readBounded(ResponseBody body, Job job) throws IOException {
        long declaredLength = body.contentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) throw new IOException("Gogo response exceeded its size limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                declaredLength > 0 ? (int) declaredLength : 8192);
        try (InputStream input = body.byteStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                ensureActive(job);
                int read = input.read(buffer);
                if (read < 0) break;
                if (read == 0) {
                    int next = input.read();
                    if (next < 0) break;
                    total++;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new IOException("Gogo response exceeded its size limit");
                    }
                    output.write(next);
                    continue;
                }
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Gogo response exceeded its size limit");
                output.write(buffer, 0, read);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isChallenge(int code, String body) {
        if (code == 403 || code == 429) return true;
        String lower = body.toLowerCase(Locale.ROOT);
        return (lower.contains("captcha") && (lower.contains("<form") ||
                lower.contains("sitekey") || lower.contains("captcha-container"))) ||
                lower.contains("hcaptcha") || lower.contains("g-recaptcha") ||
                lower.contains("verify you are human") || lower.contains("cf-challenge") ||
                lower.contains("cloudflare challenge") || lower.contains("just a moment...");
    }

    private static final class IsolationCookieJar implements CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> values) {
            long now = System.currentTimeMillis();
            cookies.removeIf(cookie -> cookie.expiresAt() <= now);
            for (Cookie cookie : values) {
                cookies.removeIf(old -> old.name().equals(cookie.name()) &&
                        old.domain().equals(cookie.domain()) && old.path().equals(cookie.path()));
                if (cookie.expiresAt() > now && cookie.matches(url)) cookies.add(cookie);
            }
        }

        @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            long now = System.currentTimeMillis();
            cookies.removeIf(cookie -> cookie.expiresAt() <= now);
            ArrayList<Cookie> matching = new ArrayList<>();
            for (Cookie cookie : cookies) if (cookie.matches(url)) matching.add(cookie);
            return matching;
        }
    }

    private static final class CaptchaChallengeException extends Exception {
        private CaptchaChallengeException() { super("Gogo host requested CAPTCHA verification"); }
    }
}
