package com.auras.orbit.network

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auras.orbit.BuildConfig
import com.auras.orbit.extensions.CS3PluginRuntime
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Local-only characterization of Orbit's CloudStream WebViewResolver facade and host. */
@RunWith(AndroidJUnit4::class)
class WebViewResolverInstrumentationTest {
    private lateinit var fixtureActivity: WebViewResolverFixtureActivity

    @Before
    fun attachToIsolatedLocalFixtureActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assertFalse("the WebView fixture must not enable the CS3 canary", BuildConfig.CS3_CANARY_BUILD)
        assertTrue("the target must be the isolated WebView fixture package",
            targetContext.packageName.endsWith(".webviewfixture"))
        assertTrue("the target must use the dedicated fixture Application",
            targetContext.applicationContext is WebViewResolverFixtureApplication)
        assertFalse("fixture must not inherit the main app's cleartext allowance",
            (targetContext.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0)
        assertTrue("fixture must package its exact-loopback network security config",
            targetContext.resources.getIdentifier(
                "orbit_webview_fixture_network_security", "xml", targetContext.packageName
            ) != 0)
        val launch = Intent(targetContext, WebViewResolverFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(launch)
        assertTrue("fixture Activity must come from the isolated target APK",
            activity is WebViewResolverFixtureActivity)
        fixtureActivity = activity as WebViewResolverFixtureActivity
        assertNotNull("the test fixture Activity must install resolver host services",
            OrbitCs3HostServices.get().currentActivity)
        assertFalse("plugin crosswalk gate must remain closed",
            CS3PluginRuntime(targetContext).isTargetPluginCrosswalkValidated)
    }

    @After
    fun closeIsolatedFixtureActivity() {
        if (::fixtureActivity.isInitialized && !fixtureActivity.isDestroyed) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { fixtureActivity.finish() }
        }
    }

    @Test
    fun resolverMatchesPinnedCloudStreamJvmDescriptorsAndDefaults() {
        assertFalse("normal AndroidTest must not enable the CS3 canary", BuildConfig.CS3_CANARY_BUILD)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("plugin crosswalk gate must remain closed",
            CS3PluginRuntime(context).isTargetPluginCrosswalkValidated)

        val type = resolverType()
        assertTrue("WebViewResolver must implement OkHttp Interceptor",
            Interceptor::class.java.isAssignableFrom(type))
        assertEquals("the pinned Android class exposes the Interceptor contract directly",
            listOf(Interceptor::class.java), type.interfaces.toList())
        assertEquals("intercept must retain the OkHttp JVM descriptor", okhttp3.Response::class.java,
            type.getDeclaredMethod("intercept", Interceptor.Chain::class.java).returnType)

        val constructorParameters = arrayOf(
            Regex::class.java, List::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
            String::class.java, kotlin.jvm.functions.Function1::class.java, Long::class.javaPrimitiveType!!
        )
        type.getConstructor(*constructorParameters)
        val defaultConstructor = type.getConstructor(
            *constructorParameters,
            Int::class.javaPrimitiveType!!,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java
        )
        assertTrue("Kotlin default constructor bridge is synthetic", defaultConstructor.isSynthetic)
        assertEquals("Companion must remain the sole public field declared by WebViewResolver",
            listOf("Companion"), type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.map { it.name })

        val companionField = type.getField("Companion")
        assertTrue(Modifier.isStatic(companionField.modifiers))
        assertTrue(Modifier.isFinal(companionField.modifiers))
        val companionType = companionField.type
        assertEquals(type.getDeclaredClasses().single { it.simpleName == "Companion" }, companionType)
        assertEquals(java.lang.Long.TYPE, companionType.getMethod("getDEFAULT_TIMEOUT").returnType)
        assertEquals(String::class.java, companionType.getMethod("getWebViewUserAgent").returnType)
        companionType.getMethod("setWebViewUserAgent", String::class.java)
        companionType.getMethod("getWebViewUserAgent1")

        val continuation = Continuation::class.java
        val callback = kotlin.jvm.functions.Function1::class.java
        val overloads = listOf(
            arrayOf(String::class.java, String::class.java, String::class.java, callback, continuation),
            arrayOf(String::class.java, String::class.java, Map::class.java, String::class.java, callback, continuation),
            arrayOf(Request::class.java, callback, continuation)
        )
        overloads.forEach { parameters ->
            val function = type.getDeclaredMethod("resolveUsingWebView", *parameters)
            assertEquals("suspend JVM methods return Object", Object::class.java, function.returnType)
            val bridgeParameters = arrayOf(type, *parameters, Int::class.javaPrimitiveType!!, Object::class.java)
            val bridge = type.getDeclaredMethod("resolveUsingWebView\$default", *bridgeParameters)
            assertTrue("default bridge must be static", Modifier.isStatic(bridge.modifiers))
            assertTrue("default bridge must be synthetic", bridge.isSynthetic)
        }
    }

