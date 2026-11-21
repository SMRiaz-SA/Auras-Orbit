package com.auras.orbit.downloads;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.auras.orbit.player.PlaybackDnsPinning;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Focused coverage for pinned DNS use on initial and manually redirected download requests. */
@RunWith(AndroidJUnit4.class)
public final class DownloadQueueDnsPinningInstrumentationTest {
    private static final String HEADER_NAME = "X-Provider-Token";
    private static final String HEADER_VALUE = "fixture-secret";
    private static final String ACTIVE_TASK_CLASS =
            "com.auras.orbit.downloads.DownloadQueueManager$ActiveTask";

    @Test public void productionPinnedDnsRejectsLoopbackPrivateAndLocalhost() throws Exception {
        Dns dns = PlaybackDnsPinning.newDns(false);
        assertDnsRejects(dns, "127.0.0.1");
        assertDnsRejects(dns, "10.0.0.8");
        assertDnsRejects(dns, "localhost");
    }

    @Test public void initialRequestUsesProductionPinnedDnsAndDirectOnlyClient() throws Exception {
        List<String> lookedUpHosts = new ArrayList<>();
        Dns publicAddressResolver = PlaybackDnsPinning.newDns(false);
        Dns testOnlyRecordingResolver = hostname -> {
            lookedUpHosts.add(hostname);
            return publicAddressResolver.lookup(hostname);
        };
        OkHttpClient client = newClient(testOnlyRecordingResolver);
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertSame(Proxy.NO_PROXY, client.proxy());
        assertSame(new OkHttpClient().hostnameVerifier(), client.hostnameVerifier());
        assertEquals(15_000, client.connectTimeoutMillis());
        assertEquals(45_000, client.readTimeoutMillis());

        for (String target : new String[] {"127.0.0.1", "10.0.0.8"}) {
            Object task = newActiveTask("initial-private-target");
            try {
                open(task, "http://" + target + ":1/file", Collections.emptyMap(), client,
                        testOnlyRecordingResolver);
                fail("the initial private or loopback destination should be rejected by pinned DNS");
            } catch (Exception expected) {
                assertCauseIsUnknownHost(expected);
            }
            assertEquals(target, lookedUpHosts.get(lookedUpHosts.size() - 1));
        }
    }

    @Test public void redirectDestinationIsResolvedAgainAndPrivateLoopbackHostsAreRejected()
            throws Exception {
        assertRedirectDestinationRejected("10.0.0.8");
        assertRedirectDestinationRejected("127.0.0.1");
    }

    @Test public void manualRedirectPreservesSameOriginHeadersAndStripsCrossOriginHeaders()
            throws Exception {
        List<Request> sameOriginRequests = new ArrayList<>();
        Dns sameOriginDns = fixtureDns();
        Response sameOriginResponse = open(newActiveTask("same-origin"),
                "https://origin.example/start", headerMap(), redirectFixtureClient(
                        sameOriginRequests, "/finish", sameOriginDns), sameOriginDns);
        try {
            assertEquals(200, sameOriginResponse.code());
        } finally {
            sameOriginResponse.close();
        }
        assertEquals(2, sameOriginRequests.size());
        assertEquals("origin.example", sameOriginRequests.get(0).url().host());
        assertEquals("origin.example", sameOriginRequests.get(1).url().host());
        assertEquals(HEADER_VALUE, sameOriginRequests.get(0).header(HEADER_NAME));
        assertEquals(HEADER_VALUE, sameOriginRequests.get(1).header(HEADER_NAME));

        List<Request> crossOriginRequests = new ArrayList<>();
        Dns crossOriginDns = fixtureDns();
        Response crossOriginResponse = open(newActiveTask("cross-origin"),
                "https://origin.example/start", headerMap(), redirectFixtureClient(
                        crossOriginRequests, "https://other.example/finish", crossOriginDns),
                crossOriginDns);
        try {
            assertEquals(200, crossOriginResponse.code());
        } finally {
            crossOriginResponse.close();
        }
        assertEquals(2, crossOriginRequests.size());
        assertEquals("origin.example", crossOriginRequests.get(0).url().host());
        assertEquals("other.example", crossOriginRequests.get(1).url().host());
        assertEquals(HEADER_VALUE, crossOriginRequests.get(0).header(HEADER_NAME));
        assertNull(crossOriginRequests.get(1).header(HEADER_NAME));
    }

    @Test public void httpsDowngradeRedirectIsRejectedBeforeTheNextRequest() throws Exception {
        List<Request> requests = new ArrayList<>();
        Dns dns = fixtureDns();
        try {
            open(newActiveTask("https-downgrade"), "https://secure.example/start",
                    Collections.emptyMap(),
                    redirectFixtureClient(requests, "http://clear.example/finish", dns), dns);
            fail("HTTPS to HTTP downgrade should be rejected");
        } catch (Exception expected) {
            assertEquals("Orbit rejected an insecure downgrade redirect.", expected.getMessage());
        }
        assertEquals(1, requests.size());
    }

