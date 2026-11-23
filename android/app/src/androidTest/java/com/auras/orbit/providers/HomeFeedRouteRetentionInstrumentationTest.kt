package com.auras.orbit.providers

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auras.orbit.BuildConfig
import com.auras.orbit.OrbitApplication
import com.auras.orbit.extensions.CloudStreamExtensionManager
import com.auras.orbit.extensions.CS3PluginRuntime
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.auras.orbit.navigation.ShellRoute
import com.auras.orbit.profile.LocalProfileStore
import com.auras.orbit.profile.ProviderSelectionStore
import com.auras.orbit.ui.OrbitShellActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies a completed Home feed remains available until its provider metadata changes. */
@RunWith(AndroidJUnit4::class)
class HomeFeedRouteRetentionInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var target: Context
    private lateinit var application: OrbitApplication
    private lateinit var profileStore: LocalProfileStore
    private lateinit var selectionStore: ProviderSelectionStore
    private lateinit var extensionManager: CloudStreamExtensionManager
    private lateinit var runtime: CS3PluginRuntime
    private lateinit var hostServices: OrbitCs3HostServices
    private lateinit var registry: Cs3ProviderRegistry
    private lateinit var extensionPreferences: SharedPreferences
    private lateinit var providerPreferences: SharedPreferences
    private lateinit var profilePreferences: SharedPreferences
    private var extensionSnapshot: PreferenceSnapshot? = null
    private var providerSnapshot: PreferenceSnapshot? = null
    private var profileSnapshot: PreferenceSnapshot? = null
    private var originalRuntimeProfileId: String? = null
    private var fixtureProfileId: String? = null
    private var fixtureProfileWritten = false
    private var fixtureExtensionWritten = false
    private var fixtureSelectionWritten = false
    private var shell: OrbitShellActivity? = null
    private var fixtureTransaction: OrbitCs3HostServices.LoadTransaction? = null
    private var fixtureMarker: File? = null

    @Before
    fun launchIsolatedShell() {
        target = instrumentation.targetContext
        assertEquals("the Home retention fixture must use the isolated queueRender app",
            "com.auras.orbit.queuerender", target.packageName)
        assertFalse("the focused feed test must not enable the CS3 canary",
            BuildConfig.CS3_CANARY_BUILD)
        application = target.applicationContext as OrbitApplication
        profileStore = application.localProfileStore
        selectionStore = application.providerSelectionStore
        extensionManager = application.cloudStreamExtensionManager
        runtime = application.cS3PluginRuntime
        hostServices = OrbitCs3HostServices.get()
        registry = application.cs3ProviderRegistry
        originalRuntimeProfileId = runtime.activeProfileId

        extensionPreferences = readField(extensionManager, "preferences") as SharedPreferences
        providerPreferences = readField(selectionStore, "preferences") as SharedPreferences
        profilePreferences = readField(profileStore, "preferences") as SharedPreferences
        extensionSnapshot = snapshotPreferences(EXTENSION_PREFERENCES, extensionPreferences)
        providerSnapshot = snapshotPreferences(PROVIDER_PREFERENCES, providerPreferences)
        profileSnapshot = snapshotPreferences(PROFILE_PREFERENCES, profilePreferences)

        fixtureProfileWritten = true
        val profile = profileStore.createProfile("Home Feed Retention Fixture")
            ?: throw AssertionError("the disposable Home retention profile must be created")
        fixtureProfileId = profile.id
        profileStore.selectProfile(profile.id)

        val launch = android.content.Intent(target, OrbitShellActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(launch)
        assertTrue("the fixture must launch the actual OrbitShellActivity",
            activity is OrbitShellActivity)
        shell = activity as OrbitShellActivity
        await("the selected profile prompt or confirmed Home shell must render") {
            findText(shell!!, "Continue") != null || findTagged(shell!!, "nav_HOME") != null
        }
        findText(shell!!, "Continue")?.let { continueButton -> onMain {
            assertTrue("the disposable profile continues into Orbit", continueButton.performClick())
        } }
        await("the confirmed shell must expose Home navigation") {
            findTagged(shell!!, "nav_HOME") != null
        }
        providerSnapshot = snapshotPreferences(PROVIDER_PREFERENCES, providerPreferences)
    }

    @Test
    fun homeFeedSurvivesRoutesAndActivityStopsUntilMetadataChanges() {
        val profileId = fixtureProfileId
            ?: throw AssertionError("the disposable profile was not prepared")
        val pluginId = FIXTURE_PLUGIN_ID
        assertFalse("the fixture plugin ID must not already be installed",
            extensionManager.getInstalledPlugin(pluginId) != null)
        assertFalse("the fixture plugin must not already own a runtime handle", runtime.isLoaded(pluginId))
        assertFalse("APIHolder must not already contain this fixture provider",
            APIHolder.INSTANCE.allProviders.any { it === fixtureApi })
        fixtureApi.firstHomePageHasNext = true

        val markerDirectory = File(target.cacheDir, FIXTURE_DIRECTORY_NAME)
        assertTrue("the fixture marker directory is app-private",
            markerDirectory.mkdirs() || markerDirectory.isDirectory)
        fixtureMarker = File(markerDirectory, "registration.fixture-marker")
        assertTrue("the provider runtime marker is created", fixtureMarker!!.createNewFile())
        fixtureExtensionWritten = true
        addFixtureExtensionGrant(profileId, pluginId)

        val plugin = object : BasePlugin() { }
        fixtureTransaction = hostServices.beginPluginLoad(pluginId, fixtureMarker!!.absolutePath, plugin)
        plugin.registerMainAPI(fixtureApi)
        fixtureTransaction!!.commit()
        seedLoadedPluginHandle(plugin, fixtureMarker!!, fixtureTransaction!!, pluginId)
        assertEquals("the real registry publishes Home metadata for the fake provider",
            Cs3ProviderRegistry.MetadataReadiness.READY,
            registry.awaitProviderMetadata(profileId, pluginId, 0, 5_000L))

        val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
        fixtureSelectionWritten = true
        selectionStore.setOrderedProviderIds(profileId, listOf(providerId))
        selectionStore.setSelectedProviderIds(profileId, listOf(providerId), listOf(providerId))
        onMain { invokeRender(shell!!) }

        await("the selected provider Home shelf must arrive") {
            findText(shell!!, SHELF_TITLE) != null
        }
        await("Home requests background details for only its selected hero title") {
            var heroBackgroundReady = false
            onMain {
                heroBackgroundReady = fixtureApi.detailsRequests.isNotEmpty()
                    && (readField(shell!!, "homeFeaturedDetailsState")?.let {
                        readField(it, "backgroundPosterUrl") == HERO_BACKGROUND_URL
                    } == true)
            }
            heroBackgroundReady
        }
        assertEquals("the selected hero should load one provider title's metadata",
            listOf("$FIXTURE_TITLE_URL/short"), fixtureApi.detailsRequests.toList())
        val heroArtworkTags = ArrayList<String>()
        onMain {
            val heroState = readField(shell!!, "homeFeaturedDetailsState")
                ?: throw AssertionError("the selected Home hero must retain its artwork state")
            val heroOverlay = readField(heroState, "currentOverlay") as? View
                ?: throw AssertionError("the selected hero must retain its backdrop view")
            val heroFrame = heroOverlay.parent as? ViewGroup
                ?: throw AssertionError("the selected hero artwork must remain mounted")
            for (index in 0 until heroFrame.childCount) {
                val tag = heroFrame.getChildAt(index).tag as? String
                if (tag != null && tag.startsWith("orbit-artwork:")) heroArtworkTags.add(tag)
            }
        }
        assertTrue("background artwork overlays the poster while preserving poster fallback; "
                + "targets=$heroArtworkTags",
            heroArtworkTags.size >= 2)
        onMain { invokeRender(shell!!) }
        instrumentation.waitForIdleSync()
        assertEquals("recomposing Home with a fresh CS3 adapter must not reload hero details",
            1, fixtureApi.detailsRequests.size)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(750L)
        assertEquals("attaching a short Home rail must not fetch its next page automatically",
            listOf("1:$MAIN_PAGE_NAME"), fixtureApi.homeRequests.toList())
        val rail = findView(shell!!.window.decorView) {
            it is RecyclerView && it.tag is String
                && (it.tag as String).startsWith("provider_rail_view_")
                && it.adapter?.itemCount == HOME_ITEM_COUNT
        } as? RecyclerView ?: throw AssertionError("the provider Home rail must be mounted")
        onMain {
            rail.layoutDirection = View.LAYOUT_DIRECTION_RTL
            rail.requestLayout()
        }
        instrumentation.waitForIdleSync()
        var observedScrollDx = 0
        var laterAdapterItemsWereVisible = false
        onMain {
            rail.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    observedScrollDx += dx
                    val count = recyclerView.adapter?.itemCount ?: 0
                    val layoutManager = recyclerView.layoutManager
                    if (layoutManager != null) {
                        laterAdapterItemsWereVisible = (maxOf(0, count - 7) until count).any {
                            layoutManager.findViewByPosition(it) != null
                        }
                    }
                }
            })
            rail.scrollBy(-100_000, 0)
        }
        assertTrue("the RTL fixture must move left, observed dx=$observedScrollDx",
            observedScrollDx < 0)
        assertTrue("the RTL scroll must expose later adapter items", laterAdapterItemsWereVisible)
        await("scrolling toward the end of an RTL Home rail loads its next page") {
            fixtureApi.homeRequests.size == 2
        }
        assertEquals("one user scroll loads exactly one Home continuation",
            listOf("1:$MAIN_PAGE_NAME", "2:$MAIN_PAGE_NAME"), fixtureApi.homeRequests.toList())
        val initialBatch = readField(shell!!, "homeProviderBatch")
        assertNotNull("Home must own a loaded provider batch", initialBatch)
        assertEquals("the provider receives one initial Home request and one continuation",
            listOf("1:$MAIN_PAGE_NAME", "2:$MAIN_PAGE_NAME"),
            fixtureApi.homeRequests.toList())

        clickRoute("nav_SEARCH", ShellRoute.SEARCH)
        clickRoute("nav_LIBRARY", ShellRoute.LIBRARY)
        clickRoute("nav_HOME", ShellRoute.HOME)
        assertHomeFeedStillRetained(initialBatch)

        // Exercise the ordinary stop/start path used when Orbit loses the foreground. onStop
        // clears pending UI callbacks, so onStart must refresh the retained batch snapshot.
        invokeLifecycle("onStop")
        invokeLifecycle("onStart")
        await("the retained feed must be visible again after Activity start") {
            findText(shell!!, SHELF_TITLE) != null
        }
        assertHomeFeedStillRetained(initialBatch)

        // Match the player handoff branch: it preserves an open Details surface while Orbit
        // is stopped, then Home/Search/Library navigation happens after the player returns.
        onMain {
            setField(shell!!, "providerTitleDetailsOpen", true)
            setField(shell!!, "providerDetailsHandoffPending", true)
        }
        invokeLifecycle("onStop")
        invokeLifecycle("onStart")
        onMain {
            setField(shell!!, "providerTitleDetailsOpen", false)
            setField(shell!!, "providerDetailsHandoffPending", false)
        }
        clickRoute("nav_SEARCH", ShellRoute.SEARCH)
        clickRoute("nav_LIBRARY", ShellRoute.LIBRARY)
        clickRoute("nav_HOME", ShellRoute.HOME)
        assertHomeFeedStillRetained(initialBatch)

        val activityBeforeOrientationChange = shell!!
        val previousRequestedOrientation = activityBeforeOrientationChange.requestedOrientation
        val previousOrientation = activityBeforeOrientationChange.resources.configuration.orientation
        val expectedOrientation = if (previousOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
        val requestedOrientation = if (expectedOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val previousRouteScroll = findTagged(activityBeforeOrientationChange, "route_page_scroll")
        assertNotNull("the current route scroll view is mounted before rotation", previousRouteScroll)
        try {
            onMain { activityBeforeOrientationChange.requestedOrientation = requestedOrientation }
            await("orientation changes without recreating the shell Activity") {
                !activityBeforeOrientationChange.isDestroyed
                    && activityBeforeOrientationChange.resources.configuration.orientation == expectedOrientation
            }
            await("the shell remounts responsive route content for the new orientation") {
                findTagged(activityBeforeOrientationChange, "route_page_scroll") !== previousRouteScroll
                    && findText(activityBeforeOrientationChange, SHELF_TITLE) != null
            }
            assertFalse("orientation changes keep the existing Activity instance alive",
                activityBeforeOrientationChange.isDestroyed)
            assertSame("orientation changes retain the same Home provider batch", initialBatch,
                readField(activityBeforeOrientationChange, "homeProviderBatch"))
            assertEquals("orientation changes do not repeat Home requests", 2,
                fixtureApi.homeRequests.size)
        } finally {
            if (!activityBeforeOrientationChange.isDestroyed) {
                onMain {
                    activityBeforeOrientationChange.requestedOrientation = previousRequestedOrientation
                }
            }
        }

        invokeMetadataChanged("unrelated-profile")
        assertEquals("metadata for another profile does not reload this Home feed", 2,
            fixtureApi.homeRequests.size)

        invokeMetadataChanged(profileId)
        await("a real provider metadata change must refresh Home") {
            fixtureApi.homeRequests.size == 3 && findText(shell!!, SHELF_TITLE) != null
        }
        assertEquals("the metadata refresh starts one replacement request", 3,
            fixtureApi.homeRequests.size)
        assertTrue("the metadata refresh replaces the old Home batch",
            readField(shell!!, "homeProviderBatch") !== initialBatch)
    }

    @After
    fun rollbackFixtureAndRestorePreferences() {
        val activity = shell
        if (activity != null && !activity.isDestroyed) {
            onMain { activity.finish() }
            await("the fixture shell must finish before provider rollback") { activity.isDestroyed }
        }

        var cleanupFailure: Throwable? = null
        try {
            if (fixtureTransaction != null) {
                registry.blockPluginCalls(FIXTURE_PLUGIN_ID)
                assertTrue("fixture provider calls drain before rollback",
                    registry.pauseAndAwaitPluginCalls(FIXTURE_PLUGIN_ID, PROVIDER_DRAIN_TIMEOUT_MS))
                assertTrue("all provider work drains before rollback",
                    registry.awaitAllProviderWorkQuiesced(PROVIDER_DRAIN_TIMEOUT_MS))
                assertTrue("provider snapshots drain before rollback",
                    registry.awaitProviderSnapshotsQuiesced(PROVIDER_DRAIN_TIMEOUT_MS))
                if (runtime.isLoaded(FIXTURE_PLUGIN_ID)) {
                    assertTrue("the runtime releases its fixture handle",
                        runtime.deactivateIfClean(FIXTURE_PLUGIN_ID))
                } else {
                    fixtureTransaction!!.rollback()
                    registry.onPluginCleanupFinished(FIXTURE_PLUGIN_ID)
                }
                fixtureTransaction = null
            }
        } catch (error: Throwable) {
            cleanupFailure = error
        }

        try {
            fixtureMarker?.let { marker ->
                assertTrue("the exact marker file is removed", marker.delete() || !marker.exists())
                val directory = marker.parentFile
                assertTrue("the fixture marker directory is removed",
                    directory == null || directory.delete() || !directory.exists())
            }
            if (originalRuntimeProfileId != runtime.activeProfileId) {
                val failures = runtime.reconcileProfile(originalRuntimeProfileId, extensionManager)
                assertTrue("the original runtime profile is restored", failures.isEmpty())
            }
            if (fixtureExtensionWritten && extensionSnapshot != null) {
                restorePreferences(extensionSnapshot!!, extensionPreferences)
            }
            if (fixtureSelectionWritten && providerSnapshot != null) {
                restorePreferences(providerSnapshot!!, providerPreferences)
            }
            if (fixtureProfileWritten && profileSnapshot != null) {
                restorePreferences(profileSnapshot!!, profilePreferences)
            }
            assertFalse("the test never enables the CS3 canary", BuildConfig.CS3_CANARY_BUILD)
        } catch (error: Throwable) {
            val previous = cleanupFailure
            if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
        }
        cleanupFailure?.let { throw AssertionError("Home retention fixture cleanup failed", it) }
    }

    private fun assertHomeFeedStillRetained(initialBatch: Any?) {
        await("Home returns with its provider shelf") {
            shellState(shell!!) == ShellRoute.HOME && findText(shell!!, SHELF_TITLE) != null
        }
        assertSame("route changes retain the same provider batch", initialBatch,
            readField(shell!!, "homeProviderBatch"))
        assertEquals("route changes do not reload the selected provider after pagination", 2,
            fixtureApi.homeRequests.size)
    }

    private fun clickRoute(tag: String, expected: ShellRoute) {
        onMain {
            val routeButton = findTagged(shell!!, tag)
            assertNotNull("$tag remains available", routeButton)
            assertTrue("$tag accepts the route change", routeButton!!.performClick())
        }
        await("$expected route opens") { shellState(shell!!) == expected }
    }

    private fun invokeLifecycle(name: String) {
        val method = OrbitShellActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        onMain { method.invoke(shell!!) }
    }

    private fun invokeMetadataChanged(profileId: String) {
        val method = OrbitShellActivity::class.java.getDeclaredMethod(
            "onProviderMetadataChanged", String::class.java)
        method.isAccessible = true
        onMain { method.invoke(shell!!, profileId) }
    }

    private fun shellState(activity: OrbitShellActivity): ShellRoute {
        val state = readField(activity, "shellState") ?: throw AssertionError("shell state is missing")
        val route = state.javaClass.getMethod("getRoute").invoke(state)
        return route as ShellRoute
    }

    private fun invokeRender(activity: OrbitShellActivity) {
        OrbitShellActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
            .invoke(activity)
    }

    private fun addFixtureExtensionGrant(profileId: String, pluginId: String) {
        val installedKey = "installed_plugins_json"
        val enabledKey = "profile_enabled_plugins_json"
        val installed = JSONArray(extensionPreferences.getString(installedKey, "[]"))
        for (index in 0 until installed.length()) {
            assertFalse("the fixture plugin ID must not collide with installed metadata",
                pluginId == installed.optJSONObject(index)?.optString("id"))
        }
        installed.put(JSONObject()
            .put("id", pluginId)
            .put("repositoryUrl", FIXTURE_REPOSITORY_URL)
            .put("name", PROVIDER_NAME)
            .put("internalName", PROVIDER_NAME)
            .put("hasMainPage", true)
            .put("version", 1L)
            .put("sha256", "0".repeat(64))
            .put("hashWasPublished", false)
            .put("pluginClassName", "fixture.HomeFeedRetentionNeverLoaded")
            .put("requiresResources", false)
            .put("fileName", "home-feed-retention-fixture.cs3")
            .put("activationError", JSONObject.NULL)
            .put("installedAt", 1L))

        val enabled = JSONObject(extensionPreferences.getString(enabledKey, "{}") ?: "{}")
        val profileIds = enabled.optJSONArray(profileId) ?: JSONArray()
        profileIds.put(pluginId)
        enabled.put(profileId, profileIds)
        assertTrue("fixture metadata and its disposable profile grant are stored",
            extensionPreferences.edit()
                .putString(installedKey, installed.toString())
                .putString(enabledKey, enabled.toString())
                .commit())
        assertNotNull("the metadata-only provider appears installed",
            extensionManager.getInstalledPlugin(pluginId))
        assertTrue("the fixture provider is enabled for its disposable profile",
            extensionManager.isEnabledForProfile(profileId, pluginId))
    }

    private fun seedLoadedPluginHandle(
        plugin: BasePlugin,
        markerFile: File,
        transaction: OrbitCs3HostServices.LoadTransaction,
        pluginId: String,
    ) {
        val loadedType = Class.forName("com.auras.orbit.extensions.CS3PluginRuntime\$LoadedPlugin")
        val constructor = loadedType.getDeclaredConstructor(
            Any::class.java,
            dalvik.system.PathClassLoader::class.java,
            File::class.java,
            OrbitCs3HostServices.LoadTransaction::class.java,
            android.content.res.AssetManager::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val loaded = constructor.newInstance(plugin, null, markerFile, transaction, null, pluginId)
        @Suppress("UNCHECKED_CAST")
        val loadedPlugins = CS3PluginRuntime::class.java.getDeclaredField("loadedPlugins")
            .apply { isAccessible = true }.get(runtime) as MutableMap<String, Any>
        loadedPlugins[pluginId] = loaded
    }

    private fun snapshotPreferences(name: String, preferences: SharedPreferences): PreferenceSnapshot {
        val file = preferencesFile(name)
        return PreferenceSnapshot(name, preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }, file.isFile, file.takeIf { it.isFile }?.readBytes())
    }

    private fun restorePreferences(snapshot: PreferenceSnapshot, preferences: SharedPreferences) {
        val editor = preferences.edit().clear()
        for ((key, value) in snapshot.values) when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> throw AssertionError("unsupported preference type ${value?.javaClass?.name} for $key")
        }
        assertTrue("${snapshot.name} preferences restore synchronously", editor.commit())
        val file = preferencesFile(snapshot.name)
        if (snapshot.existed) {
            FileOutputStream(file, false).use { output ->
                output.write(snapshot.bytes!!)
                output.flush()
                output.fd.sync()
            }
        } else {
            assertTrue("a fixture-created preference file is removed",
                target.deleteSharedPreferences(snapshot.name))
        }
    }

    private fun preferencesFile(name: String) =
        File(target.applicationInfo.dataDir, "shared_prefs/$name.xml")

    private fun readField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun setField(instance: Any, name: String, value: Any?) {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                type.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun findTagged(activity: OrbitShellActivity, tag: String): View? =
        findView(activity.window.decorView) { it.tag == tag }

    private fun findText(activity: OrbitShellActivity, text: String): TextView? =
        findView(activity.window.decorView) { it is TextView && it.text?.toString() == text } as? TextView

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findView(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50L)
        }
        assertTrue(message, predicate())
    }

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
    }

    private data class PreferenceSnapshot(
        val name: String,
        val values: Map<String, Any?>,
        val existed: Boolean,
        val bytes: ByteArray?,
    )

    private class RetentionMainApi : MainAPI() {
        val homeRequests = CopyOnWriteArrayList<String>()
        val detailsRequests = CopyOnWriteArrayList<String>()
        @Volatile var firstHomePageHasNext = false

        override fun getName(): String = PROVIDER_NAME
        override fun getMainUrl(): String = FIXTURE_PROVIDER_URL
        override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Movie)
        override fun getHasMainPage(): Boolean = true
        override fun getMainPage(): List<MainPageData> =
            listOf(MainPageData(MAIN_PAGE_NAME, "home-retention-feed", true))

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            homeRequests.add("$page:${request.name}")
            val shortItem = MovieSearchResponse(
                "$TITLE Short", "$FIXTURE_TITLE_URL/short", PROVIDER_NAME, TvType.Movie,
                "https://images.fixture.invalid/hero-poster.jpg", 2026, null, null, emptyMap(), null,
            )
            val items = (0 until HOME_ITEM_COUNT).map { index ->
                MovieSearchResponse(
                    "$TITLE $index", "$FIXTURE_TITLE_URL/$index", PROVIDER_NAME, TvType.Movie,
                    null, 2026, null, null, emptyMap(), null,
                )
            }
            return HomePageResponse(listOf(
                HomePageList(SHELF_TITLE, listOf(shortItem), true),
                HomePageList(LONG_SHELF_TITLE, items, true),
            ),
                page == 1 && firstHomePageHasNext)
        }

        override suspend fun load(url: String): LoadResponse? {
            detailsRequests.add(url)
            return MovieLoadResponse(
                "$TITLE Short", url, PROVIDER_NAME, TvType.Movie, url,
                "https://images.fixture.invalid/poster.jpg", 2026, null, null,
                emptyList(), null, emptyList(), emptyList(), emptyList(), false,
                emptyMap(), emptyMap(), HERO_BACKGROUND_URL, null, null, url,
            )
        }
    }

    private val fixtureApi = RetentionMainApi()

    companion object {
        private const val FIXTURE_PLUGIN_ID = "orbit-home-feed-route-retention-fixture"
        private const val PROVIDER_ID_PREFIX = "cs3:"
        private const val FIXTURE_DIRECTORY_NAME = "home-feed-route-retention-fixture"
        private const val PROVIDER_NAME = "Home Retention Fixture"
        private const val FIXTURE_REPOSITORY_URL = "https://example.invalid/home-retention-repository.json"
        private const val FIXTURE_PROVIDER_URL = "https://home-retention.example"
        private const val FIXTURE_TITLE_URL = "https://home-retention.example/title"
        private const val MAIN_PAGE_NAME = "Home Retention"
        private const val SHELF_TITLE = "Retained Route Shelf"
        private const val LONG_SHELF_TITLE = "Retained Long Route Shelf"
        private const val TITLE = "Retained Provider Title"
        private const val HERO_BACKGROUND_URL = "https://images.fixture.invalid/hero-background.jpg"
        private const val HOME_ITEM_COUNT = 32
        private const val EXTENSION_PREFERENCES = "orbit_cloudstream_extensions_v1"
        private const val PROVIDER_PREFERENCES = "orbit_provider_selection_v1"
        private const val PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1"
        private const val PROVIDER_DRAIN_TIMEOUT_MS = 5_000L
    }
}