    @Test
    fun urlAndHeaderOverloadsCaptureRequestsAndRunScriptCallback() = runBlocking {
        LocalFixture().use { fixture ->
            val scriptResults = CopyOnWriteArrayList<String>()
            val scriptCallback = CountDownLatch(1)
            val resolver = createResolver(
                intercept = ".*/api/final.*",
                additional = listOf(".*/start.*", ".*/api/match.*"),
                script = fixtureScript(),
                onScript = { value -> scriptResults.add(value); scriptCallback.countDown() },
                timeoutMs = 8_000L
            )
            val callbacks = CopyOnWriteArrayList<Request>()
            val callback: (Request) -> Boolean = { request -> callbacks.add(request); false }

            // The first overload and its defaults are exercised separately from the map overload.
            val urlMethod = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                String::class.java, kotlin.jvm.functions.Function1::class.java, Continuation::class.java
            )
            val first = withContext(Dispatchers.IO) {
                invokeSuspend(urlMethod, resolver, arrayOf(fixture.url("/start?case=url"), null, "GET", callback))
            } as Pair<Request?, List<Request>>
            assertTrue("page script callback should run", scriptCallback.await(5, TimeUnit.SECONDS))
            assertTrue("script result should include the fixture literal",
                scriptResults.any { it.contains("orbit-script-result") })
            assertNotNull("interceptUrl should produce the final request", first.first)
            assertEquals("POST", first.first!!.method)
            assertEquals(2, first.second.size)
            assertEquals("/start", first.second[0].url.encodedPath)
            assertEquals("/api/match", first.second[1].url.encodedPath)
            assertEquals(3, callbacks.size)
            assertEquals("POST", callbacks.last().method)
            assertTrue(fixture.requests.any { it.path.startsWith("/start?case=url") })

            val mapResolver = createResolver(
                intercept = ".*/api/final.*", additional = listOf(".*/start.*", ".*/api/match.*"),
                script = fixtureScript(), onScript = null, timeoutMs = 8_000L
            )
            val mapMethod = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                Map::class.java, String::class.java, kotlin.jvm.functions.Function1::class.java,
                Continuation::class.java
            )
            val mapResult = withContext(Dispatchers.IO) {
                invokeSuspend(mapMethod, mapResolver, arrayOf(
                    fixture.url("/start?case=headers"), "http://127.0.0.1/source",
                    mapOf("X-Orbit-Entry" to "header-value"), "GET", callback
                ))
            } as Pair<Request?, List<Request>>
            assertNotNull(mapResult.first)
            val capturedEntry = mapResult.second.single {
                it.url.encodedPath == "/start"
            }
            assertEquals("captured WebResourceRequest retains the caller header",
                "header-value", capturedEntry.header("X-Orbit-Entry"))
            assertEquals("WebView applies its origin-only Referer policy before capture",
                "http://127.0.0.1/", capturedEntry.header("Referer"))
            assertTrue("header capture mismatch; requests=${fixture.requests}", fixture.requests.any {
                it.path.startsWith("/start?case=headers")
                    && it.headers["x-orbit-entry"] == "header-value"
                    && it.headers["referer"] == "http://127.0.0.1/"
            })
        }
    }

    @Test
    fun requestOverloadPreservesEntryMethodAndHeaders() = runBlocking {
        LocalFixture().use { fixture ->
            val resolver = createResolver(
                intercept = ".*/api/final.*", additional = listOf(".*/start.*", ".*/api/match.*"),
                script = fixtureScript(), onScript = null, timeoutMs = 8_000L
            )
            val request = Request.Builder()
                .url(fixture.url("/start?case=request"))
                .header("X-Orbit-Request", "present")
                .method("POST", "request-body".toRequestBody("text/plain".toMediaType()))
                .build()
            val callback: (Request) -> Boolean = { false }
            val method = resolverType().getDeclaredMethod(
                "resolveUsingWebView", Request::class.java,
                kotlin.jvm.functions.Function1::class.java, Continuation::class.java
            )
            val result = withContext(Dispatchers.IO) {
                invokeSuspend(method, resolver, arrayOf(request, callback))
            } as Pair<Request?, List<Request>>
            assertEquals("POST", result.second.first().method)
            assertEquals("present", result.second.first().header("X-Orbit-Request"))
            assertTrue(fixture.requests.any {
                it.path.startsWith("/start?case=request") && it.method == "POST"
                    && it.headers["x-orbit-request"] == "present" && it.body == "request-body"
            })
            assertEquals("POST", result.first!!.method)
        }
    }

    @Test
    fun nativeAndChallengeRequestsRemainWebViewOwnedAndScriptsRunPerRequest() = runBlocking {
        LocalFixture().use { fixture ->
            assertFalse(OrbitWebViewResolverRuntime.shouldFetchWebRequestWithOkHttp(
                useOkhttp = false, method = "GET", url = fixture.url("/native-child")
            ))
            assertFalse(OrbitWebViewResolverRuntime.shouldFetchWebRequestWithOkHttp(
                useOkhttp = true, method = "GET", url = fixture.url("/cdn-cgi/challenge")
            ))
            assertFalse(OrbitWebViewResolverRuntime.shouldFetchWebRequestWithOkHttp(
                useOkhttp = true, method = "GET", url = fixture.url("/recaptcha/verify")
            ))
            assertTrue(OrbitWebViewResolverRuntime.shouldFetchWebRequestWithOkHttp(
                useOkhttp = true, method = "GET", url = fixture.url("/ordinary-resource")
            ))

            val nativeToken = System.nanoTime().toString()
            val scriptResults = CopyOnWriteArrayList<String>()
            val nativeResolver = createResolver(
                intercept = ".*/never-match.*",
                additional = listOf(".*/native-child-a.*", ".*/native-child-b.*"),
                script = "window.orbitResolverRunCount = (window.orbitResolverRunCount || 0) + 1; 'orbit-script-result';",
                onScript = scriptResults::add,
                timeoutMs = 3_000L,
                useOkhttp = false
            )
            val mapMethod = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                Map::class.java, String::class.java, kotlin.jvm.functions.Function1::class.java,
                Continuation::class.java
            )
            val nativeResult = withContext(Dispatchers.IO) {
                invokeSuspend(mapMethod, nativeResolver, arrayOf(
                    fixture.url("/native-start?run=$nativeToken"), null,
                    mapOf("X-Orbit-Native" to nativeToken), "GET", { _: Request -> false }
                ))
            } as Pair<Request?, List<Request>>
            assertEquals("both matching requests should be captured before WebView fallback",
                setOf("/native-child-a", "/native-child-b"),
                nativeResult.second.map { it.url.encodedPath }.toSet())
            val nativeStart = fixture.requests.firstOrNull { it.path.startsWith("/native-start") }
            assertNotNull("initial caller header should reach the native WebView request", nativeStart)
            assertEquals(nativeToken, nativeStart!!.headers["x-orbit-native"])
            val nativeChildren = fixture.requests.filter {
                it.path.startsWith("/native-child-a") || it.path.startsWith("/native-child-b")
            }
            assertEquals("the page should issue both child requests", 2, nativeChildren.size)
            assertTrue("WebView CookieManager should carry Set-Cookie into native child requests: $nativeChildren",
                nativeChildren.all { it.headers["cookie"].orEmpty().contains("orbit_fixture_$nativeToken=present") })
            assertTrue("script and callback should run for each WebView request; results=$scriptResults",
                scriptResults.size >= 3 && scriptResults.all { it.contains("orbit-script-result") })

            val challengeToken = (System.nanoTime() + 1).toString()
            val challengeResolver = createResolver(
                intercept = ".*/never-match.*",
                additional = listOf(".*/cdn-cgi/challenge-child.*", ".*/recaptcha/verify.*"),
                script = null,
                onScript = null,
                timeoutMs = 2_000L
            )
            val urlMethod = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                String::class.java, kotlin.jvm.functions.Function1::class.java, Continuation::class.java
            )
            val challengeResult = withContext(Dispatchers.IO) {
                invokeSuspend(urlMethod, challengeResolver, arrayOf(
                    fixture.url("/cdn-cgi/start?run=$challengeToken"), null, "GET", { _: Request -> false }
                ))
            } as Pair<Request?, List<Request>>
            assertEquals("Cloudflare and reCAPTCHA requests should be captured, then left to WebView",
                setOf("/cdn-cgi/challenge-child", "/recaptcha/verify"),
                challengeResult.second.map { it.url.encodedPath }.toSet())
            val challengeChildren = fixture.requests.filter {
                it.path.startsWith("/cdn-cgi/challenge") || it.path.startsWith("/recaptcha/verify")
            }
            assertEquals(2, challengeChildren.size)
            assertTrue("challenge requests should retain WebView-managed cookies: $challengeChildren",
                challengeChildren.all {
                    it.headers["cookie"].orEmpty().contains("orbit_fixture_$challengeToken=present")
                })
        }
    }

    @Test
    fun responseHeadersAreBoundedBeforeValuesAreJoinedOrRetained() {
        val countBuilder = Headers.Builder()
        repeat(64) { countBuilder.add("X-Count-$it", "v") }
        countBuilder.add("X-Count-64", "overflow")
        val countBounded = OrbitWebViewResolverRuntime.collectBoundedResponseHeaders(countBuilder.build())
        assertEquals("only the first 64 response header fields should be inspected", 64, countBounded.size)
        assertFalse(countBounded.keys.any { it.equals("X-Count-64", ignoreCase = true) })

        val valueAndNameBuilder = Headers.Builder()
            .add("X-Large-Value", "v".repeat(4097))
            .add("X${"n".repeat(256)}", "value")
            .add("X${"n".repeat(255)}", "e".repeat(4096))
            .add("Content-Type", "text/plain; charset=utf-8")
        val valueAndNameBounded = OrbitWebViewResolverRuntime
            .collectBoundedResponseHeaders(valueAndNameBuilder.build())
        assertEquals(2, valueAndNameBounded.size)
        assertEquals("e".repeat(4096), valueAndNameBounded["X${"n".repeat(255)}"])
        assertEquals("text/plain; charset=utf-8", valueAndNameBounded["Content-Type"])

        val aggregateBuilder = Headers.Builder()
        repeat(3) { aggregateBuilder.add("X-Aggregate-$it", "a".repeat(4096)) }
        aggregateBuilder.add("X-Aggregate-3", "a".repeat(4044))
        aggregateBuilder.add("X-Aggregate-Overflow", "b")
        val aggregateBounded = OrbitWebViewResolverRuntime.collectBoundedResponseHeaders(aggregateBuilder.build())
        assertEquals("the aggregate cap should retain only fields that fit", 4, aggregateBounded.size)
        assertEquals("retained aggregate header bytes should land exactly on the cap", 16 * 1024,
            aggregateBounded.entries.sumOf {
                it.key.toByteArray(StandardCharsets.UTF_8).size + it.value.toByteArray(StandardCharsets.UTF_8).size
            })
        assertFalse(aggregateBounded.keys.any { it.equals("X-Aggregate-Overflow", ignoreCase = true) })

        val duplicateBounded = OrbitWebViewResolverRuntime.collectBoundedResponseHeaders(
            Headers.Builder()
                .add("X-Dupe", "a".repeat(2000))
                .add("x-dupe", "b".repeat(2000))
                .add("X-Dupe", "c".repeat(92))
                .add("X-Dupe", "d")
                .build()
        )
        assertEquals("duplicate values are joined only while their combined value remains within its cap",
            4096, duplicateBounded.values.single().length)
        assertTrue(duplicateBounded.values.single().endsWith("c".repeat(92)))
    }

    @Test
    fun cancellationThatWinsBeforeResourceRegistrationImmediatelyCancelsIt() {
        val completed = AtomicBoolean(true)
        val active = linkedSetOf<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        var workStarted = false
        val registered = registerCancelableResource("late-call", completed, active) { cancelled.add(it) }
        if (registered) workStarted = true

        assertFalse("a resource registered after completion must not start", registered)
        assertFalse(workStarted)
        assertTrue("the late resource should be removed from active tracking", active.isEmpty())
        assertEquals(listOf("late-call"), cancelled.toList())
    }

    @Test
    fun nonHttpEntryIsRejectedAndTimeoutReturnsCollectedRequests() = runBlocking {
        val resolver = createResolver(".*", emptyList(), null, null, 250L)
        val callback: (Request) -> Boolean = { false }
        val urlMethod = resolverType().getDeclaredMethod(
            "resolveUsingWebView", String::class.java, String::class.java,
            String::class.java, kotlin.jvm.functions.Function1::class.java, Continuation::class.java
        )
        var rejected = false
        try {
            invokeSuspend(urlMethod, resolver, arrayOf("file:///etc/hosts", null, "GET", callback))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("file/content/data entry URLs must fail closed", rejected)

        LocalFixture().use { fixture ->
            val slowResolver = createResolver(".*/never-match.*", emptyList(), null, null, 250L)
            val start = System.nanoTime()
            val result = withContext(Dispatchers.IO) {
                invokeSuspend(urlMethod, slowResolver,
                    arrayOf(fixture.url("/slow"), null, "GET", callback))
            } as Pair<Request?, List<Request>>
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("resolver timeout should be bounded", elapsedMs < 1_500L)
            assertEquals(null, result.first)
            assertTrue(fixture.requests.any { it.path == "/slow" })
        }
    }

    @Test
    fun callbackStopAndCoroutineCancellationReleaseTheResolver() = runBlocking {
        LocalFixture().use { fixture ->
            val resolver = createResolver(
                intercept = ".*/api/final.*", additional = listOf(".*/api/match.*"),
                script = fixtureScript(), onScript = null, timeoutMs = 8_000L
            )
            val callback: (Request) -> Boolean = { it.url.encodedPath == "/api/match" }
            val method = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                String::class.java, kotlin.jvm.functions.Function1::class.java, Continuation::class.java
            )
            val stopped = withContext(Dispatchers.IO) {
                invokeSuspend(method, resolver, arrayOf(fixture.url("/start?case=stop"), null, "GET", callback))
            } as Pair<Request?, List<Request>>
            assertEquals(null, stopped.first)
            assertEquals(1, stopped.second.size)
            assertEquals("/api/match", stopped.second.single().url.encodedPath)

            val cancelResolver = createResolver(".*/never-match.*", emptyList(), null, null, 8_000L)
            val urlMethod = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                String::class.java, kotlin.jvm.functions.Function1::class.java, Continuation::class.java
            )
            val pending = async(Dispatchers.IO) {
                invokeSuspend(urlMethod, cancelResolver,
                    arrayOf(fixture.url("/slow?case=cancel"), null, "GET", { _: Request -> false }))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (fixture.requests.none { it.path.startsWith("/slow?case=cancel") }
                && System.nanoTime() < deadline) delay(10)
            assertTrue("slow request should be active before cancel", pending.isActive)
            pending.cancelAndJoin()
            assertTrue("request cancellation must not cancel the caller", currentCoroutineContext().isActive)

            val quick = createResolver(".*/api/final.*", emptyList(), fixtureScript(), null, 2_000L)
            val quickResult = withContext(Dispatchers.IO) {
                invokeSuspend(urlMethod, quick,
                    arrayOf(fixture.url("/start?case=after-cancel"), null, "GET", { _: Request -> false }))
            } as Pair<Request?, List<Request>>
            assertNotNull("resolver must be reusable after cancellation", quickResult.first)
        }
    }

    @Test
    fun stoppingFixtureActivityCancelsResolverAndDetachesItsSession() = runBlocking {
        LocalFixture().use { fixture ->
            val resolver = createResolver(".*/never-match.*", emptyList(), null, null, 8_000L)
            val method = resolverType().getDeclaredMethod(
                "resolveUsingWebView", String::class.java, String::class.java,
                String::class.java, kotlin.jvm.functions.Function1::class.java, Continuation::class.java
            )
            val pending = async(Dispatchers.IO) {
                invokeSuspend(method, resolver,
                    arrayOf(fixture.url("/slow?case=activity-stop"), null, "GET", { _: Request -> false }))
            }
            val requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fixture.requests.none { it.path.startsWith("/slow?case=activity-stop") }
                && System.nanoTime() < requestDeadline) delay(10)
            assertTrue("fixture request should be active before the host Activity stops",
                fixture.requests.any { it.path.startsWith("/slow?case=activity-stop") })

            InstrumentationRegistry.getInstrumentation().runOnMainSync { fixtureActivity.finish() }
            val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (OrbitCs3HostServices.get().currentActivity != null
                && System.nanoTime() < stopDeadline) delay(10)
            assertNull("stopping the owner must clear the active host Activity",
                OrbitCs3HostServices.get().currentActivity)

            val failure = runCatching { pending.await() }.exceptionOrNull()
            assertTrue("Activity stop must cancel the active resolver session",
                failure is CancellationException)
            assertEquals("Activity stop must remove the session after destroying its WebView",
                0, activeSessionCount(fixtureActivity))
        }
    }

    private fun activeSessionCount(activity: android.app.Activity): Int {
        val instance = OrbitWebViewResolverRuntime::class.java
            .getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val sessions = OrbitWebViewResolverRuntime::class.java
            .getDeclaredField("sessionsByActivity").apply { isAccessible = true }
            .get(instance) as Map<*, *>
        return (sessions[activity] as? Set<*>)?.size ?: 0
    }

    private fun resolverType(): Class<*> = WebViewResolver::class.java

    private fun fixtureScript(): String = """
        document.body.setAttribute('data-orbit-script','ran');
        'orbit-script-result'
    """.trimIndent()

    private fun createResolver(
        intercept: String,
        additional: List<String>,
        script: String?,
        onScript: ((String) -> Unit)?,
        timeoutMs: Long,
        useOkhttp: Boolean = true
    ): Any {
        val constructor = resolverType().getConstructor(
            Regex::class.java, List::class.java, String::class.java, Boolean::class.javaPrimitiveType!!,
            String::class.java, kotlin.jvm.functions.Function1::class.java, Long::class.javaPrimitiveType!!
        )
        return constructor.newInstance(
            Regex(intercept), additional.map(::Regex), "Orbit WebView fixture", useOkhttp,
            script, onScript, timeoutMs
        )
    }

    private suspend fun invokeSuspend(method: Method, receiver: Any, arguments: Array<Any?>): Any? =
        suspendCancellableCoroutine { continuation ->
            val completion = object : Continuation<Any?> {
                override val context: CoroutineContext = continuation.context
                override fun resumeWith(result: Result<Any?>) = continuation.resumeWith(result)
            }
            try {
                val returned = method.invoke(receiver, *arguments, completion)
                if (returned !== COROUTINE_SUSPENDED) continuation.resume(returned)
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private class LocalFixture : Closeable {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val worker = Thread({ acceptLoop() }, "orbit-webview-fixture-accept").apply {
            isDaemon = true
            start()
        }
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

        private fun acceptLoop() {
            while (!closed.get()) {
                try {
                    val socket = server.accept()
                    Thread({ serve(socket) }, "orbit-webview-fixture-request").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!closed.get()) throw RuntimeException("loopback fixture accept failed")
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            socket.use { connection ->
                try {
                    connection.soTimeout = 5_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val requestLine = readLine(input) ?: return
                    val parts = requestLine.split(' ')
                    if (parts.size < 2) return
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                    val bodySize = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16_384) ?: 0
                    val requestBody = ByteArray(bodySize)
                    var read = 0
                    while (read < requestBody.size) {
                        val count = input.read(requestBody, read, requestBody.size - read)
                        if (count < 0) break
                        read += count
                    }
                    val path = parts[1]
                    requests.add(RecordedRequest(parts[0], path, headers,
                        String(requestBody, 0, read, StandardCharsets.UTF_8)))
                    if (path == "/slow" || path.startsWith("/slow?")) Thread.sleep(2_000)
                    val pathOnly = path.substringBefore('?')
                    val runToken = Regex("[?&]run=([A-Za-z0-9_-]+)").find(path)?.groupValues?.getOrNull(1)
                    val (responseText, type, setCookie) = when (pathOnly) {
                        "/start" -> Triple(
                            """<!doctype html><html><head><title>orbit-fixture</title></head><body>fixture
                                <script>
                                fetch('/api/match?source=fixture', {headers:{'X-Child':'child'}})
                                  .then(() => fetch('/api/final?source=fixture', {
                                    method:'POST', headers:{'X-Final':'final'}, body:'fixture-body'
                                  })).catch(() => {});
                                </script></body></html>""".trimIndent(),
                            "text/html; charset=utf-8", null
                        )
                        "/native-start" -> Triple(
                            """<!doctype html><html><body><script>
                                Promise.all([
                                  fetch('/native-child-a?run=$runToken'),
                                  fetch('/native-child-b?run=$runToken')
                                ]).catch(() => {});
                                </script></body></html>""".trimIndent(),
                            "text/html; charset=utf-8", "orbit_fixture_$runToken=present; Path=/; SameSite=Lax"
                        )
                        "/cdn-cgi/start" -> Triple(
                            """<!doctype html><html><body><script>
                                Promise.all([
                                  fetch('/cdn-cgi/challenge-child?run=$runToken'),
                                  fetch('/recaptcha/verify?run=$runToken')
                                ]).catch(() => {});
                                </script></body></html>""".trimIndent(),
                            "text/html; charset=utf-8", "orbit_fixture_$runToken=present; Path=/; SameSite=Lax"
                        )
                        else -> Triple("fixture-response", "text/plain; charset=utf-8", null)
                    }
                    val response = responseText.toByteArray(StandardCharsets.UTF_8)
                    val responseHeaders = mutableListOf(
                        "Content-Type: $type",
                        "Content-Length: ${response.size}",
                        "Connection: close"
                    )
                    setCookie?.let { responseHeaders.add("Set-Cookie: $it") }
                    val output = BufferedOutputStream(connection.getOutputStream())
                    output.write(("HTTP/1.1 200 OK\r\n" + responseHeaders.joinToString("\r\n") + "\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII))
                    output.write(response)
                    output.flush()
                } catch (_: Exception) {
                    // Timeout/cancellation deliberately closes the in-process fixture socket.
                }
            }
        }

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
                if (bytes.size() > 16_384) return null
            }
            return bytes.toString("UTF-8")
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) server.close()
            worker.join(500)
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )
}