    @Test public void cancellationBeforeCallPublicationCancelsTheLateCall() throws Exception {
        Object task = newActiveTask("call-publication-race");
        Class<?> taskClass = task.getClass();
        Field pause = taskClass.getDeclaredField("pause");
        pause.setAccessible(true);
        Method stopConnection = taskClass.getDeclaredMethod("stopConnection");
        stopConnection.setAccessible(true);
        Method setCall = taskClass.getDeclaredMethod("setCall", Call.class);
        setCall.setAccessible(true);

        Call call = new OkHttpClient().newCall(new Request.Builder()
                .url("https://example.test/file").build());
        CountDownLatch stoppedBeforePublication = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread stoppingThread = new Thread(() -> {
            try {
                pause.setBoolean(task, true);
                stopConnection.invoke(task);
                stoppedBeforePublication.countDown();
            } catch (Throwable failure) {
                threadFailure.set(failure);
                stoppedBeforePublication.countDown();
            }
        });
        Thread publishingThread = new Thread(() -> {
            try {
                assertTrue(stoppedBeforePublication.await(5, TimeUnit.SECONDS));
                setCall.invoke(task, call);
            } catch (Throwable failure) {
                threadFailure.set(failure);
            }
        });
        stoppingThread.start();
        publishingThread.start();
        stoppingThread.join(5_000L);
        publishingThread.join(5_000L);

        assertFalse("test worker threads should have completed", stoppingThread.isAlive()
                || publishingThread.isAlive());
        if (threadFailure.get() != null) throw new AssertionError(threadFailure.get());
        assertTrue("late call publication must observe pause and cancel the call", call.isCanceled());
    }

    private static void assertRedirectDestinationRejected(String blockedHost) throws Exception {
        List<String> resolvedHosts = Collections.synchronizedList(new ArrayList<>());
        try (SingleRedirectServer server = new SingleRedirectServer(
                "http://" + blockedHost + ":1/blocked")) {
            InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            Dns publicAddressResolver = PlaybackDnsPinning.newDns(false);
            Dns testOnlyFixtureResolver = hostname -> {
                resolvedHosts.add(hostname);
                if ("download-origin.example".equals(hostname)) {
                    return Collections.singletonList(loopback);
                }
                return publicAddressResolver.lookup(hostname);
            };
            try {
                open(newActiveTask("redirect-private-target"),
                        server.url("download-origin.example"), Collections.emptyMap(),
                        newClient(testOnlyFixtureResolver), testOnlyFixtureResolver);
                fail("redirect destination should be resolved and rejected");
            } catch (Exception expected) {
                assertCauseIsUnknownHost(expected);
            }
            assertTrue(server.awaitRequest());
            assertEquals(1, server.requestCount());
            assertTrue("initial and redirect host lookups should both reach the resolver",
                    resolvedHosts.size() >= 2);
            assertEquals("download-origin.example", resolvedHosts.get(0));
            assertEquals(blockedHost, resolvedHosts.get(resolvedHosts.size() - 1));
        }
    }

    private static Response open(Object task, String source,
            Map<String, String> headers, OkHttpClient client, Dns pinnedDns) throws Exception {
        Class<?> taskClass = task.getClass();
        Method method = DownloadQueueManager.class.getDeclaredMethod(
                "openResponseFollowingRedirects", String.class, Map.class, taskClass,
                OkHttpClient.class, Dns.class);
        method.setAccessible(true);
        try {
            return (Response) method.invoke(null, source, headers, task, client, pinnedDns);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }

    private static OkHttpClient newClient(Dns dns) throws Exception {
        Method method = DownloadQueueManager.class.getDeclaredMethod("newDownloadClient", Dns.class);
        method.setAccessible(true);
        try {
            return (OkHttpClient) method.invoke(null, dns);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }

    private static Object newActiveTask(String id) throws Exception {
        Class<?> type = Class.forName(ACTIVE_TASK_CLASS);
        Constructor<?> constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id);
    }

    private static Map<String, String> headerMap() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_NAME, HEADER_VALUE);
        return headers;
    }

    private static Dns fixtureDns() throws UnknownHostException {
        InetAddress fixtureAddress = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        return hostname -> Collections.singletonList(fixtureAddress);
    }

    private static OkHttpClient redirectFixtureClient(List<Request> requests, String location,
            Dns dns) {
        return new OkHttpClient.Builder()
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor((Interceptor.Chain chain) -> {
                    Request request = chain.request();
                    requests.add(request);
                    boolean redirect = requests.size() == 1;
                    Response.Builder response = new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(redirect ? 302 : 200)
                            .message(redirect ? "Found" : "OK")
                            .body(ResponseBody.create(new byte[0], null));
                    if (redirect) response.header("Location", location);
                    return response.build();
                })
                .build();
    }

    private static void assertDnsRejects(Dns dns, String hostname) throws Exception {
        try {
            dns.lookup(hostname);
            fail("pinned DNS accepted a non-public destination: " + hostname);
        } catch (UnknownHostException expected) {
            // PlaybackDnsPinning intentionally collapses unsafe and unresolved destinations here.
        }
    }

    private static void assertCauseIsUnknownHost(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnknownHostException) return;
        }
        fail("expected an UnknownHostException in the failure chain, received " + failure);
    }

    private static final class SingleRedirectServer implements Closeable {
        private final ServerSocket server;
        private final String location;
        private final CountDownLatch requestRead = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile int requests;
        private final Thread worker;

        SingleRedirectServer(String location) throws IOException {
            this.location = location;
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(5_000);
            worker = new Thread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader input = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
                    while (true) {
                        String line = input.readLine();
                        if (line == null || line.isEmpty()) break;
                    }
                    requests++;
                    OutputStream output = socket.getOutputStream();
                    String response = "HTTP/1.1 302 Found\r\nLocation: " + this.location
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    output.write(response.getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    requestRead.countDown();
                }
            }, "download-dns-redirect-fixture");
            worker.setDaemon(true);
            worker.start();
        }

        String url(String hostname) {
            return "http://" + hostname + ":" + server.getLocalPort() + "/start";
        }

        boolean awaitRequest() throws InterruptedException {
            boolean completed = requestRead.await(5, TimeUnit.SECONDS);
            if (failure.get() != null) throw new AssertionError(failure.get());
            return completed;
        }

        int requestCount() { return requests; }

        @Override public void close() throws IOException {
            server.close();
        }
    }
}
