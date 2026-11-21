package com.auras.orbit.providers

import android.app.Instrumentation
import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.C
import androidx.media3.common.Player
import com.auras.orbit.BuildConfig
import com.auras.orbit.OrbitApplication
import com.auras.orbit.extensions.CloudStreamExtensionManager
import com.auras.orbit.extensions.CS3PluginRuntime
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.auras.orbit.navigation.ShellRoute
import com.auras.orbit.navigation.ShellState
import com.auras.orbit.library.LocalLibraryStore
import com.auras.orbit.player.OrbitPlayerActivity
import com.auras.orbit.player.PlaybackRequest
import com.auras.orbit.profile.LocalProfileStore
import com.auras.orbit.profile.ProviderSelectionStore
import com.auras.orbit.ui.OrbitShellActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.fcast.FcastAction
import com.lagradost.cloudstream3.actions.temp.fcast.FcastManager
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fixture-only CS3 registration through Orbit's real registry and shell. The fake mirrors the
 * local pinned CloudStream MainAPI contract (stable 3796406e / pre-release 5024ace); every
 * callback returns in-memory values and no .cs3 archive or provider URL is opened.
 */
@RunWith(AndroidJUnit4::class)
class Cs3ProviderFlowInstrumentationTest {
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
    private lateinit var extensionSnapshot: PreferenceSnapshot
    private lateinit var providerSnapshot: PreferenceSnapshot
    private lateinit var providerRuntimeSnapshot: PreferenceSnapshot
    private lateinit var profileSnapshot: PreferenceSnapshot
    private lateinit var librarySnapshot: FileSnapshot
    private lateinit var libraryRuntimeFiles: FileSnapshot
    private lateinit var libraryRuntimeSnapshot: LibraryLogicalSnapshot
    private lateinit var apiSnapshot: List<MainAPI>
    private var originalRuntimeProfileId: String? = null
    private var activeProfileId: String? = null
    private var shell: OrbitShellActivity? = null
    private var fakeApis: List<MainAPI> = emptyList()
    private var transaction: OrbitCs3HostServices.LoadTransaction? = null
    private var fixtureMarker: File? = null
    private var fixturePackagePath: File? = null
    private var fixtureOutputDirectory: File? = null
    private var extensionFixtureWritten = false
    private var selectionFixtureWritten = false
    private var profileFixtureWritten = false
    private var mediaServer: LoopbackMediaFixtureServer? = null
    @Volatile private var deferredMediaServerClose: Thread? = null
    private var playbackMonitor: Instrumentation.ActivityMonitor? = null
    @Volatile private var playbackActivity: OrbitPlayerActivity? = null
    private var playbackLifecycleCallback: Application.ActivityLifecycleCallbacks? = null
    @Volatile private var playbackLaunchAttempted = false
    @Volatile private var playbackExpectingAnotherLaunch = false
    @Volatile private var latePlaybackRollbackFailure: Throwable? = null
    private var expectedPlaybackSourceUri: String? = null
    private var expectedPlaybackTitle: String? = null
    private var expectedPlaybackProviderId: String? = null
    private var playbackProgressSnapshot: Map<PlaybackProgressKey, ContentValues> = emptyMap()
    private var savedTitleSnapshot: Map<PlaybackProgressKey, ContentValues> = emptyMap()
    private var collectionSnapshot: List<ContentValues> = emptyList()
    private var collectionMembershipSnapshot: List<ContentValues> = emptyList()
    private var playbackLibraryMutationAllowed = false
    private var populatedLibraryFixtureSeeded = false
    private var realFcastAcceptanceEnabled = false
    private var episodeProgressiveFixture: FixtureSeriesApi? = null

    private fun registerPlaybackLifecycleCallback() {
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                captureFixturePlaybackActivity(activity)
            }
            override fun onActivityStarted(activity: Activity) = captureFixturePlaybackActivity(activity)
            override fun onActivityResumed(activity: Activity) = captureFixturePlaybackActivity(activity)
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is OrbitPlayerActivity && activity === playbackActivity) {
                    try {
                        restoreLibrarySnapshotsAfterPlaybackStops()
                        assertFalse("late playback rollback must restore the captured tables",
                            playbackLibraryMutationAllowed)
                        latePlaybackRollbackFailure = null
                        playbackLaunchAttempted = false
                    } catch (error: Throwable) {
                        latePlaybackRollbackFailure = error
                        Log.e(TAG, "LATE_PLAYBACK_ROLLBACK_ERROR", error)
                    } finally {
                        if (!playbackExpectingAnotherLaunch) closeMediaServerAfterLatePlayback()
                    }
                    val registered = playbackLifecycleCallback
                    if (registered != null && !playbackLaunchAttempted
                        && !playbackExpectingAnotherLaunch) {
                        application.unregisterActivityLifecycleCallbacks(registered)
                        playbackLifecycleCallback = null
                    }
                }
            }
        }
        playbackLifecycleCallback = callback
        application.registerActivityLifecycleCallbacks(callback)
    }

    @Before
    fun prepareIsolatedOrbitAndCaptureState() {
        target = instrumentation.targetContext
        assertEquals("provider flow must run in the isolated queueRender application",
            "com.auras.orbit.queuerender", target.packageName)
        assertFalse("provider fixture must not enable the CS3 canary", BuildConfig.CS3_CANARY_BUILD)
        application = target.applicationContext as OrbitApplication
        registerPlaybackLifecycleCallback()
        profileStore = application.localProfileStore
        selectionStore = application.providerSelectionStore
        extensionManager = application.cloudStreamExtensionManager
        runtime = application.cS3PluginRuntime
        hostServices = OrbitCs3HostServices.get()
        registry = application.cs3ProviderRegistry
        assertTrue("the accepted target plugin crosswalk is enabled",
            runtime.isTargetPluginCrosswalkValidated)
        assertTrue("extension storage must be recovered before adding fixture metadata",
            extensionManager.isStorageRecoveryReady)

        extensionPreferences = readField(extensionManager, "preferences") as SharedPreferences
        providerPreferences = readField(selectionStore, "preferences") as SharedPreferences
        profilePreferences = readField(profileStore, "preferences") as SharedPreferences
        extensionSnapshot = snapshotPreferences(EXTENSION_PREFERENCES, extensionPreferences)
        providerSnapshot = snapshotPreferences(PROVIDER_PREFERENCES, providerPreferences)
        profileSnapshot = snapshotPreferences(PROFILE_PREFERENCES, profilePreferences)
        librarySnapshot = snapshotLibraryFiles()
        playbackProgressSnapshot = snapshotLibraryRows("playback_progress")
        savedTitleSnapshot = snapshotLibraryRows("saved_titles")
        collectionSnapshot = snapshotLibraryTable("collections")
        collectionMembershipSnapshot = snapshotLibraryTable("collection_memberships")
        apiSnapshot = ArrayList(APIHolder.INSTANCE.allProviders)
        originalRuntimeProfileId = runtime.activeProfileId

        // Every run gets a profile with a new ID so pre-existing personal library rows cannot
        // affect the locked fixture counts or be overwritten by its stable title IDs.
        profileFixtureWritten = true
        val chosen = profileStore.createProfile("Orbit Provider Fixture")
            ?: error("the isolated package could not create its disposable fixture profile")
        activeProfileId = chosen.id
        profileStore.selectProfile(chosen.id)

        val launch = android.content.Intent(target, OrbitShellActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = instrumentation.startActivitySync(launch)
        assertTrue("the fixture must launch the actual OrbitShellActivity",
            launched is OrbitShellActivity)
        shell = launched as OrbitShellActivity
        assertTrue("queueRender runs the real OrbitApplication", shell!!.application is OrbitApplication)
        continueWithSelectedProfile(shell!!)
        await("the runtime profile must be reconciled without plugin activation") {
            runtime.activeProfileId == activeProfileId
        }
        onMain {
            shellState(shell).navigateTo(ShellRoute.HOME)
            invokeRender(shell!!)
        }
        await("the shell must be on Home before provider registration") {
            shellState(shell).route == ShellRoute.HOME && findTagged(shell!!, "nav_HOME") != null
        }
        assertEquals("the test starts from the Home route", ShellRoute.HOME,
            shellState(shell).route)
        providerRuntimeSnapshot = snapshotPreferences(PROVIDER_PREFERENCES, providerPreferences)
        logProviderPreferenceState("runtime-baseline")
        libraryRuntimeFiles = snapshotLibraryFiles()
        libraryRuntimeSnapshot = snapshotLibraryLogicalState()
        Log.i(TAG, "LIBRARY_RUNTIME_BASELINE rawFiles=${libraryRuntimeFiles.files} " +
            "logical=${libraryRuntimeSnapshot.summary} sha256=${libraryRuntimeSnapshot.digest}")
        Log.i(TAG, "STATE_BEFORE ${stateSummary()}")
    }

    @Test
    fun providerTitleSearchPrecedesManualTorrentEntry() {
        val activity = shell ?: error("OrbitShellActivity was not retained")
        onMain {
            val searchNav = findTagged(activity, "nav_SEARCH")
            assertNotNull("universal Search navigation is available", searchNav)
            assertTrue("the Search route opens", searchNav!!.performClick())
        }
        await("Search exposes its provider query and manual torrent input") {
            shellState(activity).route == ShellRoute.SEARCH
                && findTagged(activity, "search_query") is EditText
                && findTagged(activity, "torrent_magnet_input") is EditText
        }
        val providerQuery = findTagged(activity, "search_query") as EditText
        val torrentInput = findTagged(activity, "torrent_magnet_input") as EditText
        assertTrue("provider title search precedes manual torrent entry on Search",
            locationY(providerQuery) < locationY(torrentInput))
    }

    @Test
    fun fakeCs3ProviderFlowsThroughHomeUniversalSearchAndDetails() {
        try {
        val sourceSnapshot = InstrumentationRegistry.getArguments()
        val shellSourceSha256 = sourceSnapshot.getString("orbitShellSourceSha256").orEmpty()
        val testSourceSha256 = sourceSnapshot.getString("orbitTestSourceSha256").orEmpty()
        val sourceSnapshotRequired = sourceSnapshot.getString("orbitSourceSnapshotRequired") == "true"
        realFcastAcceptanceEnabled = sourceSnapshot.getString("realFcast")
            ?.equals("true", ignoreCase = true) == true
        Log.i(TAG, "SOURCE_SNAPSHOT OrbitShellActivity.java=$shellSourceSha256 " +
            "Cs3ProviderFlowInstrumentationTest.kt=$testSourceSha256")
        if (sourceSnapshotRequired) {
            assertTrue("the exact OrbitShellActivity source hash must be attached to this run",
                SOURCE_SHA256_PATTERN.matches(shellSourceSha256))
            assertTrue("the exact instrumentation test source hash must be attached to this run",
                SOURCE_SHA256_PATTERN.matches(testSourceSha256))
        }
        val profileId = activeProfileId ?: error("fixture profile was not prepared")
        val pluginId = FIXTURE_PLUGIN_ID
        assertNull("the fixture plugin ID must not already be installed",
            extensionManager.getInstalledPlugin(pluginId))
        assertFalse("the fixture plugin must not already own a registration",
            hostServices.hasOutstandingRegistrations(pluginId))
        assertFalse("the fixture plugin must not already own a runtime handle",
            runtime.isLoaded(pluginId))
        assertFalse("APIHolder must not already contain a fixture provider",
            APIHolder.INSTANCE.allProviders.any { it is FixtureMainApi })

        val markerRoot = File(target.cacheDir, FIXTURE_DIRECTORY_NAME)
        assertTrue("fixture marker directory must be app-private", markerRoot.mkdirs() || markerRoot.isDirectory)
        fixtureMarker = File(markerRoot, "registration.fixture-marker")
        assertTrue("the non-package registration marker must be created", fixtureMarker!!.createNewFile())
        fixtureOutputDirectory = createScreenshotDirectory()

        val movieAUrl = MOVIE_A_URL
        mediaServer = LoopbackMediaFixtureServer(instrumentation.context)
        val queriedSourceUrl = mediaServer!!.url("query-source.mp4?fixture=local-only")
        val subtitleUrl = mediaServer!!.url("captions.en.vtt")
        val selectedSourceUrl = mediaServer!!.url(
            if (realFcastAcceptanceEnabled) "fcast-source.mp4" else "selected-source.mp4")
        val coastArtworkUrl = mediaServer!!.url("provider-artwork/home-coast.png")
        val miraArtworkUrl = mediaServer!!.url("provider-artwork/mira.png")
        val hannahArtworkUrl = mediaServer!!.url("provider-artwork/hannah.png")
        val additionalSearchMovies = listOf(
            FixtureMovie(MOVIE_C_TITLE, MOVIE_C_URL, coastArtworkUrl),
            FixtureMovie(MOVIE_D_TITLE, MOVIE_D_URL, hannahArtworkUrl),
            FixtureMovie(MOVIE_E_TITLE, MOVIE_E_URL, miraArtworkUrl),
            FixtureMovie(MOVIE_F_TITLE, MOVIE_F_URL, coastArtworkUrl),
        )
        val provider = FixtureMainApi(
            movieAUrl,
            mediaServer!!.url("first-source.mp4"),
            selectedSourceUrl,
            queriedSourceUrl,
            subtitleUrl,
            coastArtworkUrl,
            miraArtworkUrl,
            additionalSearchMovies,
            pauseAfterFirstLinkCallback = !realFcastAcceptanceEnabled,
        )
        val secondaryProvider = FixtureMainApi(
            SECONDARY_MOVIE_G_URL,
            mediaServer!!.url("first-source.mp4"),
            selectedSourceUrl,
            queriedSourceUrl,
            subtitleUrl,
            coastArtworkUrl,
            hannahArtworkUrl,
            emptyList(),
            providerName = SECONDARY_PROVIDER_NAME,
            providerUrl = SECONDARY_PROVIDER_URL,
            movieATitle = SECONDARY_MOVIE_G_TITLE,
            movieBTitle = SECONDARY_MOVIE_H_TITLE,
            movieBUrl = SECONDARY_MOVIE_H_URL,
            shelfATitle = SECONDARY_SHELF_A_TITLE,
            shelfBTitle = SECONDARY_SHELF_B_TITLE,
            homeRequestAName = SECONDARY_HOME_REQUEST_A_NAME,
            homeRequestBName = SECONDARY_HOME_REQUEST_B_NAME,
        )
        fakeApis = listOf(provider, secondaryProvider)
        extensionFixtureWritten = true
        addFixtureExtensionGrant(profileId, pluginId)
        fixturePackagePath = File(extensionManager.getInstalledPlugin(pluginId)!!.filePath)
        assertFalse("the metadata-only fixture must not create a .cs3 package", fixturePackagePath!!.exists())
        val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
        val secondaryProviderId = "$PROVIDER_ID_PREFIX$pluginId:provider-1"
        selectionFixtureWritten = true
        val selectedProviderIds = listOf(providerId, secondaryProviderId)
        selectionStore.setOrderedProviderIds(profileId, selectedProviderIds)
        selectionStore.setSelectedProviderIds(profileId, selectedProviderIds, selectedProviderIds)

        val fakePlugin = object : BasePlugin() { }
        transaction = hostServices.beginPluginLoad(pluginId, fixtureMarker!!.absolutePath, fakePlugin)
        fakePlugin.registerMainAPI(provider)
        fakePlugin.registerMainAPI(secondaryProvider)
        transaction!!.commit()
        seedLoadedPluginHandle(fakePlugin, fixtureMarker!!, transaction!!, pluginId)
        assertTrue("the fixture must retain its committed plugin runtime handle", runtime.isLoaded(pluginId))

        val registered = APIHolder.INSTANCE.allProviders
        assertTrue("the exact fake MainAPI identity must be visible through APIHolder",
            registered.any { it === provider })
        assertTrue("the second fake MainAPI identity must be visible through APIHolder",
            registered.any { it === secondaryProvider })
        assertTrue("the provider registry must expose the committed fake registration",
            hostServices.committedProviderRegistrations.any {
                it.pluginId == pluginId && it.provider === provider && it.registrationIndex == 0
            })
        assertTrue("the provider registry must expose the second committed fake registration",
            hostServices.committedProviderRegistrations.any {
                it.pluginId == pluginId && it.provider === secondaryProvider && it.registrationIndex == 1
            })

        assertTrue("the synthetic MainAPI reports its Home capability", provider.hasMainPage)
        assertEquals("the registry publishes the fixture metadata before capability assertions",
            Cs3ProviderRegistry.MetadataReadiness.READY,
            registry.awaitProviderMetadata(profileId, pluginId, 0, 5_000L))
        assertEquals("the registry publishes the second fixture metadata before capability assertions",
            Cs3ProviderRegistry.MetadataReadiness.READY,
            registry.awaitProviderMetadata(profileId, pluginId, 1, 5_000L))
        val providers = application.getMediaProvidersForProfile(profileId)
        assertEquals("the real Cs3ProviderRegistry returns both fixtures in saved order", 2, providers.size)
        assertEquals(selectedProviderIds, providers.map { it.descriptor().id })
        assertEquals("both distinct fixture providers remain selected in profile order", selectedProviderIds,
            selectionStore.getSelectedProviderIds(profileId, selectedProviderIds))
        assertEquals(listOf(FIXTURE_PROVIDER_NAME, SECONDARY_PROVIDER_NAME),
            providers.map { it.descriptor().name })
        assertTrue("the first fixture exposes its Home callback", providers[0].descriptor().homeFeed)
        assertTrue("the first fixture exposes universal title Search", providers[0].descriptor().titleSearch)
        assertTrue("the first fixture exposes Movie Details", providers[0].descriptor().details)
        assertTrue("the second fixture exposes its Home callback", providers[1].descriptor().homeFeed)
        assertTrue("the second fixture exposes universal title Search", providers[1].descriptor().titleSearch)

        val fixtureCollections = seedPopulatedPersonalLibrary(profileId, providerId,
            miraArtworkUrl, hannahArtworkUrl,
            mediaServer!!.url("provider-artwork/home-coast.png"))

        val activity = shell ?: error("OrbitShellActivity was not retained")
        onMain { invokeRender(activity) }
        await("ordered fixture movie shelves must arrive on Home") {
            findText(activity, SHELF_A_TITLE) != null && findText(activity, SHELF_B_TITLE) != null
                && findText(activity, SECONDARY_SHELF_A_TITLE) != null
                && findText(activity, SECONDARY_SHELF_B_TITLE) != null
                && findText(activity, "From $FIXTURE_PROVIDER_NAME") != null
                && findText(activity, "From $SECONDARY_PROVIDER_NAME") != null
                && findText(activity, MOVIE_A_TITLE) != null && findText(activity, MOVIE_B_TITLE) != null
                && findText(activity, SECONDARY_MOVIE_G_TITLE) != null
                && findText(activity, SECONDARY_MOVIE_H_TITLE) != null
                && isVisibleLaidOut(findText(activity, SHELF_A_TITLE))
                && isVisibleLaidOut(findText(activity, SHELF_B_TITLE))
                && isVisibleLaidOut(findText(activity, SECONDARY_SHELF_A_TITLE))
                && isVisibleLaidOut(findText(activity, SECONDARY_SHELF_B_TITLE))
                && isVisibleLaidOut(findContentDescription(activity,
                    "$MOVIE_A_TITLE, from $FIXTURE_PROVIDER_NAME"))
                && isVisibleLaidOut(findContentDescription(activity,
                    "$MOVIE_B_TITLE, from $FIXTURE_PROVIDER_NAME"))
                && isVisibleLaidOut(findContentDescription(activity,
                    "$SECONDARY_MOVIE_G_TITLE, from $SECONDARY_PROVIDER_NAME"))
                && isVisibleLaidOut(findContentDescription(activity,
                    "$SECONDARY_MOVIE_H_TITLE, from $SECONDARY_PROVIDER_NAME"))
        }
        await("the featured Home title must use the deterministic coast artwork") {
            val hero = findContentDescription(activity, "$MOVIE_A_TITLE from $FIXTURE_PROVIDER_NAME")
            hero != null && findView(hero) { it is android.widget.ImageView }?.let {
                (it as android.widget.ImageView).drawable != null
            } == true && mediaServer!!.requests().any {
                it.path == "/provider-artwork/home-coast.png" && it.status == 200
            }
        }
        assertEquals("MainPage callbacks follow MainAPI.mainPage declaration order",
            listOf("1:Fixture Movies A", "1:Fixture Movies B"),
            provider.homeRequests.take(2))
        assertEquals("the second provider's Home callbacks keep their declared shelf order",
            listOf("1:$SECONDARY_HOME_REQUEST_A_NAME", "1:$SECONDARY_HOME_REQUEST_B_NAME"),
            secondaryProvider.homeRequests.take(2))
        val initialHomeRequests = provider.homeRequests.toList()
        val initialSecondaryHomeRequests = secondaryProvider.homeRequests.toList()
        val shelfA = findText(activity, SHELF_A_TITLE)!!
        val shelfB = findText(activity, SHELF_B_TITLE)!!
        val shelfC = findText(activity, SECONDARY_SHELF_A_TITLE)!!
        val shelfD = findText(activity, SECONDARY_SHELF_B_TITLE)!!
        val shelfASection = shelfA.parent as? ViewGroup
            ?: throw AssertionError("the first Home shelf heading must have a section container")
        val shelfBSection = shelfB.parent as? ViewGroup
            ?: throw AssertionError("the second Home shelf heading must have a section container")
        val shelfCSection = shelfC.parent as? ViewGroup
            ?: throw AssertionError("the third Home shelf heading must have a section container")
        val shelfDSection = shelfD.parent as? ViewGroup
            ?: throw AssertionError("the fourth Home shelf heading must have a section container")
        val homePage = shelfASection.parent as? ViewGroup
            ?: throw AssertionError("Home shelf headings must belong to the Home page")
        val shelfBPage = shelfBSection.parent as? ViewGroup
            ?: throw AssertionError("the second Home shelf heading must belong to the Home page")
        val shelfCPage = shelfCSection.parent as? ViewGroup
            ?: throw AssertionError("the third Home shelf heading must belong to the Home page")
        val shelfDPage = shelfDSection.parent as? ViewGroup
            ?: throw AssertionError("the fourth Home shelf heading must belong to the Home page")
        Log.i(TAG, "HOME_SHELF_LAYOUT " +
            "A=(x=${locationX(shelfA)},y=${locationY(shelfA)},rowIndex=${homePage.indexOfChild(shelfASection)}) " +
            "B=(x=${locationX(shelfB)},y=${locationY(shelfB)},rowIndex=${shelfBPage.indexOfChild(shelfBSection)}) " +
            "C=(x=${locationX(shelfC)},y=${locationY(shelfC)},rowIndex=${shelfCPage.indexOfChild(shelfCSection)}) " +
            "D=(x=${locationX(shelfD)},y=${locationY(shelfD)},rowIndex=${shelfDPage.indexOfChild(shelfDSection)}) " +
            "samePage=${homePage === shelfBPage && homePage === shelfCPage && homePage === shelfDPage}")
        val homeMovieA = findContentDescription(activity, "$MOVIE_A_TITLE, from $FIXTURE_PROVIDER_NAME")
        val homeMovieB = findContentDescription(activity, "$MOVIE_B_TITLE, from $FIXTURE_PROVIDER_NAME")
        val homeMovieG = findContentDescription(activity,
            "$SECONDARY_MOVIE_G_TITLE, from $SECONDARY_PROVIDER_NAME")
        val homeMovieH = findContentDescription(activity,
            "$SECONDARY_MOVIE_H_TITLE, from $SECONDARY_PROVIDER_NAME")
        assertNotNull("the first shelf movie is attributed to its provider", homeMovieA)
        assertNotNull("the second shelf movie is attributed to its provider", homeMovieB)
        assertNotNull("the third shelf movie is attributed to the second provider", homeMovieG)
        assertNotNull("the fourth shelf movie is attributed to the second provider", homeMovieH)
        scrollToTop(shelfA)
        captureScreenshot("home-featured-populated.png", listOf(
            "$MOVIE_A_TITLE from $FIXTURE_PROVIDER_NAME",
        ))
        captureScreenshot("home-top-populated.png", listOf(
            "$MOVIE_A_TITLE from $FIXTURE_PROVIDER_NAME",
        ))
        bringIntoView(shelfA)
        captureScreenshot("home.png", listOf(
            SHELF_A_TITLE,
            "$MOVIE_A_TITLE, from $FIXTURE_PROVIDER_NAME",
        ))
        assertTrue("Home renders CS3 shelves in declared order",
            homePage === shelfBPage
                && homePage.indexOfChild(shelfASection) < shelfBPage.indexOfChild(shelfBSection)
                && locationY(shelfA) < locationY(shelfB))
        assertTrue("the first Home movie precedes the second Home movie",
            locationY(homeMovieA!!) < locationY(homeMovieB!!))
        assertTrue("Home preserves selected provider order across source-attributed rails",
            homePage === shelfCPage && homePage === shelfDPage
                && homePage.indexOfChild(shelfBSection) < homePage.indexOfChild(shelfCSection)
                && homePage.indexOfChild(shelfCSection) < homePage.indexOfChild(shelfDSection)
                && locationY(shelfB) < locationY(shelfC) && locationY(shelfC) < locationY(shelfD)
                && locationY(homeMovieB!!) < locationY(homeMovieG!!)
                && locationY(homeMovieG) < locationY(homeMovieH!!))
        bringIntoView(shelfC)
        captureScreenshot("home-second-provider-rail.png", listOf(
            SECONDARY_SHELF_A_TITLE, "From $SECONDARY_PROVIDER_NAME",
            "$SECONDARY_MOVIE_G_TITLE, from $SECONDARY_PROVIDER_NAME",
        ))
        val continueWatchingHeading = findText(activity, "Continue Watching")
            ?: throw AssertionError("Home must show the Continue Watching section")
        val continueWatchingDescriptions = listOf(
            "Resume Deep Signal, SERIES · $FIXTURE_PROVIDER_NAME, S1 · E04 · The Signal Below, " +
                "60% watched, 24 min left",
            "Resume Signal / 08, SERIES · $FIXTURE_PROVIDER_NAME, S1 · E08 · Tomorrow’s Broadcast, " +
                "80% watched, 12 min left",
            "Resume Afterlight, SERIES · $FIXTURE_PROVIDER_NAME, S1 · E03 · The Blue Hour, " +
                "42% watched, 35 min left",
            "Resume The Northbound Line, SERIES · $FIXTURE_PROVIDER_NAME, S2 · E01 · The Long Crossing, " +
                "37% watched, 38 min left",
            "Resume Glass Harbor, SERIES · $FIXTURE_PROVIDER_NAME, S1 · E06 · Tides at Night, " +
                "68% watched, 19 min left",
            "Resume Silent Meridian, SERIES · $FIXTURE_PROVIDER_NAME, S1 · E02 · First Light, " +
                "90% watched, 6 min left",
            "Resume Paper Moons, SERIES · $FIXTURE_PROVIDER_NAME, S2 · E05 · The Fold, " +
                "55% watched, 27 min left",
            "Resume Atlas of Ash, SERIES · $FIXTURE_PROVIDER_NAME, S1 · E09 · A Map of Stars, " +
                "77% watched, 14 min left",
        )
        await("Home must render all eight deterministic mockup Continue Watching rows") {
            continueWatchingDescriptions.all { findContentDescription(activity, it) != null }
        }
        assertTrue("Continue Watching accessibility labels show a provider name, never an opaque provider ID",
            continueWatchingDescriptions.none { it.contains(providerId) })
        bringIntoView(continueWatchingHeading)
        val continueCardViews = continueWatchingDescriptions.map { description ->
            findContentDescription(activity, description)
                ?: throw AssertionError("Continue Watching card must be visible to Orbit's view tree: $description")
        }
        val visibleContinueCards = continueCardViews.count { view ->
            val rect = Rect()
            view.getGlobalVisibleRect(rect) && rect.width() >= view.width * 0.98f
        }
        val isTelevision = readField(activity, "isTelevision") as? Boolean == true
        if (!isTelevision) {
            val expectedPhoneCardWidthPx = Math.round(
                176f * activity.resources.displayMetrics.density)
            assertEquals("phone Continue Watching matches the accepted 176dp episode-card width",
                expectedPhoneCardWidthPx, continueCardViews.first().width)
        }
        val continueCards = continueCardViews.first().parent as ViewGroup
        val continueRail = continueCards.parent as HorizontalScrollView
        val firstContinueCard = continueCardViews.first()
        val continueCardGapPx =
            (firstContinueCard.layoutParams as ViewGroup.MarginLayoutParams).rightMargin
        val usableContinueRailWidthPx = continueRail.width - continueCards.paddingLeft
        val expectedVisibleContinueCards = maxOf(1,
            (usableContinueRailWidthPx + continueCardGapPx)
                / (firstContinueCard.width + continueCardGapPx))
        assertEquals("Continue Watching fills every complete card that fits the phone or TV rail",
            expectedVisibleContinueCards, visibleContinueCards)
        assertEquals("the local profile holds all eight mockup Continue Watching titles", 8,
            application.localLibraryStore.getContinueWatching(profileId, 8).count {
                it.stableId in FIXTURE_CONTINUE_TITLE_IDS
            })
        captureScreenshot("home-continue-watching-populated.png",
            listOf("Continue Watching") + continueWatchingDescriptions.take(expectedVisibleContinueCards))
        bringIntoView(continueCardViews.last())
        captureScreenshot("home-continue-watching-tail.png",
            continueWatchingDescriptions.takeLast(expectedVisibleContinueCards))

        onMain {
            val libraryNav = findTagged(activity, "nav_LIBRARY")
            assertNotNull("the personal Library route is available", libraryNav)
            assertTrue("the Library navigation click is accepted", libraryNav!!.performClick())
        }
        await("the populated personal My Lists view must open") {
            shellState(activity).route == ShellRoute.LIBRARY
                && findText(activity, "Recently saved") != null
                && findText(activity, "10 saved  ·  6 lists  ·  3 collections") != null
        }
        val recent = findText(activity, "Recently saved")
            ?: throw AssertionError("the personal Library must show its Recently saved shelf")
        val populatedTitles = application.localLibraryStore.getSavedTitles(profileId).filter {
            it.stableId in FIXTURE_LIBRARY_TITLE_IDS
        }
        assertEquals("the mockup fixture seeds ten personal titles", 10, populatedTitles.size)
        assertEquals("the mockup fixture covers each of the five watch statuses", setOf(
            LocalLibraryStore.STATUS_WATCHING,
            LocalLibraryStore.STATUS_PLAN_TO_WATCH,
            LocalLibraryStore.STATUS_ON_HOLD,
            LocalLibraryStore.STATUS_DROPPED,
            LocalLibraryStore.STATUS_COMPLETED,
        ), populatedTitles.map { it.watchStatus }.toSet())
        assertEquals("the mockup fixture matches the approved four Favorites sample", 4,
            populatedTitles.count { it.favorite })
        assertEquals("the mockup fixture seeds three named collections", setOf(
            "Weekend watch", "Studio Ghibli", "Family picks",
        ), fixtureCollections.map { it.name }.toSet())
        assertEquals("each mockup collection has four local title memberships", setOf(4),
            fixtureCollections.map { collection ->
                application.localLibraryStore.getCollectionTitles(profileId, collection.id).size
            }.toSet())
        val collectionHeading = findText(activity, "Your Collections")
            ?: throw AssertionError("the populated My Lists overview must show Your Collections")
        await("all three mockup collections must render in My Lists") {
            fixtureCollections.all { findText(activity, it.name) != null }
        }
        bringIntoView(collectionHeading)
        captureScreenshot("library-collections-populated.png", listOf(
            "Your Collections", "Weekend watch", "Studio Ghibli", "Family picks",
        ))
        bringIntoView(recent)
        val posterTitles = listOf("The Quiet Between", "Northbound", "Glass Harbor")
        await("each saved synthetic title must render downloaded local artwork") {
            posterTitles.all { title ->
                val poster = findContentDescription(activity, "Open details for $title")
                poster != null && findView(poster) { it is android.widget.ImageView }?.let {
                    (it as android.widget.ImageView).drawable != null
                } == true
            }
        }
        captureScreenshot("library-populated.png", listOf(
            "Recently saved", "Open details for The Quiet Between", "Open details for Northbound",
            "Open details for Glass Harbor",
        ))
        onMain {
            val homeNav = findTagged(activity, "nav_HOME")
            assertNotNull("Home navigation remains available from My Lists", homeNav)
            assertTrue("Home navigation returns to the provider fixture", homeNav!!.performClick())
        }
        await("Home can return from the populated personal Library") {
            shellState(activity).route == ShellRoute.HOME && findText(activity, SHELF_A_TITLE) != null
        }
        assertEquals("returning from Library reuses the active Home provider feed",
            initialHomeRequests, provider.homeRequests.toList())
        assertEquals("returning from Library reuses the secondary Home provider feed",
            initialSecondaryHomeRequests, secondaryProvider.homeRequests.toList())

        onMain {
            val downloadsNav = findTagged(activity, "nav_DOWNLOADS")
            assertNotNull("the Downloads navigation route is available", downloadsNav)
            assertTrue("the Downloads navigation click is accepted", downloadsNav!!.performClick())
        }
        await("the Downloads route must open") {
            shellState(activity).route == ShellRoute.DOWNLOADS
                && findText(activity, "No offline items") != null
        }
        captureScreenshot("downloads-empty.png", listOf("No offline items"))

        onMain {
            val settingsNav = findTagged(activity, "nav_SETTINGS")
            assertNotNull("the Settings navigation route is available", settingsNav)
            assertTrue("the Settings navigation click is accepted", settingsNav!!.performClick())
        }
        await("the Settings hub must open") {
            shellState(activity).route == ShellRoute.SETTINGS && findText(activity, "Extensions") != null
        }
        captureScreenshot("settings-hub.png", listOf("Extensions"))

        onMain {
            val homeNav = findTagged(activity, "nav_HOME")
            assertNotNull("Home navigation remains available from Settings", homeNav)
            assertTrue("Home navigation returns to the provider fixture", homeNav!!.performClick())
        }
        await("Home can return from Settings") {
            shellState(activity).route == ShellRoute.HOME && findText(activity, SHELF_A_TITLE) != null
        }
        assertEquals("returning from Settings reuses the active Home provider feed",
            initialHomeRequests, provider.homeRequests.toList())
        assertEquals("returning from Settings reuses the secondary Home provider feed",
            initialSecondaryHomeRequests, secondaryProvider.homeRequests.toList())

        onMain {
            val searchNav = findTagged(activity, "nav_SEARCH")
            assertNotNull("universal Search navigation is available", searchNav)
            assertTrue("the actual Search navigation click is accepted", searchNav!!.performClick())
        }
        await("universal title Search route must open") {
            shellState(activity).route == ShellRoute.SEARCH
                && findTagged(activity, "search_query") is EditText
        }
        onMain {
            val homeNav = findTagged(activity, "nav_HOME")
            assertNotNull("Home navigation remains available from Search", homeNav)
            assertTrue("Home navigation returns to the active provider feed", homeNav!!.performClick())
        }
        await("Home can return from universal Search") {
            shellState(activity).route == ShellRoute.HOME && findText(activity, SHELF_A_TITLE) != null
        }
        assertEquals("returning from Search reuses the active Home provider feed",
            initialHomeRequests, provider.homeRequests.toList())
        assertEquals("returning from Search reuses the secondary Home provider feed",
            initialSecondaryHomeRequests, secondaryProvider.homeRequests.toList())
        onMain {
            val searchNav = findTagged(activity, "nav_SEARCH")
            assertNotNull("universal Search navigation remains available from Home", searchNav)
            assertTrue("Search navigation opens the existing query route", searchNav!!.performClick())
        }
        await("universal title Search route reopens for its query check") {
            shellState(activity).route == ShellRoute.SEARCH
                && findTagged(activity, "search_query") is EditText
        }
        val searchInput = findTagged(activity, "search_query") as EditText
        onMain {
            searchInput.setText(SEARCH_QUERY)
            searchInput.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            searchInput.clearFocus()
        }
        val expectedPrimarySearchTitles = listOf(
            MOVIE_A_TITLE, MOVIE_B_TITLE, MOVIE_C_TITLE, MOVIE_D_TITLE, MOVIE_E_TITLE, MOVIE_F_TITLE,
        )
        val expectedPrimarySearchDescriptions = expectedPrimarySearchTitles.map {
            "$it, from $FIXTURE_PROVIDER_NAME"
        }
        val expectedSecondarySearchTitles = listOf(SECONDARY_MOVIE_G_TITLE, SECONDARY_MOVIE_H_TITLE)
        val expectedSecondarySearchDescriptions = expectedSecondarySearchTitles.map {
            "$it, from $SECONDARY_PROVIDER_NAME"
        }
        val expectedSearchDescriptions = expectedPrimarySearchDescriptions + expectedSecondarySearchDescriptions
        val expectedFirstSearchPageDescriptions = expectedPrimarySearchDescriptions.take(4) +
            expectedSecondarySearchDescriptions
        val searchResultsRendered = try {
            await("both providers' first universal Search page renders source-attributed local poster art") {
                findText(activity, "Title results") != null
                    && findText(activity, FIXTURE_PROVIDER_NAME) != null
                    && findText(activity, SECONDARY_PROVIDER_NAME) != null
                    && expectedFirstSearchPageDescriptions.all { description ->
                        val result = findContentDescription(activity, description)
                        result != null && findView(result) { it is android.widget.ImageView }?.let {
                            (it as android.widget.ImageView).drawable != null
                        } == true
                    }
                    && findVisibleTitleSearchSuccess(activity)
                    && isVisibleLaidOut(findText(activity, FIXTURE_PROVIDER_NAME))
                    && isVisibleLaidOut(findText(activity, MOVIE_A_TITLE))
                    && isVisibleLaidOut(findText(activity, MOVIE_B_TITLE))
                    && isVisibleLaidOut(findText(activity, SECONDARY_MOVIE_G_TITLE))
                    && isVisibleLaidOut(findText(activity, SECONDARY_MOVIE_H_TITLE))
                    && isVisibleLaidOut(findContentDescription(activity,
                        "$MOVIE_A_TITLE, from $FIXTURE_PROVIDER_NAME"))
                    && isVisibleLaidOut(findContentDescription(activity,
                        "$MOVIE_B_TITLE, from $FIXTURE_PROVIDER_NAME"))
                    && isVisibleLaidOut(findContentDescription(activity,
                        "$SECONDARY_MOVIE_G_TITLE, from $SECONDARY_PROVIDER_NAME"))
                    && isVisibleLaidOut(findContentDescription(activity,
                        "$SECONDARY_MOVIE_H_TITLE, from $SECONDARY_PROVIDER_NAME"))
            }
            true
        } catch (failure: AssertionError) {
            val diagnostic = onMain {
                val outcomes = readField(activity, "titleSearchOutcomes") as? List<*> ?: emptyList<Any>()
                val outcomeSummary = outcomes.joinToString { outcome ->
                    if (outcome == null) return@joinToString "<null outcome>"
                    val status = readField(outcome, "status")
                    val providerOutcome = readField(outcome, "provider")
                    val providerName = if (providerOutcome == null) "?"
                        else readField(providerOutcome, "name")
                    val hits = (readField(outcome, "value") as? List<*>)?.joinToString { hit ->
                        if (hit == null) "<null hit>" else {
                            val item = readField(hit, "item")
                            if (item == null) "<null item>" else
                                "${readField(item, "title")}:${readField(item, "posterUrl")}"
                        }
                    } ?: "<no value>"
                    "$providerName/$status=[$hits]"
                }
                val posterSummary = expectedSearchDescriptions.joinToString { description ->
                    val result = findContentDescription(activity, description)
                    val imageView = result?.let { findView(it) { child ->
                        child is android.widget.ImageView
                    } as? android.widget.ImageView }
                    "$description result=${result?.isShown}/${result?.isLaidOut}" +
                        " image=${imageView?.drawable?.javaClass?.simpleName}"
                }
                "route=${shellState(activity).route} submitted=${readField(activity, "titleSearchSubmittedQuery")} " +
                    "query=${readField(activity, "searchQuery")} " +
                    "requests=${provider.searchRequests}/${secondaryProvider.searchRequests} " +
                    "outcomes=[$outcomeSummary] posters=[$posterSummary]"
            }
            throw AssertionError("universal Search render diagnostic: $diagnostic", failure)
        }
        assertTrue("universal Search results render", searchResultsRendered)
        assertEquals("universal Search preserves the first provider's six results in response order",
            listOf(movieAUrl, MOVIE_B_URL) + additionalSearchMovies.map { it.url },
            provider.searchResultUrls)
        assertEquals("Search sends the entered query to the first local MainAPI callback",
            listOf("$SEARCH_QUERY:1"), provider.searchRequests)
        assertEquals("universal Search preserves the second provider's two results in response order",
            listOf(SECONDARY_MOVIE_G_URL, SECONDARY_MOVIE_H_URL), secondaryProvider.searchResultUrls)
        assertEquals("Search sends the entered query to the second local MainAPI callback",
            listOf("$SEARCH_QUERY:1"), secondaryProvider.searchRequests)

        val firstPageResultA = findContentDescription(activity, expectedPrimarySearchDescriptions[0])!!
        val primarySearchRail = findAncestorRecyclerView(firstPageResultA)
            ?: throw AssertionError("the first provider's Search titles must belong to a RecyclerView rail")
        onMain { primarySearchRail.scrollToPosition(4) }
        await("scrolling the primary Search rail materializes the remaining provider posters") {
            expectedPrimarySearchDescriptions.drop(4).all { description ->
                val result = findContentDescription(activity, description)
                result != null && findView(result) { it is android.widget.ImageView }?.let {
                    (it as android.widget.ImageView).drawable != null
                } == true
            }
        }
        val resultE = findContentDescription(activity, expectedPrimarySearchDescriptions[4])!!
        captureScreenshot("search-tail.png", listOf(
            FIXTURE_PROVIDER_NAME,
            MOVIE_E_TITLE,
            MOVIE_F_TITLE,
        ))
        onMain { primarySearchRail.scrollToPosition(0) }
        await("scrolling the primary Search rail back restores its opening posters") {
            expectedFirstSearchPageDescriptions.all { description ->
                    val result = findContentDescription(activity, description)
                    result != null && findView(result) { it is android.widget.ImageView }?.let {
                        (it as android.widget.ImageView).drawable != null
                    } == true
                }
        }
        val resultA = findContentDescription(activity, expectedPrimarySearchDescriptions[0])!!
        val resultB = findContentDescription(activity, expectedPrimarySearchDescriptions[1])!!
        val resultG = findContentDescription(activity, expectedSecondarySearchDescriptions[0])!!
        val resultH = findContentDescription(activity, expectedSecondarySearchDescriptions[1])!!
        val primarySearchHeading = findText(activity, FIXTURE_PROVIDER_NAME)!!
        val secondarySearchHeading = findText(activity, SECONDARY_PROVIDER_NAME)!!
        assertTrue("Search tiles carry visible provider attribution", resultA.isClickable)
        val resultRow = findAncestorRecyclerView(resultA)
            ?: throw AssertionError("Search result A must belong to its rendered provider rail")
        val resultBRow = findAncestorRecyclerView(resultB)
            ?: throw AssertionError("Search result B must belong to its rendered provider rail")
        val resultAItem = resultA.parent as? View
            ?: throw AssertionError("Search result A must belong to a RecyclerView item")
        val resultBItem = resultB.parent as? View
            ?: throw AssertionError("Search result B must belong to a RecyclerView item")
        val resultAPosition = resultRow.getChildAdapterPosition(resultAItem)
        val resultBPosition = resultRow.getChildAdapterPosition(resultBItem)
        Log.i(TAG, "SEARCH_RESULT_LAYOUT " +
            "A=(x=${locationX(resultA)},y=${locationY(resultA)},shown=${resultA.isShown},attached=${resultA.isAttachedToWindow},tag=${resultA.tag},adapterPosition=$resultAPosition) " +
            "B=(x=${locationX(resultB)},y=${locationY(resultB)},shown=${resultB.isShown},attached=${resultB.isAttachedToWindow},tag=${resultB.tag},adapterPosition=$resultBPosition) " +
            "sameRail=${resultRow === resultBRow}")
        captureScreenshot("search.png", listOf(
            FIXTURE_PROVIDER_NAME,
            MOVIE_A_TITLE,
            MOVIE_B_TITLE,
        ))
        assertTrue("Search result order is stable in the rendered provider rail",
            resultRow === resultBRow && resultAPosition == 0 && resultBPosition == 1)
        val secondaryResultRow = findAncestorRecyclerView(resultG)
            ?: throw AssertionError("the second provider's first Search result must have a rail")
        val secondaryResultHRow = findAncestorRecyclerView(resultH)
            ?: throw AssertionError("the second provider's second Search result must have a rail")
        val secondaryResultGItem = resultG.parent as? View
            ?: throw AssertionError("the second provider's first Search result must have a RecyclerView item")
        val secondaryResultHItem = resultH.parent as? View
            ?: throw AssertionError("the second provider's second Search result must have a RecyclerView item")
        assertTrue("Search renders provider groups in selected source order",
            locationY(primarySearchHeading) < locationY(secondarySearchHeading)
                && secondaryResultRow === secondaryResultHRow
                && secondaryResultRow.getChildAdapterPosition(secondaryResultGItem) == 0
                && secondaryResultRow.getChildAdapterPosition(secondaryResultHItem) == 1)
        bringIntoView(secondarySearchHeading)
        captureScreenshot("search-second-provider.png", listOf(
            SECONDARY_PROVIDER_NAME, SECONDARY_MOVIE_G_TITLE,
        ))

        onMain {
            assertTrue("the first universal Search result opens its details", resultA.performClick())
        }
        await("Movie Details must render the seed item while the provider callback is pending") {
            findTagged(activity, "provider_details_title")?.let {
                (it as? TextView)?.text?.toString() == MOVIE_A_TITLE
            } == true && readField(activity, "providerDetailsLoading") == true
        }
        assertEquals("the seed item remains visible during the provider Details callback",
            MOVIE_A_TITLE,
            (findTagged(activity, "provider_details_title") as TextView).text.toString())
        await("Movie Details must be loaded from the provider callback") {
            findTagged(activity, "provider_details_title")?.let {
                (it as? TextView)?.text?.toString() == MOVIE_A_TITLE
            } == true && readField(activity, "providerDetailsLoading") == false
                && findText(activity, "Local fixture details returned by MainAPI.load.") != null
        }
        assertEquals("Details receives the exact URL returned by Search",
            listOf(movieAUrl), provider.detailRequests)
        val sourceLine = findText(activity, "$FIXTURE_PROVIDER_NAME  ·  MOVIE")
        assertNotNull("Movie Details identifies the originating provider", sourceLine)
        val detailsDescription = findText(activity, "Local fixture details returned by MainAPI.load.")
        assertNotNull("the provider Details callback renders its fixture description", detailsDescription)
        val detailsItemForContext = readField(activity, "providerDetailsItem") as MediaItem
        val detailsProviderForContext = readField(activity, "providerDetailsProvider") as MediaProvider
        val currentProviderForContext = application.getMediaProvidersForProfile(profileId)
            .firstOrNull { it.descriptor().id == detailsProviderForContext.descriptor().id }
            ?: throw AssertionError("the current provider registry must retain the fixture registration")
        assertEquals("the loaded fixture handle owns the details provider",
            pluginId, detailsProviderForContext.owningPluginId())
        assertEquals("the current provider retains the same registration identity",
            detailsProviderForContext.descriptor().id, currentProviderForContext.descriptor().id)
        assertEquals("the current provider retains the same loaded owner",
            pluginId, currentProviderForContext.owningPluginId())
        assertEquals("the current provider retains the same profile scope",
            profileId, currentProviderForContext.profileScopeId())
        assertTrue("the retained provider adapter still owns its committed registration",
            detailsProviderForContext.isRegistrationCurrent())
        assertTrue("the fresh registry adapter proves the same committed registration is current",
            currentProviderForContext.isRegistrationCurrent())
        assertTrue("the fixture's committed details payload remains current under its runtime handle",
            detailsProviderForContext.isLoadedDetailsContextCurrent(detailsItemForContext))
        val liveActionProbe = MediaItem(detailsItemForContext.stableId,
            detailsItemForContext.title, MediaKind.LIVE, detailsItemForContext.detailUrl,
            detailsItemForContext.posterUrl, detailsItemForContext.year,
            detailsItemForContext.overview, detailsItemForContext.providerData)
        assertFalse("LIVE payloads do not claim retained loaded-details context",
            detailsProviderForContext.isLoadedDetailsContextCurrent(liveActionProbe))
        assertTrue("the LIVE reuse proof comes from the exact current registration",
            detailsProviderForContext.isRegistrationCurrent())
        assertEquals("the flow remains before provider link resolution until Play", 0, provider.linkCalls)
        assertTrue("the provider registration remains visible while Details is open",
            application.getMediaProvidersForProfile(profileId).any {
                it.descriptor().id == providerId
            })
        bringIntoView(findTagged(activity, "provider_details_title")!!)
        captureScreenshot("details.png", listOf(
            MOVIE_A_TITLE,
            "$FIXTURE_PROVIDER_NAME  ·  MOVIE",
            "Local fixture details returned by MainAPI.load.",
        ))
        assertTrue("Orbit remains in the actual provider Movie Details state",
            readField(activity, "providerTitleDetailsOpen") as Boolean)

        val detailsItem = readField(activity, "providerDetailsItem") as? MediaItem
            ?: throw AssertionError("the selected provider Details item must be retained before playback")
        playbackMonitor = instrumentation.addMonitor(OrbitPlayerActivity::class.java.name, null, false)
        if (realFcastAcceptanceEnabled) {
            val play = findTagged(activity, "provider_details_play")
                ?: throw AssertionError("provider Details must expose the actual Play action")
            bringIntoView(play)
            onMain { assertTrue("Details Play starts external-player link resolution", play.performClick()) }
            await("MainAPI.loadLinks prepares the provider action for the real Fcast handoff") {
                provider.linkCalls == 1
                    && readField(activity, "providerDetailsLinksLoaded") == true
                    && provider.emittedLinkUrls.size == 3
                    && findTagged(activity, "provider_details_source_0") != null
                    && findTagged(activity, "provider_details_source_1") != null
            }
            assertEquals("the external-player handoff keeps the exact provider item payload",
                listOf("fixture-movie-data:$movieAUrl"), provider.linkRequestData)
            assertEquals("external-player link resolution remains non-casting",
                listOf(false), provider.castingRequests)
            assertEquals("MainAPI.loadLinks preserves its callback order for the external action",
                listOf(mediaServer!!.url("first-source.mp4"), selectedSourceUrl,
                    queriedSourceUrl), provider.emittedLinkUrls)
            runRealFcastProviderHandoff(activity, profileId, providerId, selectedSourceUrl)
            return
        }

        expectedPlaybackSourceUri = selectedSourceUrl
        expectedPlaybackTitle = detailsItem.title
        expectedPlaybackProviderId = providerId
        assertNoLibraryRowsForPlayback(profileId, detailsItem.stableId)
        // Arm cleanup before the player can begin persisting progress, including an early failure.
        playbackLibraryMutationAllowed = true
        val play = findTagged(activity, "provider_details_play")
            ?: throw AssertionError("provider Details must expose the actual Play action")
        bringIntoView(play)
        playbackLaunchAttempted = true
        onMain { assertTrue("Details Play launches Orbit Player before link resolution", play.performClick()) }

        val launched = playbackMonitor!!.waitForActivityWithTimeout(UI_TIMEOUT_MS)
        assertNotNull("Details Play must immediately start OrbitPlayerActivity", launched)
        assertTrue("the launched Activity is Orbit's actual player", launched is OrbitPlayerActivity)
        val playerActivity = launched as OrbitPlayerActivity
        playbackActivity = playerActivity
        try {
            val deferredRequest = readField(playerActivity, "deferredProviderPlayback")
                ?: throw AssertionError("Orbit Player must begin a deferred provider lookup")
            assertEquals("deferred playback retains the active fixture profile", profileId,
                readField(deferredRequest, "profileId"))
            assertEquals("deferred playback retains the movie's exact provider payload",
                "fixture-movie-data:$movieAUrl", readField(deferredRequest, "providerItemId"))
            assertTrue("the provider link lookup starts after Orbit Player opens",
                provider.linkResolutionStarted.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("the player shows its link-loading state before the first callback",
                onMain {
                    readField(playerActivity, "request") == null
                        && findView(playerActivity.window.decorView) {
                            it is TextView && it.text?.toString() == "Finding links…"
                        } != null
                })
            provider.allowFirstLinkCallback.countDown()
            assertTrue("the player receives its first source while the provider is still resolving",
                provider.firstLinkCallbackDelivered.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            await(
                "the first provider candidate starts playback while link resolution continues",
                PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS,
                failureDetails = {
                    val request = readField(playerActivity, "request") as? PlaybackRequest
                    val current = readField(playerActivity, "currentSource") as? PlaybackRequest.Source
                    val playback = readField(playerActivity, "player") as? Player
                    "requestSources=${request?.sources?.size} " +
                        "defaultSourceId=${request?.defaultSourceId} currentUri=${current?.uri} " +
                        "isPlaying=${playback?.isPlaying} linkCalls=${provider.linkCalls}"
                },
            ) {
                val request = readField(playerActivity, "request") as? PlaybackRequest
                val current = readField(playerActivity, "currentSource") as? PlaybackRequest.Source
                val playback = readField(playerActivity, "player") as? Player
                request != null && request.sources.size == 1
                    && request.defaultSourceId == "provider-source-0"
                    && current?.uri == selectedSourceUrl
                    && playback?.isPlaying == true
            }
        } finally {
            provider.allowFirstLinkCallback.countDown()
            provider.allowLinkResolutionReturn.countDown()
        }
        await("the player merges remaining safe links and completes the provider lookup") {
            val request = readField(playerActivity, "request") as? PlaybackRequest
            provider.linkCalls == 1 && provider.completedLinkCallbacks == listOf(1)
                && provider.emittedLinkUrls.size == 3
                && readField(playerActivity, "deferredProviderPlayback") == null
                && request?.sources?.size == 2
        }
        assertEquals("in-player MainAPI.loadLinks receives the exact details payload",
            listOf("fixture-movie-data:$movieAUrl"), provider.linkRequestData)
        assertEquals("in-player provider link resolution remains non-casting",
            listOf(false), provider.castingRequests)
        assertEquals("the player preserves the provider's callback order",
            listOf(selectedSourceUrl, mediaServer!!.url("first-source.mp4"), queriedSourceUrl),
            provider.emittedLinkUrls)
        assertEquals("MainAPI.loadLinks reports both provider subtitle callbacks",
            listOf("en", "fr"), provider.emittedSubtitleLanguages)
        assertEquals("the player receives the exact URLs for both provider subtitle callbacks",
            listOf(subtitleUrl, "https://captions.fixture.invalid/captions.fr.vtt"),
            provider.emittedSubtitleUrls)
        assertTrue("the provider subtitle fixture carries no credential headers",
            provider.emittedSubtitleFiles.first().headers.orEmpty().isEmpty())
        assertEquals("the cross-origin provider subtitle keeps its own callback header",
            mapOf("X-Subtitle-Fixture" to "subtitle-token"),
            provider.emittedSubtitleFiles.last().headers.orEmpty())
        assertTrue("all provider link callbacks remain on loopback",
            provider.emittedLinkUrls.all { it.startsWith("http://127.0.0.1:") })
        assertTrue("the fetched subtitle callback remains on loopback",
            provider.emittedSubtitleUrls.first().startsWith("http://127.0.0.1:"))
        assertTrue("the third candidate is the expected query-bearing local fixture",
            queriedSourceUrl.endsWith("/query-source.mp4?fixture=local-only"))
        val request = readField(playerActivity, "request") as? PlaybackRequest
            ?: throw AssertionError("the provider link callbacks must populate PlaybackRequest")
        assertEquals("the request remains scoped to the active fixture profile", profileId, request.profileId)
        assertEquals("the request retains the Details item's stable ID",
            detailsItem.stableId, request.media.stableId)
        assertEquals("the first provider callback remains the initial PlaybackRequest source",
            "provider-source-0", request.defaultSourceId)
        assertEquals("both provider candidates cross the player request contract",
            listOf("provider-source-0", "provider-source-1"), request.sources.map { it.id })
        assertEquals("PlaybackRequest retains both safe source URLs in callback order",
            listOf(selectedSourceUrl, mediaServer!!.url("first-source.mp4")),
            request.sources.map { it.uri })
        assertEquals("the PlaybackRequest keeps the first loopback callback as its default",
            selectedSourceUrl, request.getDefaultSource().uri)
        assertTrue("the player source labels preserve both provider link names and the provider name",
            request.sources.any { it.label.contains("Fixture MP4 1080p")
                && it.label.contains(FIXTURE_PROVIDER_NAME) }
                && request.sources.any { it.label.contains("Fixture MP4 360p")
                    && it.label.contains(FIXTURE_PROVIDER_NAME) })
        assertTrue("provider callback sources carry no request or credential headers",
            request.sources.all { it.headers.isEmpty() })
        val playbackSubtitles = request.getDefaultSource().subtitles
        assertEquals("Orbit Shell forwards both provider subtitles into PlaybackRequest",
            2, playbackSubtitles.size)
        val protectedSubtitle = playbackSubtitles.first { it.language == "fr" }
        assertEquals("the cross-origin subtitle retains its request header",
            mapOf("X-Subtitle-Fixture" to "subtitle-token"),
            readField(protectedSubtitle, "headers"))
        val playbackSubtitle = playbackSubtitles.first { it.language == "en" }
        assertEquals("the forwarded subtitle has its stable callback-derived ID",
            "provider-subtitle-1", playbackSubtitle.id)
        assertEquals("the forwarded subtitle label preserves its literal language", "en",
            playbackSubtitle.label)
        assertEquals("the forwarded subtitle language is preserved literally", "en",
            playbackSubtitle.language)
        assertEquals("the forwarded subtitle URI is the exact loopback callback URI",
            subtitleUrl, playbackSubtitle.uri)
        assertEquals("the VTT suffix maps to the WebVTT playback MIME type", "text/vtt",
            playbackSubtitle.mimeType)
        assertEquals("the provider subtitle remains unforced for selection", 0,
            playbackSubtitle.selectionFlags)
        val captionsButton = readField(playerActivity, "captionsButton") as? Button
            ?: throw AssertionError("Orbit Player exposes a caption toggle")
        onMain { assertTrue("the initial caption toggle accepts a click", captionsButton.performClick()) }
        val sourcesDialogAfterToggle = readField(playerActivity, "sourcesDialog") as? android.app.Dialog
        assertTrue("CC off with no selected caption does not open the Sources selector",
            sourcesDialogAfterToggle == null || !sourcesDialogAfterToggle.isShowing)
        assertEquals("CC off leaves caption choice to Sources", "",
            readField(playerActivity, "selectedSubtitleId"))
        assertEquals("the unconfigured caption toggle remains off", "Captions off",
            captionsButton.contentDescription)
        awaitProviderSubtitleTrack(playerActivity, playbackSubtitle.id)
        chooseProviderSubtitleThroughPlayerUi(playerActivity, playbackSubtitle.id,
            "Fixture MP4 1080p")
        assertEquals("the actual player subtitle UI selects the reported subtitle",
            "external:${playbackSubtitle.id}", readField(playerActivity, "selectedSubtitleId"))
        assertEquals("choosing a subtitle in Sources turns captions on", "Captions on",
            captionsButton.contentDescription)
        onMain { assertTrue("the selected caption can be switched off from the player", captionsButton.performClick()) }
        assertEquals("the caption toggle turns subtitles off without clearing the choice", false,
            readField(playerActivity, "subtitlesEnabled"))
        assertEquals("turning captions off keeps the selected subtitle for the next toggle",
            "external:${playbackSubtitle.id}", readField(playerActivity, "selectedSubtitleId"))
        assertEquals("the direct off toggle updates its accessibility label", "Captions off",
            captionsButton.contentDescription)
        val sourcesDialogAfterOff = readField(playerActivity, "sourcesDialog") as? android.app.Dialog
        assertTrue("turning captions off does not open Sources",
            sourcesDialogAfterOff == null || !sourcesDialogAfterOff.isShowing)
        onMain { assertTrue("the selected caption can be switched back on from the player", captionsButton.performClick()) }
        assertEquals("the direct on toggle re-enables the retained subtitle choice", true,
            readField(playerActivity, "subtitlesEnabled"))
        assertEquals("turning captions on preserves the selected subtitle",
            "external:${playbackSubtitle.id}", readField(playerActivity, "selectedSubtitleId"))
        assertEquals("the direct on toggle updates its accessibility label", "Captions on",
            captionsButton.contentDescription)
        val sourcesDialogAfterOn = readField(playerActivity, "sourcesDialog") as? android.app.Dialog
        assertTrue("turning captions on does not open Sources",
            sourcesDialogAfterOn == null || !sourcesDialogAfterOn.isShowing)
        val served = mediaServer!!.awaitRequest("/selected-source.mp4", UI_TIMEOUT_MS)
        assertNotNull("Orbit Player must request the first local provider callback immediately", served)
        await("Media3 must render an active play state from the local fixture MP4") {
            val playback = readField(playerActivity, "player") as? Player
            playback?.isPlaying == true && playback.currentPosition > 0L
                && (readField(playerActivity, "playPauseButton") as? TextView)
                    ?.contentDescription == "Pause playback"
        }
        val subtitleServed = mediaServer!!.awaitRequest("/captions.en.vtt", UI_TIMEOUT_MS)
        assertNotNull("selecting the reported subtitle through Orbit Player must fetch its VTT", subtitleServed)
        val mediaRequests = mediaServer!!.requests()
        assertNull("the bounded loopback fixture server remains healthy", mediaServer!!.failure())
        val queryCandidateRequests = mediaRequests.count { it.requestTarget.contains("query-source.mp4") }
        assertEquals("the HTTP query candidate must never reach the local media server", 0,
            queryCandidateRequests)
        assertTrue("only advertised loopback fixture media and subtitle paths can be served",
            mediaRequests.isNotEmpty() && mediaRequests.all {
                it.path == "/first-source.mp4" || it.path == "/selected-source.mp4"
                    || it.path == "/captions.en.vtt"
                    || it.path == "/provider-artwork/home-coast.png"
                    || it.path == "/provider-artwork/mira.png"
                    || it.path == "/provider-artwork/hannah.png"
                    || it.path == "/provider-artwork/orbit-original.jpg"
            })
        assertTrue("the selected source is served as successful bounded MP4 data",
            mediaRequests.filter { it.path == "/selected-source.mp4" }
                .all { it.status == 200 || it.status == 206 })
        val selectedRequests = mediaRequests.filter { it.path == "/selected-source.mp4" }
        val credentialHeaderNames = setOf("authorization", "cookie", "proxy-authorization", "referer", "x-api-key")
        assertTrue("the player request carries Media3's generated ICY metadata transport header",
            selectedRequests.isNotEmpty() && selectedRequests.any { "icy-metadata" in it.headerNames })
        assertTrue("the local media request carries no provider or credential headers",
            selectedRequests.all { requestHeaders ->
                requestHeaders.headerNames.none { it in credentialHeaderNames }
            })
        val subtitleRequests = mediaRequests.filter { it.path == "/captions.en.vtt" }
        assertTrue("the selected subtitle is actually requested from the loopback fixture",
            subtitleRequests.isNotEmpty())
        assertEquals("the requested subtitle URL is exactly the configured loopback VTT URL",
            listOf(subtitleUrl), subtitleRequests.map { "http://${it.host}${it.requestTarget}" }.distinct())
        assertTrue("the player receives the complete local WebVTT without credentials",
            subtitleRequests.all { subtitleRequest ->
                subtitleRequest.method == "GET"
                    && subtitleRequest.requestTarget == subtitleRequest.path
                    && subtitleRequest.status == 200
                    && subtitleRequest.bytes == WEBVTT_FIXTURE.toByteArray(StandardCharsets.UTF_8).size
                    && subtitleRequest.headerNames.none { it in credentialHeaderNames }
            })
        assertFalse("the metadata-only fixture still has no CS3 package file",
            fixturePackagePath?.exists() == true)

        val playbackReady = onMain { (readField(playerActivity, "player") as Player).isPlaying }
        Log.i(TAG, "FLOW_PASS profile=$profileId provider=$providerId " +
            "homeRequests=${provider.homeRequests} searchRequests=${provider.searchRequests} " +
            "details=${provider.detailRequests} linkCalls=${provider.linkCalls} " +
            "linkRequestCount=${provider.linkRequestData.size} selectedSource=${request.defaultSourceId} " +
            "servedRequests=${mediaRequests.size} queryCandidateRequests=$queryCandidateRequests " +
            "playbackReady=$playbackReady " +
            "screenshots=${fixtureOutputDirectory!!.absolutePath}")
        } catch (failure: Throwable) {
            if (fixtureOutputDirectory?.isDirectory == true) {
                try {
                    captureScreenshot("failure.png")
                } catch (captureFailure: Throwable) {
                    Log.e(TAG, "FAILURE_SCREENSHOT_ERROR", captureFailure)
                }
            }
            throw failure
        }
    }

    @Test
    fun tappingProviderEpisodeLaunchesPlayerLoadsLinksAndReturnsFocusToCard() {
        val profileId = activeProfileId ?: error("fixture profile was not prepared")
        val pluginId = FIXTURE_PLUGIN_ID
        assertNull("the fixture plugin ID must not already be installed",
            extensionManager.getInstalledPlugin(pluginId))
        assertFalse("the fixture plugin must not already own a registration",
            hostServices.hasOutstandingRegistrations(pluginId))
        assertFalse("the series fixture plugin must not already own a runtime handle",
            runtime.isLoaded(pluginId))
        assertFalse("APIHolder must not already contain a fixture series API",
            APIHolder.INSTANCE.allProviders.any { it is FixtureSeriesApi })

        val playbackPreferences = shell!!.getSharedPreferences(
            "orbit_profile_playback_preferences_v1", Context.MODE_PRIVATE)
        val playbackPreferenceKey = "default_video_player:$profileId"
        val priorPlayerId = playbackPreferences.getString(playbackPreferenceKey, null)
        assertTrue("Orbit Player must be configured for the fixture profile",
            playbackPreferences.edit().putString(playbackPreferenceKey, "orbit.internal").commit())
        try {
            val markerRoot = File(target.cacheDir, FIXTURE_DIRECTORY_NAME)
            assertTrue("fixture marker directory must be app-private",
                markerRoot.mkdirs() || markerRoot.isDirectory)
            fixtureMarker = File(markerRoot, "registration.fixture-marker")
            assertTrue("the non-package registration marker must be created", fixtureMarker!!.createNewFile())
            mediaServer = LoopbackMediaFixtureServer(instrumentation.context)
            val firstSourceUrl = mediaServer!!.url("first-source.mp4")
            val selectedSourceUrl = mediaServer!!.url("selected-source.mp4")
            val queriedSourceUrl = mediaServer!!.url("query-source.mp4?fixture=local-only")
            val hlsFailureSourceUrl = mediaServer!!.url("failing-source.m3u8")
            val secondHlsFailureSourceUrl = mediaServer!!.url("failing-source-2.m3u8")
            val lateSubtitleUrl = mediaServer!!.url("captions.en.vtt")
            val provider = FixtureSeriesApi(firstSourceUrl, selectedSourceUrl, queriedSourceUrl,
                pauseAfterFirstLinkCallback = true, lateSubtitleUrl = lateSubtitleUrl,
                hlsFailureSourceUrl = hlsFailureSourceUrl,
                secondHlsFailureSourceUrl = secondHlsFailureSourceUrl)
            episodeProgressiveFixture = provider
            fakeApis = listOf(provider)

            extensionFixtureWritten = true
            addFixtureExtensionGrant(profileId, pluginId)
            fixturePackagePath = File(extensionManager.getInstalledPlugin(pluginId)!!.filePath)
            assertFalse("the metadata-only series fixture must not create a .cs3 package",
                fixturePackagePath!!.exists())
            val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
            selectionFixtureWritten = true
            selectionStore.setOrderedProviderIds(profileId, listOf(providerId))
            selectionStore.setSelectedProviderIds(profileId, listOf(providerId), listOf(providerId))

            val fakePlugin = object : BasePlugin() { }
            transaction = hostServices.beginPluginLoad(pluginId, fixtureMarker!!.absolutePath, fakePlugin)
            fakePlugin.registerMainAPI(provider)
            transaction!!.commit()
            seedLoadedPluginHandle(fakePlugin, fixtureMarker!!, transaction!!, pluginId)
            assertTrue("the series fixture must retain its committed plugin runtime handle",
                runtime.isLoaded(pluginId))
            assertEquals("the series provider publishes supported CS3 metadata",
                Cs3ProviderRegistry.MetadataReadiness.READY,
                registry.awaitProviderMetadata(profileId, pluginId, 0, 5_000L))
            val registeredProvider = application.getMediaProvidersForProfile(profileId).single()
            assertEquals("the registered provider retains its series capability", providerId,
                registeredProvider.descriptor().id)
            assertTrue("the provider supports series Details", registeredProvider.descriptor().details)
            assertTrue("the provider supports episode links", registeredProvider.descriptor().links)
            assertTrue("the provider supports the separate episode download action",
                registeredProvider.descriptor().downloads)

            val activity = shell ?: error("OrbitShellActivity was not retained")
            onMain {
                val searchNav = findTagged(activity, "nav_SEARCH")
                    ?: throw AssertionError("universal Search navigation must be available")
                assertTrue("the actual Search navigation click is accepted", searchNav.performClick())
            }
            await("the universal Search route must open") {
                shellState(activity).route == ShellRoute.SEARCH
                    && findTagged(activity, "search_query") is EditText
            }
            val searchInput = findTagged(activity, "search_query") as EditText
            onMain {
                searchInput.setText(FIXTURE_SERIES_TITLE)
                searchInput.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
                searchInput.clearFocus()
            }
            val seriesDescription = "$FIXTURE_SERIES_TITLE, from $FIXTURE_PROVIDER_NAME"
            await("the fake TV series must appear in provider Search") {
                val result = findContentDescription(activity, seriesDescription)
                result != null && result.isClickable
            }
            val seriesResult = findContentDescription(activity, seriesDescription)!!
            bringIntoView(seriesResult)
            onMain { assertTrue("the TV series result opens provider Details", seriesResult.performClick()) }
            await("the real provider Details screen must expose its first episode") {
                findTagged(activity, "provider_details_title")?.let {
                    (it as? TextView)?.text?.toString() == FIXTURE_SERIES_TITLE
                } == true && findTagged(activity, "provider_episode_select_0") != null
                    && findTagged(activity, "provider_episode_download_0") != null
            }
            val detailsItem = readField(activity, "providerDetailsItem") as? MediaItem
                ?: throw AssertionError("the series Details item must be retained")
            val selectedEpisode = readField(activity, "providerDetailsSelectedEpisode")
                as? MediaItem.EpisodeItem
                ?: throw AssertionError("the first episode must be selected in Details")
            val detailsProviderForContext = readField(activity, "providerDetailsProvider") as MediaProvider
            val currentProviderForContext = application.getMediaProvidersForProfile(profileId)
                .firstOrNull { it.descriptor().id == detailsProviderForContext.descriptor().id }
                ?: throw AssertionError("the current provider registry must retain the series registration")
            assertEquals("the loaded series fixture handle owns the Details provider",
                pluginId, detailsProviderForContext.owningPluginId())
            assertEquals("the current series provider retains the same registration identity",
                detailsProviderForContext.descriptor().id, currentProviderForContext.descriptor().id)
            assertEquals("the current series provider retains the same loaded owner",
                pluginId, currentProviderForContext.owningPluginId())
            assertEquals("the current series provider retains the same profile scope",
                profileId, currentProviderForContext.profileScopeId())
            assertTrue("the retained series adapter still owns its committed registration",
                detailsProviderForContext.isRegistrationCurrent())
            assertTrue("the fresh series registry adapter proves its registration is current",
                currentProviderForContext.isRegistrationCurrent())
            assertTrue("the fixture series episode payload remains current under its runtime handle",
                detailsProviderForContext.isLoadedDetailsContextCurrent(detailsItem))
            assertEquals("Details retains the selected episode's provider payload",
                FIXTURE_EPISODE_DATA, selectedEpisode.providerData)

            playbackMonitor = instrumentation.addMonitor(
                OrbitPlayerActivity::class.java.name, null, false)
            expectedPlaybackSourceUri = hlsFailureSourceUrl
            expectedPlaybackTitle = detailsItem.title
            expectedPlaybackProviderId = providerId
            assertNoLibraryRowsForPlayback(profileId, detailsItem.stableId)
            playbackExpectingAnotherLaunch = true
            playbackLibraryMutationAllowed = true
            playbackLaunchAttempted = true
            var episodeCard = findTagged(activity, "provider_episode_select_0")
                ?: throw AssertionError("the tapped episode card must remain available in Details")
            bringIntoView(episodeCard)
            episodeCard = findTagged(activity, "provider_episode_select_0")
                ?: throw AssertionError("the episode card must remain in the current Details tree")
            assertTrue("the current episode card is attached and laid out before tapping",
                isVisibleLaidOut(episodeCard))
            onMain {
                assertTrue("tapping the episode card launches the actual Orbit player",
                    episodeCard.performClick())
            }

            val launched = playbackMonitor!!.waitForActivityWithTimeout(UI_TIMEOUT_MS)
            assertNotNull("the episode card must immediately start OrbitPlayerActivity", launched)
            assertTrue("episode-card playback must use Orbit's actual player",
                launched is OrbitPlayerActivity)
            val playerActivity = launched as OrbitPlayerActivity
            playbackActivity = playerActivity
            val deferredRequest = readField(playerActivity, "deferredProviderPlayback")
                ?: throw AssertionError("the player must capture the deferred episode identity immediately")
            assertTrue("Orbit Player shows an in-player provider lookup state",
                onMain {
                    readField(playerActivity, "request") == null
                        && findView(playerActivity.window.decorView) {
                            it is TextView && it.text?.toString() == "Finding links…"
                        } != null
                })
            assertTrue("the provider must publish its first candidate while loadLinks is still active",
                provider.firstLinkCallbackDelivered.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            try {
                val firstManifest = mediaServer!!.awaitRequest(
                    "/failing-source.m3u8", PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS)
                assertNotNull("ExoPlayer must request the first candidate while loadLinks is active; " +
                    "requests=${mediaServer!!.requests()} serverFailure=${mediaServer!!.failure()}",
                    firstManifest)
                assertEquals("ExoPlayer must receive the deterministic HLS manifest",
                    200, firstManifest!!.status)
                val firstFailedSegment = mediaServer!!.awaitRequest(
                    "/segment-403.ts", PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS)
                assertNotNull("the first HLS segment request must reach the local fixture",
                    firstFailedSegment)
                assertEquals("the deterministic HLS media chunk must fail with HTTP 403",
                    403, firstFailedSegment!!.status)
                await("the real ExoPlayer HTTP failure must enter progressive source failover",
                    PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS) {
                    onMain {
                        val request = readField(playerActivity, "request") as? PlaybackRequest
                        val playback = readField(playerActivity, "player") as? Player
                        val current = readField(playerActivity, "currentSource") as? PlaybackRequest.Source
                        request != null && request.sources.size == 1
                            && request.sources[0].uri == hlsFailureSourceUrl
                            && current?.id == "provider-source-0"
                            && playback?.playerError != null
                            && readField(playerActivity, "sourceFailoverPending") == true
                            && ((readField(playerActivity, "statusView") as? TextView)
                                ?.text?.toString()?.contains("Still looking for links") == true)
                            && !playerActivity.isFinishing
                    }
                }
                assertTrue("the provider must remain unresolved after its first candidate fails",
                    provider.completedLinkCallbacks.isEmpty())
                val failedSource = onMain {
                    readField(playerActivity, "currentSource") as PlaybackRequest.Source
                }
                val failedSourceHooks = failedSource.cs3PlaybackHooks
                val firstSourceRequestsBeforeLateSubtitle = mediaServer!!.requests()
                    .count { it.path == "/first-source.mp4" }
                provider.allowLinkResolutionReturn.countDown()
                assertTrue("the provider must publish the late subtitle before its next link",
                    provider.lateSubtitleCallbackDelivered.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                await("a subtitle-only callback must preserve the failed source and pending failover") {
                    onMain {
                        val request = readField(playerActivity, "request") as? PlaybackRequest
                        val source = request?.sources?.firstOrNull { it.id == "provider-source-0" }
                        val current = readField(playerActivity, "currentSource")
                            as? PlaybackRequest.Source
                        val playback = readField(playerActivity, "player") as? Player
                        val firstSourceRequests = mediaServer!!.requests()
                            .count { it.path == "/first-source.mp4" }
                        source === failedSource && source.subtitles.size == 1
                            && source.cs3PlaybackHooks === failedSourceHooks
                            && readField(playerActivity, "sourceFailoverPending") == true
                            && current === failedSource
                            && playback?.currentMediaItem?.mediaId == "provider-source-0"
                            && firstSourceRequests == firstSourceRequestsBeforeLateSubtitle
                    }
                }
                assertTrue("the provider must remain unresolved until the next link callback is released",
                    provider.completedLinkCallbacks.isEmpty())
            } finally {
                provider.allowLinkResolutionReturn.countDown()
                provider.allowNextLinkAfterLateSubtitle.countDown()
            }
            await("the player must resolve this episode payload on its bounded provider worker") {
                provider.linkCalls == 1 && provider.linkRequestData.size == 1
                    && provider.completedLinkCallbacks == listOf(1)
            }
            assertEquals("the player lookup carries the active profile", profileId,
                readField(deferredRequest, "profileId"))
            assertEquals("the player lookup carries the selected episode's provider payload",
                FIXTURE_EPISODE_DATA, readField(deferredRequest, "providerItemId"))
            assertEquals("the player lookup carries the exact selected-episode payload",
                listOf(FIXTURE_EPISODE_DATA), provider.linkRequestData)
            assertEquals("the provider link lookup remains non-casting",
                listOf(false), provider.castingRequests)

            await("the new highest-priority source must receive the pending failover",
                PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS) {
                val request = readField(playerActivity, "request") as? PlaybackRequest
                val playback = readField(playerActivity, "player") as? Player
                val current = readField(playerActivity, "currentSource") as? PlaybackRequest.Source
                request != null && request.defaultSourceId == "provider-source-0"
                    && current?.id == "provider-source-1"
                    && playback != null && playback.playbackState == Player.STATE_READY && playback.isPlaying
                    && readField(playerActivity, "sourcesDialog") == null
            }
            val request = readField(playerActivity, "request") as PlaybackRequest
            assertEquals("the deferred player request remains profile scoped", profileId, request.profileId)
            assertEquals("the deferred player request keeps the series identity",
                detailsItem.stableId, request.media.stableId)
            assertEquals("the deferred player request keeps the tapped episode identity",
                selectedEpisode.stableId, request.episode.id)
            assertEquals("the initial playback default remains stable as higher quality arrives",
                "provider-source-0", request.defaultSourceId)
            assertEquals("the failed HLS default is followed by its MP4 fallback",
                listOf("provider-source-0", "provider-source-1"), request.sources.map { it.id })
            assertEquals("the unsupported queried candidate is skipped while source attribution is retained",
                listOf(hlsFailureSourceUrl, selectedSourceUrl), request.sources.map { it.uri })
            assertTrue("the player does not show the old manual source chooser",
                readField(playerActivity, "sourcesDialog") == null)
            assertNotNull("the failover source must be requested after the first source fails",
                mediaServer!!.awaitRequest("/selected-source.mp4", PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS))
            assertTrue("the fallback's source caption must match the selected provider candidate",
                onMain {
                    val current = readField(playerActivity, "currentSource") as PlaybackRequest.Source
                    val info = readField(playerActivity, "infoView") as TextView
                    current.uri == selectedSourceUrl
                        && info.text.toString().contains("Fixture MP4 1080p")
                })

            onMain { playerActivity.onBackPressed() }
            var backState = ""
            try {
                await("Back from playback returns to the tapped episode card") {
                    val returnedCard = findTagged(activity, "provider_episode_select_0")
                    backState = "finishing=${playerActivity.isFinishing} " +
                        "destroyed=${playerActivity.isDestroyed} shellFocus=${activity.hasWindowFocus()} " +
                        "cardShown=${returnedCard?.isShown} cardAttached=${returnedCard?.isAttachedToWindow} " +
                        "cardFocused=${returnedCard?.isFocused} route=${shellState(activity).route}"
                    (playerActivity.isFinishing || playerActivity.isDestroyed)
                        && activity.hasWindowFocus() && isVisibleLaidOut(returnedCard)
                }
            } catch (failure: AssertionError) {
                throw AssertionError("playback Back return state: $backState", failure)
            }
            assertFalse("returning from the deferred player must clear the stale opening status",
                readField(activity, "providerDetailsStatus") == "Opening Orbit Player…")
            await("first player destruction must finish its Library rollback before the second attempt") {
                playerActivity.isDestroyed && !playbackLaunchAttempted
                    && latePlaybackRollbackFailure == null
            }
            assertEquals("the first player teardown must restore the exact Library baseline",
                libraryRuntimeSnapshot.digest, snapshotLibraryLogicalState().digest)

            provider.allFailingNextCall = true
            val manifestsBeforeTerminalAttempt = mediaServer!!.requests().count {
                (it.path == "/failing-source.m3u8" || it.path == "/failing-source-2.m3u8")
                    && it.status == 200
            }
            val failedSegmentsBeforeTerminalAttempt = mediaServer!!.requests().count {
                it.path == "/segment-403.ts" && it.status == 403
            }
            val secondFailedSegmentsBeforeTerminalAttempt = mediaServer!!.requests().count {
                it.path == "/segment-403-second.ts" && it.status == 403
            }
            expectedPlaybackSourceUri = hlsFailureSourceUrl
            val previousMonitor = playbackMonitor
            if (previousMonitor != null) instrumentation.removeMonitor(previousMonitor)
            playbackMonitor = instrumentation.addMonitor(
                OrbitPlayerActivity::class.java.name, null, false)
            // The first player's destroy callback completed the rollback above. Keep the server
            // alive and re-arm teardown tracking for this second player's progress row.
            playbackActivity = null
            playbackLaunchAttempted = true
            playbackLibraryMutationAllowed = true
            if (playbackLifecycleCallback == null) registerPlaybackLifecycleCallback()
            episodeCard = findTagged(activity, "provider_episode_select_0")
                ?: throw AssertionError("the episode card must remain available for a second playback")
            bringIntoView(episodeCard)
            onMain {
                assertTrue("the same episode can start a fresh provider lookup", episodeCard.performClick())
            }
            val allFailingLaunched = playbackMonitor!!.waitForActivityWithTimeout(UI_TIMEOUT_MS)
                as? OrbitPlayerActivity
            assertNotNull("the all-failing lookup must keep Orbit Player open", allFailingLaunched)
            val allFailingPlayer = allFailingLaunched!!
            playbackActivity = allFailingPlayer
            playbackExpectingAnotherLaunch = false
            await("terminal provider exhaustion must follow the real HTTP 403 callback",
                PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS) {
                onMain {
                    val status = (readField(allFailingPlayer, "statusView") as? TextView)
                        ?.text?.toString().orEmpty()
                    readField(allFailingPlayer, "lastSourceFailed") == true
                        && readField(allFailingPlayer, "providerResolutionTerminal") == true
                        && status.contains("No playable links available")
                        && !allFailingPlayer.isFinishing
                        && !allFailingPlayer.isDestroyed
                        && mediaServer!!.requests().count {
                            (it.path == "/failing-source.m3u8" || it.path == "/failing-source-2.m3u8")
                                && it.status == 200
                        } > manifestsBeforeTerminalAttempt
                        && mediaServer!!.requests().count {
                            it.path == "/segment-403.ts" && it.status == 403
                        } > failedSegmentsBeforeTerminalAttempt
                        && mediaServer!!.requests().count {
                            it.path == "/segment-403-second.ts" && it.status == 403
                        } > secondFailedSegmentsBeforeTerminalAttempt
                }
            }
            assertEquals("the terminal provider lookup must complete after the failed source",
                listOf(1, 2), provider.completedLinkCallbacks)
            assertNull("terminal source exhaustion must clear the deferred provider lookup",
                readField(allFailingPlayer, "deferredProviderPlayback"))
            val terminalMediaRequests = mediaServer!!.requests()
            assertTrue("the first all-failing HLS manifest must return HTTP 200",
                terminalMediaRequests.any { it.path == "/failing-source.m3u8" && it.status == 200 })
            assertTrue("the second all-failing HLS manifest must return HTTP 200",
                terminalMediaRequests.any { it.path == "/failing-source-2.m3u8" && it.status == 200 })
            assertTrue("the first all-failing media segment must return HTTP 403",
                terminalMediaRequests.any { it.path == "/segment-403.ts" && it.status == 403 })
            assertTrue("the second all-failing media segment must return HTTP 403",
                terminalMediaRequests.any { it.path == "/segment-403-second.ts" && it.status == 403 })
            val terminalRequest = readField(allFailingPlayer, "request") as PlaybackRequest
            assertEquals("the second lookup contains both failed HLS candidates",
                setOf(hlsFailureSourceUrl, secondHlsFailureSourceUrl),
                terminalRequest.sources.map { it.uri }.toSet())
            assertEquals("terminal exhaustion must attempt both provider source identities",
                setOf("provider-source-0", "provider-source-1"),
                readField(allFailingPlayer, "attemptedSourceIds"))
            assertEquals("terminal exhaustion must mark both provider source identities failed",
                setOf("provider-source-0", "provider-source-1"),
                readField(allFailingPlayer, "failedSourceIds"))
            assertEquals("both playback attempts must remain bound to the same provider episode",
                listOf(FIXTURE_EPISODE_DATA, FIXTURE_EPISODE_DATA), provider.linkRequestData)
            onMain { allFailingPlayer.onBackPressed() }
            await("Back returns from terminal source exhaustion to the tapped episode") {
                (allFailingPlayer.isFinishing || allFailingPlayer.isDestroyed)
                    && isVisibleLaidOut(findTagged(activity, "provider_episode_select_0"))
            }
            Log.i(TAG, "EPISODE_TAP_FLOW_PASS profile=$profileId provider=$providerId " +
                "episode=${selectedEpisode.stableId} linkCalls=${provider.linkCalls} " +
                "default=${request.defaultSourceId} returnedToDetails=true")
        } finally {
            playbackExpectingAnotherLaunch = false
            val editor = playbackPreferences.edit()
            if (priorPlayerId == null) editor.remove(playbackPreferenceKey)
            else editor.putString(playbackPreferenceKey, priorPlayerId)
            assertTrue("the fixture must restore the original default-player preference", editor.commit())
        }
    }

    @Test
    fun liveProviderDetailsReloadsOnEachOpenButNotWithinSession() {
        val profileId = activeProfileId ?: error("fixture profile was not prepared")
        val pluginId = FIXTURE_PLUGIN_ID
        assertNull("the fixture plugin ID must not already be installed",
            extensionManager.getInstalledPlugin(pluginId))
        assertFalse("the fixture plugin must not already own a registration",
            hostServices.hasOutstandingRegistrations(pluginId))
        assertFalse("the LIVE fixture plugin must not already own a runtime handle",
            runtime.isLoaded(pluginId))
        assertFalse("APIHolder must not already contain the LIVE fixture provider",
            APIHolder.INSTANCE.allProviders.any { it is FixtureLiveApi })

        val markerRoot = File(target.cacheDir, FIXTURE_DIRECTORY_NAME)
        assertTrue("fixture marker directory must be app-private",
            markerRoot.mkdirs() || markerRoot.isDirectory)
        fixtureMarker = File(markerRoot, "registration.fixture-marker")
        assertTrue("the non-package LIVE registration marker must be created",
            fixtureMarker!!.createNewFile())
        val provider = FixtureLiveApi()
        fakeApis = listOf(provider)
        extensionFixtureWritten = true
        addFixtureExtensionGrant(profileId, pluginId)
        fixturePackagePath = File(extensionManager.getInstalledPlugin(pluginId)!!.filePath)
        assertFalse("the metadata-only LIVE fixture must not create a .cs3 package",
            fixturePackagePath!!.exists())
        val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
        selectionFixtureWritten = true
        selectionStore.setOrderedProviderIds(profileId, listOf(providerId))
        selectionStore.setSelectedProviderIds(profileId, listOf(providerId), listOf(providerId))

        val fakePlugin = object : BasePlugin() { }
        transaction = hostServices.beginPluginLoad(pluginId, fixtureMarker!!.absolutePath, fakePlugin)
        fakePlugin.registerMainAPI(provider)
        transaction!!.commit()
        seedLoadedPluginHandle(fakePlugin, fixtureMarker!!, transaction!!, pluginId)
        assertTrue("the LIVE fixture must retain its committed plugin runtime handle",
            runtime.isLoaded(pluginId))
        assertEquals("the registry must discover the LIVE provider metadata",
            Cs3ProviderRegistry.MetadataReadiness.READY,
            registry.awaitProviderMetadata(profileId, pluginId, 0, 5_000L))
        val registeredProvider = application.getMediaProvidersForProfile(profileId).single()
        assertEquals("the registry preserves the LIVE provider identity", providerId,
            registeredProvider.descriptor().id)
        assertTrue("the provider supports LIVE details", registeredProvider.descriptor().details)
        assertTrue("the provider supports LIVE link checks", registeredProvider.descriptor().links)

        val activity = shell ?: error("OrbitShellActivity was not retained")
        onMain {
            val searchNav = findTagged(activity, "nav_SEARCH")
                ?: throw AssertionError("universal Search navigation must be available")
            assertTrue("the actual Search navigation click is accepted", searchNav.performClick())
        }
        await("the universal Search route must open") {
            shellState(activity).route == ShellRoute.SEARCH
                && findTagged(activity, "search_query") is EditText
        }
        val searchInput = findTagged(activity, "search_query") as EditText
        onMain {
            searchInput.setText(FIXTURE_LIVE_SEARCH_QUERY)
            searchInput.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            searchInput.clearFocus()
        }
        val liveDescription = "$FIXTURE_LIVE_TITLE, from $FIXTURE_PROVIDER_NAME"
        await("the synthetic LIVE result must appear in universal Search") {
            findContentDescription(activity, liveDescription)?.isClickable == true
        }
        val liveResult = findContentDescription(activity, liveDescription)!!
        bringIntoView(liveResult)
        onMain { assertTrue("the LIVE result opens the actual provider Details screen",
            liveResult.performClick()) }

        await("the first open must load its own dynamic LIVE payload") {
            readField(activity, "providerDetailsLoading") == false
                && ((readField(activity, "providerDetailsItem") as? MediaItem)?.providerData
                    == "fixture-live-data:1")
        }
        assertEquals("the first Details session performs one live load",
            listOf(FIXTURE_LIVE_URL), provider.detailRequests)
        assertEquals("the visible LIVE payload belongs to the first load",
            "fixture-live-data:1",
            (readField(activity, "providerDetailsItem") as MediaItem).providerData)

        repeat(2) { actionIndex ->
            val play = findTagged(activity, "provider_details_play")
                ?: throw AssertionError("the loaded LIVE Details session must expose Play")
            bringIntoView(play)
            onMain { assertTrue("a LIVE Play action remains available within its Details session",
                play.performClick()) }
            val expectedLinks = actionIndex + 1
            await("the LIVE action $expectedLinks must finish without another details load") {
                provider.linkRequests.size == expectedLinks
                    && readField(activity, "providerDetailsLinksLoading") == false
            }
            assertEquals("each same-session action uses the current LIVE payload",
                List(expectedLinks) { "fixture-live-data:1" }, provider.linkRequests)
            assertEquals("same-session actions do not reload dynamic LIVE details",
                1, provider.detailRequests.size)
        }

        val back = findTagged(activity, "provider_details_back")
            ?: throw AssertionError("LIVE Details must expose the Search return action")
        onMain { assertTrue("Back returns to the same Search listing", back.performClick()) }
        await("LIVE Details closes before the next open") {
            shellState(activity).route == ShellRoute.SEARCH
                && readField(activity, "providerTitleDetailsOpen") == false
        }
        val reopenedResult = findContentDescription(activity, liveDescription)
            ?: throw AssertionError("the LIVE Search result must remain available after Back")
        bringIntoView(reopenedResult)
        onMain { assertTrue("reopening the LIVE result starts a new Details session",
            reopenedResult.performClick()) }
        await("a new open must reload changed dynamic LIVE data") {
            readField(activity, "providerDetailsLoading") == false
                && ((readField(activity, "providerDetailsItem") as? MediaItem)?.providerData
                    == "fixture-live-data:2")
        }
        assertEquals("the second Details session performs a fresh live load",
            listOf(FIXTURE_LIVE_URL, FIXTURE_LIVE_URL), provider.detailRequests)
        assertEquals("the second session displays the changed live payload",
            "fixture-live-data:2",
            (readField(activity, "providerDetailsItem") as MediaItem).providerData)

        val reopenedPlay = findTagged(activity, "provider_details_play")
            ?: throw AssertionError("the second LIVE Details session must expose Play")
        bringIntoView(reopenedPlay)
        onMain { assertTrue("the second session's LIVE action is accepted", reopenedPlay.performClick()) }
        await("the new session action must finish on its newly loaded payload") {
            provider.linkRequests.size == 3
                && readField(activity, "providerDetailsLinksLoading") == false
        }
        assertEquals("the new session action uses the newly loaded LIVE payload",
            listOf("fixture-live-data:1", "fixture-live-data:1", "fixture-live-data:2"),
            provider.linkRequests)
        assertEquals("the new session action does not repeat its details load",
            2, provider.detailRequests.size)
    }

    @Test
    fun runtimeUnloadInvalidatesOldActionAndReplacementRegistrationRehydratesDetails() {
        val profileId = activeProfileId ?: error("fixture profile was not prepared")
        val pluginId = FIXTURE_PLUGIN_ID
        assertNull("the fixture plugin ID must not already be installed",
            extensionManager.getInstalledPlugin(pluginId))
        assertFalse("the fixture plugin must not already own a registration",
            hostServices.hasOutstandingRegistrations(pluginId))
        assertFalse("the fixture plugin must not already own a runtime handle",
            runtime.isLoaded(pluginId))
        assertFalse("APIHolder must not already contain a fixture series API",
            APIHolder.INSTANCE.allProviders.any { it is FixtureSeriesApi })

        val markerRoot = File(target.cacheDir, FIXTURE_DIRECTORY_NAME)
        assertTrue("fixture marker directory must be app-private", markerRoot.mkdirs() || markerRoot.isDirectory)
        fixtureMarker = File(markerRoot, "registration.fixture-marker")
        assertTrue("the registration marker must be created", fixtureMarker!!.createNewFile())
        val firstSourceUrl = "https://media.fixture.invalid/first.mp4"
        val selectedSourceUrl = "https://media.fixture.invalid/selected.mp4"
        val queriedSourceUrl = "https://media.fixture.invalid/queried.mp4"
        val oldProvider = FixtureSeriesApi(firstSourceUrl, selectedSourceUrl, queriedSourceUrl)
        fakeApis = listOf(oldProvider)
        extensionFixtureWritten = true
        addFixtureExtensionGrant(profileId, pluginId)
        fixturePackagePath = File(extensionManager.getInstalledPlugin(pluginId)!!.filePath)
        assertFalse("the synthetic reload fixture must not create a .cs3 package",
            fixturePackagePath!!.exists())

        val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
        selectionFixtureWritten = true
        selectionStore.setOrderedProviderIds(profileId, listOf(providerId))
        selectionStore.setSelectedProviderIds(profileId, listOf(providerId), listOf(providerId))
        val oldPlugin = object : BasePlugin() { }
        transaction = hostServices.beginPluginLoad(pluginId, fixtureMarker!!.absolutePath, oldPlugin)
        oldPlugin.registerMainAPI(oldProvider)
        transaction!!.commit()
        seedLoadedPluginHandle(oldPlugin, fixtureMarker!!, transaction!!, pluginId)
        assertEquals("the initial synthetic series registration reaches provider metadata readiness",
            Cs3ProviderRegistry.MetadataReadiness.READY,
            registry.awaitProviderMetadata(profileId, pluginId, 0, 5_000L))

        val activity = shell ?: error("OrbitShellActivity was not retained")
        onMain {
            val search = findTagged(activity, "nav_SEARCH")
                ?: throw AssertionError("universal Search navigation must be available")
            assertTrue("Search opens the fixture lookup", search.performClick())
        }
        await("the series Search field must be visible") {
            shellState(activity).route == ShellRoute.SEARCH && findTagged(activity, "search_query") is EditText
        }
        val query = findTagged(activity, "search_query") as EditText
        onMain {
            query.setText(FIXTURE_SERIES_SEARCH_QUERY)
            query.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
            query.clearFocus()
        }
        val resultDescription = "$FIXTURE_SERIES_TITLE, from $FIXTURE_PROVIDER_NAME"
        await("the initial registration must return the synthetic series") {
            findContentDescription(activity, resultDescription)?.isClickable == true
        }
        val result = findContentDescription(activity, resultDescription)!!
        bringIntoView(result)
        onMain { assertTrue("the provider result opens Details", result.performClick()) }
        await("the initial registration must load its episode action context") {
            findTagged(activity, "provider_episode_select_0") != null
                && readField(activity, "providerDetailsLoading") == false
        }
        val oldAdapter = readField(activity, "providerDetailsProvider") as MediaProvider
        val oldDetails = readField(activity, "providerDetailsItem") as MediaItem
        val selectedEpisode = readField(activity, "providerDetailsSelectedEpisode")
            as? MediaItem.EpisodeItem
            ?: throw AssertionError("the original episode must be selected in Details")
        assertTrue("the original payload is current before runtime unload",
            oldAdapter.isLoadedDetailsContextCurrent(oldDetails))
        val playbackPreferences = activity.getSharedPreferences(
            "orbit_profile_playback_preferences_v1", Context.MODE_PRIVATE)
        val playbackPreferenceKey = "default_video_player:$profileId"
        val hadPreviousPlayerPreference = playbackPreferences.contains(playbackPreferenceKey)
        val previousPlayerPreference = playbackPreferences.getString(playbackPreferenceKey, null)
        val monitor = instrumentation.addMonitor(OrbitPlayerActivity::class.java.name, null, false)
        playbackMonitor = monitor
        playbackActivity = null
        playbackLaunchAttempted = false
        playbackLibraryMutationAllowed = true
        expectedPlaybackSourceUri = firstSourceUrl
        expectedPlaybackTitle = oldDetails.title
        expectedPlaybackProviderId = providerId
        var staleTapAt = 0L
        var providerWorkQuiesced = false
        var libraryRestored = false
        var unexpectedPlayerObserved = false
        var staleCandidate: Any? = null
        try {
            assertTrue("the fixture forces the link-first Details flow for its source candidate",
                playbackPreferences.edit().putString(playbackPreferenceKey,
                    "fixture.external.player").commit())
            val loadEpisodeLinks = OrbitShellActivity::class.java.getDeclaredMethod(
                "loadProviderEpisodeLinks", MediaItem.EpisodeItem::class.java,
            ).apply { isAccessible = true }
            onMain { loadEpisodeLinks.invoke(activity, selectedEpisode) }
            await("the old Details session must load a source before runtime unload") {
                oldProvider.linkCalls == 1
                    && readField(activity, "providerDetailsLinksLoaded") == true
                    && (readField(activity, "providerPlayableCandidates") as? List<*>)
                        ?.isNotEmpty() == true
            }
            staleCandidate = (readField(activity, "providerPlayableCandidates") as List<*>)
                .firstOrNull() ?: throw AssertionError("the old link callback must provide a candidate")

            assertTrue("the actual runtime must unload the registered synthetic plugin cleanly",
                runtime.deactivateIfClean(pluginId))
            assertFalse("runtime unload must release its plugin handle", runtime.isLoaded(pluginId))
            assertFalse("runtime unload removes the retained adapter registration lease",
                oldAdapter.isRegistrationCurrent())
            assertFalse("runtime unload invalidates the retained episode action context",
                oldAdapter.isLoadedDetailsContextCurrent(oldDetails))
            assertTrue("no current provider may retain the unloaded registration",
                application.getMediaProvidersForProfile(profileId).none {
                    it.descriptor().id == providerId
                })

            staleTapAt = SystemClock.uptimeMillis()
            val staleEpisode = findTagged(activity, "provider_episode_select_0")
                ?: throw AssertionError("the old Details screen must retain its episode control until the stale tap")
            onMain { assertTrue("selecting the stale episode is handled", staleEpisode.performClick()) }
            val unavailableMessage = "This extension has changed or is unavailable. Open a current listing to continue."
            await("the stale action must be disabled after its provider unloads") {
                findText(activity, unavailableMessage) != null
            }

            val replacementProvider = FixtureSeriesApi(
                firstSourceUrl, selectedSourceUrl, queriedSourceUrl, REPLACEMENT_EPISODE_DATA,
            )
            fakeApis = listOf(oldProvider, replacementProvider)
            val replacementPlugin = object : BasePlugin() { }
            transaction = hostServices.beginPluginLoad(
                pluginId, fixtureMarker!!.absolutePath, replacementPlugin,
            )
            replacementPlugin.registerMainAPI(replacementProvider)
            transaction!!.commit()
            seedLoadedPluginHandle(replacementPlugin, fixtureMarker!!, transaction!!, pluginId)
            assertEquals("replacement provider metadata becomes ready after its committed registration",
                Cs3ProviderRegistry.MetadataReadiness.READY,
                registry.awaitProviderMetadata(profileId, pluginId, 0, 5_000L))
            val replacementAdapter = application.getMediaProvidersForProfile(profileId)
                .single { it.descriptor().id == providerId }
            assertTrue("the replacement provider belongs to the current registration",
                replacementAdapter.isRegistrationCurrent())
            assertFalse("a new registration must not revive the old adapter or its loaded episode context",
                oldAdapter.isLoadedDetailsContextCurrent(oldDetails))

            val staleLaunch = OrbitShellActivity::class.java.getDeclaredMethod(
                "launchProviderEpisodePlayback", MediaProvider::class.java,
                MediaItem::class.java, MediaItem.EpisodeItem::class.java,
                staleCandidate!!.javaClass,
            ).apply { isAccessible = true }
            staleTapAt = SystemClock.uptimeMillis()
            onMain {
                staleLaunch.invoke(activity, oldAdapter, oldDetails, selectedEpisode, staleCandidate)
            }
            await("replacement Details must load its new episode payload") {
                readField(activity, "providerDetailsLoading") == false
                    && (readField(activity, "providerDetailsItem") as? MediaItem)
                        ?.episodes?.firstOrNull()?.providerData == REPLACEMENT_EPISODE_DATA
            }
            val replacementDetails = readField(activity, "providerDetailsItem") as MediaItem
            val replacementDetailsAdapter = readField(activity, "providerDetailsProvider") as MediaProvider
            assertTrue("reopened Details use the replacement registration",
                replacementDetailsAdapter.isRegistrationCurrent())
            assertTrue("the replacement episode context is current",
                replacementDetailsAdapter.isLoadedDetailsContextCurrent(replacementDetails))
            assertEquals("the replacement Details payload is distinguishable from the stale payload",
                REPLACEMENT_EPISODE_DATA,
                replacementDetails.episodes?.firstOrNull()?.providerData)
            assertEquals("the retired registration receives no link callbacks after unload",
                1, oldProvider.linkCalls)
            assertEquals("rehydration alone does not dispatch a replacement link action",
                0, replacementProvider.linkCalls)
        } finally {
            val guardDeadline = if (staleTapAt == 0L) SystemClock.uptimeMillis()
                    else staleTapAt + PLAYER_LATE_START_GRACE_MS
            val quiescenceDeadline = SystemClock.uptimeMillis() + PROVIDER_DRAIN_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < guardDeadline
                    || (!providerWorkQuiesced && SystemClock.uptimeMillis() < quiescenceDeadline)) {
                instrumentation.waitForIdleSync()
                val player = monitor.lastActivity as? OrbitPlayerActivity
                if (player != null) {
                    unexpectedPlayerObserved = true
                    playbackActivity = player
                    playbackLaunchAttempted = true
                    if (!player.isFinishing && !player.isDestroyed) onMain { player.finish() }
                }
                if (!providerWorkQuiesced) {
                    providerWorkQuiesced = registry.awaitAllProviderWorkQuiesced(50L)
                }
                SystemClock.sleep(50L)
            }
            val player = monitor.lastActivity as? OrbitPlayerActivity
            if (player != null) {
                unexpectedPlayerObserved = true
                playbackActivity = player
                playbackLaunchAttempted = true
                if (!player.isFinishing && !player.isDestroyed) onMain { player.finish() }
                await("any unexpected player must stop before Library restoration") {
                    player.isDestroyed
                }
            }
            instrumentation.waitForIdleSync()
            restoreLibrarySnapshotsAfterPlaybackStops()
            val playerPreferenceEditor = playbackPreferences.edit()
            if (hadPreviousPlayerPreference) {
                playerPreferenceEditor.putString(playbackPreferenceKey, previousPlayerPreference)
            } else {
                playerPreferenceEditor.remove(playbackPreferenceKey)
            }
            assertTrue("the fixture restores the original default-player preference",
                playerPreferenceEditor.commit())
            assertEquals("the stale-action guard must restore the exact Library baseline",
                libraryRuntimeSnapshot.digest, snapshotLibraryLogicalState().digest)
            libraryRestored = true
            if (providerWorkQuiesced && libraryRestored) {
                instrumentation.removeMonitor(monitor)
                playbackMonitor = null
                playbackLaunchAttempted = false
                playbackActivity = null
                playbackLibraryMutationAllowed = false
                expectedPlaybackSourceUri = null
                expectedPlaybackTitle = null
                expectedPlaybackProviderId = null
            }
        }
        assertTrue("provider work must quiesce before player guards are removed", providerWorkQuiesced)
        assertFalse("a stale episode action must not launch Orbit Player", unexpectedPlayerObserved)
        assertEquals("the retired provider is not called again after its source becomes stale",
            1, oldProvider.linkCalls)
    }

    @After
    fun removeFixtureRestoreSnapshotsAndLeaveHomeForeground() {
        episodeProgressiveFixture?.allowLinkResolutionReturn?.countDown()
        episodeProgressiveFixture = null
        if (!::libraryRuntimeSnapshot.isInitialized) {
            cleanupAfterAbortedSetup()
            return
        }
        val pluginId = FIXTURE_PLUGIN_ID
        val app = if (::application.isInitialized) application else null
        val registryRef = if (::registry.isInitialized) registry else null
        var cleanupFailure: Throwable? = null
        try {
            stopPlaybackAndRestoreFixtureRows()
        } catch (error: Throwable) {
            cleanupFailure = error
        }
        val monitor = playbackMonitor
        val lifecycleCallback = playbackLifecycleCallback
        try {
            if (registryRef != null && transaction != null) {
                registryRef.blockPluginCalls(pluginId)
                val activity = shell
                if (activity != null && !activity.isDestroyed) closeActivityProviderBatches(activity)
                assertTrue("fixture provider calls must drain before rollback",
                    registryRef.pauseAndAwaitPluginCalls(pluginId, PROVIDER_DRAIN_TIMEOUT_MS))
                assertTrue("all provider calls must drain before restoring state",
                    registryRef.awaitAllProviderWorkQuiesced(PROVIDER_DRAIN_TIMEOUT_MS))
                assertTrue("provider registration snapshots must drain before rollback",
                    registryRef.awaitProviderSnapshotsQuiesced(PROVIDER_DRAIN_TIMEOUT_MS))
                if (runtime.isLoaded(pluginId)) {
                    assertTrue("the runtime must unload the fixture handle after provider rollback",
                        runtime.deactivateIfClean(pluginId))
                } else {
                    transaction!!.rollback()
                    registryRef.onPluginCleanupFinished(pluginId)
                }
                transaction = null
            }
        } catch (error: Throwable) {
            val firstFailure = cleanupFailure
            if (firstFailure == null) cleanupFailure = error
            else if (firstFailure !== error) firstFailure.addSuppressed(error)
        }

        try {
            if (::extensionPreferences.isInitialized && extensionFixtureWritten) {
                restoreFixtureExtensionPreferences(extensionSnapshot, extensionPreferences)
                extensionFixtureWritten = false
            }

            fixtureMarker?.let { marker ->
                assertTrue("the exact registration marker must be removed", marker.delete() || !marker.exists())
                val directory = marker.parentFile
                assertTrue("fixture marker directory must be empty after cleanup",
                    directory == null || directory.listFiles().isNullOrEmpty())
                assertTrue("fixture marker directory must be removed", directory == null || directory.delete() || !directory.exists())
            }

            if (registryRef != null && transaction == null) {
                assertFalse("the fixture registration must be absent after transaction rollback",
                    hostServices.hasOutstandingRegistrations(pluginId))
                assertFalse("the runtime must release the fixture handle after rollback",
                    runtime.isLoaded(pluginId))
                assertFalse("APIHolder must not retain either fixture API", APIHolder.INSTANCE.allProviders.any { api ->
                    fakeApis.any { it === api }
                })
                assertSameIdentityOrder("APIHolder provider order must return to its pre-test state", apiSnapshot)
            }

            val activity = shell
            if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
                onMain {
                    closeProviderDetails(activity)
                    shellState(activity).navigateTo(ShellRoute.HOME)
                    setField(activity, "profileConfirmed", profileStore.activeProfile != null)
                    invokeRender(activity)
                }
                await("Orbit Home must remain in the foreground after fixture cleanup") {
                    shellState(activity).route == ShellRoute.HOME && !activity.isFinishing
                }
                instrumentation.waitForIdleSync()
            }
            if (app != null && originalRuntimeProfileId != runtime.activeProfileId) {
                val failures = runtime.reconcileProfile(originalRuntimeProfileId, extensionManager)
                assertTrue("the original runtime profile must be restored without plugin activation", failures.isEmpty())
            }
            val runtimeProfileAfterHome = runtime.activeProfileId

            // Restore the post-startup provider baseline after the last Home render. Rendering
            // Home initializes empty selected/known lists for the active profile, so restoring
            // before this point would allow ordinary shell startup work to rewrite the file.
            if (::providerPreferences.isInitialized && selectionFixtureWritten) {
                restorePreferences(providerRuntimeSnapshot, providerPreferences)
                selectionFixtureWritten = false
            }
            if (::profilePreferences.isInitialized && profileFixtureWritten) {
                restorePreferences(profileSnapshot, profilePreferences)
                profileFixtureWritten = false
            }

            if (::extensionPreferences.isInitialized) {
                assertFixtureExtensionPreferencesRestored(extensionSnapshot, extensionPreferences)
            }
            if (::providerPreferences.isInitialized) {
                assertPreferencesRestored(providerRuntimeSnapshot, providerPreferences,
                    "provider ordering and grants must return to their post-startup pre-fixture state")
            }
            if (::profilePreferences.isInitialized) {
                assertPreferencesRestored(profileSnapshot, profilePreferences,
                    "profile preferences must return to their pre-test state")
            }
            val libraryAfterFiles = snapshotLibraryFiles()
            val libraryAfterLogical = snapshotLibraryLogicalState()
            Log.i(TAG, "LIBRARY_AFTER rawFiles=${libraryAfterFiles.files} " +
                "runtimeBaselineFiles=${libraryRuntimeFiles.files} " +
                "logical=${libraryAfterLogical.summary} sha256=${libraryAfterLogical.digest}")
            assertEquals("the local library schema and logical rows must remain unchanged after fixture cleanup",
                libraryRuntimeSnapshot.digest, libraryAfterLogical.digest)
            assertFalse("the fixture .cs3 package path must remain absent",
                fixturePackagePath?.exists() == true)
            Log.i(TAG, "STATE_AFTER ${stateSummary()}")
            assertEquals("the runtime profile must match its original value after final Home render",
                originalRuntimeProfileId, runtimeProfileAfterHome)
            assertFalse("cleanup must leave the CS3 canary gate disabled", BuildConfig.CS3_CANARY_BUILD)
            assertTrue("cleanup must preserve the accepted target crosswalk",
                runtime.isTargetPluginCrosswalkValidated)
            if (cleanupFailure == null && !playbackLaunchAttempted
                && !playbackLibraryMutationAllowed && !populatedLibraryFixtureSeeded
                && playbackLifecycleCallback == null) {
                Log.i(TAG, "CLEANUP_PASS fixturePackageExists=${fixturePackagePath?.exists() == true} " +
                    "markerExists=${fixtureMarker?.exists() == true} route=${shellState(shell).route} " +
                    "canary=${BuildConfig.CS3_CANARY_BUILD} crosswalk=${runtime.isTargetPluginCrosswalkValidated}")
            } else {
                Log.e(TAG, "CLEANUP_INCOMPLETE playbackLaunchAttempted=$playbackLaunchAttempted", cleanupFailure)
            }
        } catch (error: Throwable) {
            if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
        }
        if (playbackLaunchAttempted && playbackActivity?.isDestroyed == true
            && playbackLibraryMutationAllowed) {
            try {
                restoreLibrarySnapshotsAfterPlaybackStops()
                latePlaybackRollbackFailure = null
            } catch (error: Throwable) {
                latePlaybackRollbackFailure = error
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            }
        }
        if (playbackLaunchAttempted && playbackActivity?.isDestroyed == true
            && !playbackLibraryMutationAllowed && latePlaybackRollbackFailure == null) {
            playbackLaunchAttempted = false
        }
        if (playbackLaunchAttempted) scheduleDeferredMediaServerClose()
        if (cleanupFailure == null && registryRef != null) {
            try {
                assertTrue("provider callbacks must quiesce before player guards are removed",
                    registryRef.awaitAllProviderWorkQuiesced(PROVIDER_DRAIN_TIMEOUT_MS))
                assertTrue("provider snapshots must quiesce before player guards are removed",
                    registryRef.awaitProviderSnapshotsQuiesced(PROVIDER_DRAIN_TIMEOUT_MS))
            } catch (error: Throwable) {
                cleanupFailure = error
            }
        }
        if (cleanupFailure == null) {
            if (monitor != null) {
                try {
                    instrumentation.removeMonitor(monitor)
                    playbackMonitor = null
                } catch (error: Throwable) {
                    cleanupFailure = error
                }
            }
            if (cleanupFailure == null && lifecycleCallback != null
                && ::application.isInitialized && !playbackLaunchAttempted) {
                try {
                    application.unregisterActivityLifecycleCallbacks(lifecycleCallback)
                    playbackLifecycleCallback = null
                } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error
                    else cleanupFailure!!.addSuppressed(error)
                }
            }
        }
        if (cleanupFailure != null) throw AssertionError("provider fixture cleanup failed", cleanupFailure)
    }

    /** Cleans only setup state when @Before fails before its complete rollback baseline exists. */
    private fun cleanupAfterAbortedSetup() {
        var cleanupFailure: Throwable? = null
        fun rememberCleanupFailure(error: Throwable) {
            val original = cleanupFailure
            if (original == null) cleanupFailure = error
            else if (original !== error) original.addSuppressed(error)
        }

        val activity = shell
        if (activity != null && !activity.isDestroyed) {
            try {
                if (!activity.isFinishing) onMain { activity.finish() }
                await("Orbit Shell must finish after setup abort") { activity.isDestroyed }
                instrumentation.waitForIdleSync()
            } catch (error: Throwable) {
                rememberCleanupFailure(error)
            }
        }
        if (::profileSnapshot.isInitialized && ::runtime.isInitialized
            && ::extensionManager.isInitialized
            && runtime.activeProfileId != originalRuntimeProfileId) {
            try {
                val failures = runtime.reconcileProfile(originalRuntimeProfileId, extensionManager)
                assertTrue("the original runtime profile must be restored after setup abort", failures.isEmpty())
            } catch (error: Throwable) {
                rememberCleanupFailure(error)
            }
        }

        val lifecycleCallback = playbackLifecycleCallback
        if (lifecycleCallback != null && ::application.isInitialized) {
            try {
                application.unregisterActivityLifecycleCallbacks(lifecycleCallback)
                playbackLifecycleCallback = null
            } catch (error: Throwable) {
                rememberCleanupFailure(error)
            }
        }
        if (::providerSnapshot.isInitialized && ::providerPreferences.isInitialized) {
            try {
                restorePreferences(providerSnapshot, providerPreferences)
                assertPreferencesRestored(providerSnapshot, providerPreferences,
                    "provider preferences must return to their pre-startup state after setup abort")
            } catch (error: Throwable) {
                rememberCleanupFailure(error)
            }
        }
        if (profileFixtureWritten && ::profileSnapshot.isInitialized
            && ::profilePreferences.isInitialized) {
            try {
                restorePreferences(profileSnapshot, profilePreferences)
                assertPreferencesRestored(profileSnapshot, profilePreferences,
                    "profile preferences must return to their pre-test state after setup abort")
                profileFixtureWritten = false
            } catch (error: Throwable) {
                rememberCleanupFailure(error)
            }
        }
        if (cleanupFailure != null) {
            throw AssertionError("provider fixture setup-abort cleanup failed", cleanupFailure)
        }
        Log.i(TAG, "CLEANUP_SETUP_ABORTED completeLibraryBaseline=false " +
            "libraryFixtureSeeded=$populatedLibraryFixtureSeeded recoveryGateUnchanged=true")
    }

    private fun stopPlaybackAndRestoreFixtureRows() {
        var playbackSettled = false
        var cleanupFailure: Throwable? = null
        fun rememberCleanupFailure(error: Throwable) {
            val original = cleanupFailure
            if (original == null) cleanupFailure = error
            else if (original !== error) original.addSuppressed(error)
        }

        try {
            try {
                if (playbackLaunchAttempted) instrumentation.waitForIdleSync()
                var activePlayer = playbackActivity ?: monitoredFixturePlayer()
                if (playbackLaunchAttempted && activePlayer == null) {
                    // A timed-out ActivityMonitor wait must not leave a queued player launch able to
                    // write after database rollback. Finishing Shell drains/cancels that launch path.
                    val activeShell = shell
                    if (activeShell != null && !activeShell.isDestroyed) {
                        if (!activeShell.isFinishing) onMain { activeShell.finish() }
                        await("Orbit Shell or a late player must settle before rollback", UI_TIMEOUT_MS * 2) {
                            activeShell.isDestroyed || playbackActivity != null
                        }
                        instrumentation.waitForIdleSync()
                    }
                    activePlayer = playbackActivity ?: monitoredFixturePlayer()
                    if (activePlayer == null && (activeShell == null || activeShell.isDestroyed)) {
                        // Keep the lifecycle callback and rollback armed for a full second monitor wait
                        // after Shell is gone; a pending Activity launch must settle before rollback.
                        val latePlayer = playbackMonitor?.waitForActivityWithTimeout(PLAYER_LATE_START_GRACE_MS)
                        if (latePlayer is OrbitPlayerActivity) captureFixturePlaybackActivity(latePlayer)
                        instrumentation.waitForIdleSync()
                        activePlayer = playbackActivity ?: monitoredFixturePlayer()
                    }
                }
                if (activePlayer != null && !activePlayer.isDestroyed) {
                    val playerToFinish = activePlayer
                    if (!playerToFinish.isFinishing) onMain { playerToFinish.finish() }
                    try {
                        await("Orbit Player must finish before provider fixture rollback") {
                            playerToFinish.isDestroyed
                        }
                    } catch (firstFailure: Throwable) {
                        if (!playerToFinish.isDestroyed) onMain { playerToFinish.finish() }
                        instrumentation.waitForIdleSync()
                        try {
                            await("Orbit Player must stop before library rollback after retry", UI_TIMEOUT_MS * 2) {
                                playerToFinish.isDestroyed
                            }
                        } catch (retryFailure: Throwable) {
                            retryFailure.addSuppressed(firstFailure)
                            throw AssertionError(
                                "Orbit Player may still write library progress; database rollback was skipped",
                                retryFailure)
                        }
                    }
                    instrumentation.waitForIdleSync()
                }
                val latestPlayer = playbackActivity ?: monitoredFixturePlayer()
                if (playbackLaunchAttempted && latestPlayer != null && !latestPlayer.isDestroyed) {
                    throw AssertionError("Orbit Player may still write library progress; database rollback was skipped")
                }
                if (playbackLaunchAttempted && latestPlayer == null) {
                    val shellSettled = shell == null || shell?.isDestroyed == true
                    if (!shellSettled) {
                        throw AssertionError("Orbit Shell did not settle; database rollback was skipped")
                    }
                    // No Activity after the grace window is still unresolved: keep the callback and
                    // snapshot rollback armed so a delayed accepted launch cannot write past cleanup.
                    throw AssertionError(
                        "Orbit Player launch remains unresolved; database rollback was skipped and the late-launch guard remains armed")
                }
                playbackLaunchAttempted = false
                playbackActivity = null
                playbackSettled = true
            } catch (error: Throwable) {
                rememberCleanupFailure(error)
            }

            if (playbackSettled) {
                try {
                    if (realFcastAcceptanceEnabled) {
                        val activeShell = shell
                        if (activeShell != null && !activeShell.isDestroyed) {
                            val manager = readField(activeShell, "fcastManager") as? FcastManager
                            manager?.stop()
                        }
                        await("Orbit stops FCast discovery during fixture cleanup", UI_TIMEOUT_MS) {
                            FcastManager.currentDevices.isEmpty()
                        }
                        Log.i(TAG, "FCAST_CLEANUP discoveryStopped=true")
                    }
                } catch (error: Throwable) {
                    rememberCleanupFailure(error)
                }

                try {
                    takeMediaServer()?.close()
                } catch (error: Throwable) {
                    rememberCleanupFailure(error)
                }
            }
        } catch (error: Throwable) {
            rememberCleanupFailure(error)
        } finally {
            if (playbackSettled) {
                val previousRollbackFailure = latePlaybackRollbackFailure
                try {
                    restoreLibrarySnapshotsAfterPlaybackStops()
                    latePlaybackRollbackFailure = null
                } catch (error: Throwable) {
                    if (previousRollbackFailure != null && previousRollbackFailure !== error) {
                        error.addSuppressed(previousRollbackFailure)
                    }
                    latePlaybackRollbackFailure = error
                    rememberCleanupFailure(error)
                }
            }
        }

        cleanupFailure?.let { throw it }
    }

    /**
     * Keep the loopback source available while a late player is alive, but bound its lifetime if
     * Android never delivers the Activity. A late player's destroy callback closes it sooner.
     */
    private fun scheduleDeferredMediaServerClose() {
        if (mediaServer == null || deferredMediaServerClose != null) return
        val closer = Thread({
            try {
                Thread.sleep(PLAYER_LATE_START_GRACE_MS)
                val server = synchronized(this) {
                    val latePlayerStillActive = playbackActivity?.let { !it.isDestroyed } == true
                    if (latePlayerStillActive) null
                    else mediaServer.also { mediaServer = null }
                }
                if (server != null) {
                    try {
                        server.close()
                        Log.i(TAG, "DEFERRED_MEDIA_SERVER_CLOSE completed=true")
                    } catch (error: Throwable) {
                        latePlaybackRollbackFailure = error
                        Log.e(TAG, "DEFERRED_MEDIA_SERVER_CLOSE_ERROR", error)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                deferredMediaServerClose = null
            }
        }, "orbit-provider-deferred-media-close").apply { isDaemon = true }
        deferredMediaServerClose = closer
        closer.start()
    }

    private fun closeMediaServerAfterLatePlayback() {
        try {
            takeMediaServer()?.close()
            deferredMediaServerClose?.interrupt()
            deferredMediaServerClose = null
        } catch (error: Throwable) {
            latePlaybackRollbackFailure = latePlaybackRollbackFailure?.also { previous ->
                if (previous !== error) previous.addSuppressed(error)
            } ?: error
            Log.e(TAG, "LATE_MEDIA_SERVER_CLOSE_ERROR", error)
        }
    }

    private fun takeMediaServer(): LoopbackMediaFixtureServer? = synchronized(this) {
        mediaServer.also { mediaServer = null }
    }

    private fun captureFixturePlaybackActivity(activity: Activity) {
        val player = activity as? OrbitPlayerActivity ?: return
        synchronized(this) {
            if (isFixturePlaybackActivity(player)) playbackActivity = player
        }
    }

    private fun monitoredFixturePlayer(): OrbitPlayerActivity? {
        val player = playbackMonitor?.lastActivity as? OrbitPlayerActivity ?: return null
        return player.takeIf { isFixturePlaybackActivity(it) }
    }

    private fun isFixturePlaybackActivity(activity: OrbitPlayerActivity): Boolean {
        val expectedUri = expectedPlaybackSourceUri ?: return false
        val expectedTitle = expectedPlaybackTitle ?: return false
        val expectedProvider = expectedPlaybackProviderId ?: return false
        val request = try {
            readField(activity, "request") as? PlaybackRequest
        } catch (_: Throwable) {
            null
        } ?: return false
        return request.media.title == expectedTitle
            && request.media.providerId == expectedProvider
            && request.sources.any { it.uri == expectedUri }
    }

    private fun continueWithSelectedProfile(activity: OrbitShellActivity) {
        await("the profile confirmation or confirmed shell must render") {
            findTagged(activity, "nav_HOME") != null || findText(activity, "Continue") != null
        }
        val continueButton = findText(activity, "Continue")
        if (continueButton != null) onMain {
            assertTrue("the existing or disposable profile continues into Orbit", continueButton.performClick())
        }
        await("the confirmed Orbit shell must expose Home navigation") {
            findTagged(activity, "nav_HOME") != null
        }
    }

    private fun addFixtureExtensionGrant(profileId: String, pluginId: String) {
        val installedKey = "installed_plugins_json"
        val enabledKey = "profile_enabled_plugins_json"
        val installed = JSONArray(extensionPreferences.getString(installedKey, "[]"))
        for (index in 0 until installed.length()) {
            assertFalse("fixture ID collision is not safe", pluginId == installed.optJSONObject(index)?.optString("id"))
        }
        val metadata = JSONObject()
            .put("id", pluginId)
            .put("repositoryUrl", FIXTURE_REPOSITORY_URL)
            .put("name", FIXTURE_PROVIDER_NAME)
            .put("internalName", FIXTURE_PROVIDER_NAME)
            .put("hasMainPage", true)
            .put("version", 1L)
            .put("sha256", "0".repeat(64))
            .put("hashWasPublished", false)
            .put("pluginClassName", "fixture.OrbitProviderFlowNeverLoaded")
            .put("requiresResources", false)
            .put("fileName", "provider-flow-fixture.cs3")
            .put("activationError", JSONObject.NULL)
            .put("installedAt", 1L)
        installed.put(metadata)

        val enabled = JSONObject(extensionPreferences.getString(enabledKey, "{}") ?: "{}")
        val profileIds = enabled.optJSONArray(profileId) ?: JSONArray()
        for (index in 0 until profileIds.length()) {
            assertFalse("fixture grant must not duplicate a prior plugin ID", pluginId == profileIds.optString(index))
        }
        profileIds.put(pluginId)
        enabled.put(profileId, profileIds)
        assertTrue("fixture plugin metadata and profile grant must be stored locally",
            extensionPreferences.edit()
                .putString(installedKey, installed.toString())
                .putString(enabledKey, enabled.toString())
                .commit())
        assertNotNull("Orbit must see the fixture as an installed provider",
            extensionManager.getInstalledPlugin(pluginId))
        assertTrue("Orbit must see the fixture grant only for the active isolated profile",
            extensionManager.isEnabledForProfile(profileId, pluginId))
    }

    private fun seedPopulatedPersonalLibrary(
        profileId: String,
        providerId: String,
        miraArtworkUrl: String,
        hannahArtworkUrl: String,
        coastArtworkUrl: String,
    ): List<LocalLibraryStore.Collection> {
        val library = application.localLibraryStore
        assertTrue("the disposable fixture profile starts with no saved titles",
            library.getSavedTitles(profileId).isEmpty())
        assertTrue("the disposable fixture profile starts with no collections",
            library.getCollections(profileId).isEmpty())
        // Arm DB cleanup before the first write so a partial seed or later UI assertion failure
        // still restores every touched library table from @After.
        populatedLibraryFixtureSeeded = true
        data class SeedTitle(
            val title: LocalLibraryStore.SavedTitle,
            val status: String,
            val favorite: Boolean,
        )
        val seededTitles = listOf(
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-quiet-between", providerId, "The Quiet Between", "SERIES",
                "", miraArtworkUrl, "2025", "A signal from a silent coastline brings a small town's stories into focus.",
            ), LocalLibraryStore.STATUS_COMPLETED, true),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-northbound", providerId, "Northbound", "MOVIE",
                "", hannahArtworkUrl, "2024", "A night operator follows a distant signal home.",
            ), LocalLibraryStore.STATUS_PLAN_TO_WATCH, false),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-glass-harbor", providerId, "Glass Harbor", "SERIES",
                "", coastArtworkUrl, "", "A harbor town finds its stories written in the fog.",
            ), LocalLibraryStore.STATUS_WATCHING, true),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-silent-meridian", providerId, "Silent Meridian", "SERIES",
                "", coastArtworkUrl, "", "Two cartographers map a shoreline that changes each evening.",
            ), LocalLibraryStore.STATUS_WATCHING, true),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-long-winter", providerId, "Long Winter", "MOVIE",
                "", hannahArtworkUrl, "2022", "A family waits out a winter far from the city.",
            ), LocalLibraryStore.STATUS_ON_HOLD, false),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-emberline", providerId, "Emberline", "SERIES",
                "", miraArtworkUrl, "2021", "A young pilot carries a message across a changing world.",
            ), LocalLibraryStore.STATUS_COMPLETED, false),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-paper-moons", providerId, "Paper Moons", "SERIES",
                "", coastArtworkUrl, "", "A quiet artist begins painting the night sky.",
            ), LocalLibraryStore.STATUS_DROPPED, false),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-wild-current", providerId, "Wild Current", "SERIES",
                "", hannahArtworkUrl, "2025", "A river guide searches for the source of an old map.",
            ), LocalLibraryStore.STATUS_PLAN_TO_WATCH, false),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-blue-hours", providerId, "Blue Hours", "MOVIE",
                "", miraArtworkUrl, "2019", "Friends meet each summer as the light changes.",
            ), LocalLibraryStore.STATUS_COMPLETED, false),
            SeedTitle(LocalLibraryStore.SavedTitle(
                "mockup-fixture-far-mountain", providerId, "The Far Mountain", "SERIES",
                "", coastArtworkUrl, "2018", "A mountain town follows a trail beyond the snow line.",
            ), LocalLibraryStore.STATUS_PLAN_TO_WATCH, true),
        )
        data class ContinueSeed(
            val stableId: String,
            val title: String,
            val artworkUrl: String,
            val synopsis: String,
            val episodeId: String,
            val episodeTitle: String,
            val season: Int,
            val episodeNumber: Int,
            val minutesLeft: Long,
        )
        val continueOnlyTitles = listOf(
            LocalLibraryStore.SavedTitle("mockup-cw-deep-signal", providerId, "Deep Signal", "SERIES",
                "", miraArtworkUrl, "", "A coastal radio operator receives a message from tomorrow."),
            LocalLibraryStore.SavedTitle("mockup-cw-signal-08", providerId, "Signal / 08", "SERIES",
                "", hannahArtworkUrl, "", "A late night broadcast ties together eight lives."),
            LocalLibraryStore.SavedTitle("mockup-cw-afterlight", providerId, "Afterlight", "SERIES",
                "", miraArtworkUrl, "", "A photographer follows the final light across the city."),
            LocalLibraryStore.SavedTitle("mockup-cw-northbound-line", providerId,
                "The Northbound Line", "SERIES", "", coastArtworkUrl, "",
                "A train crew carries a lost traveler across the winter coast."),
            LocalLibraryStore.SavedTitle("mockup-cw-atlas-of-ash", providerId, "Atlas of Ash", "SERIES",
                "", hannahArtworkUrl, "", "A mapmaker finds a route through a vanished country."),
        )
        val continueOnlyStableIds = continueOnlyTitles.map { it.stableId }.toSet()
        // Save in reverse order with distinct timestamps so the six-title recent rail is stable.
        seededTitles.asReversed().forEach { seeded ->
            library.saveTitle(profileId, seeded.title)
            SystemClock.sleep(2L)
        }
        for (seeded in seededTitles) {
            assertTrue("fixture favorite state is applied for ${seeded.title.title}",
                library.setFavorite(profileId, seeded.title.stableId, seeded.favorite))
            assertTrue("fixture watch status is applied for ${seeded.title.title}",
                library.setWatchStatus(profileId, seeded.title.stableId, seeded.status))
        }
        val boardContinueSeeds = listOf(
            ContinueSeed("mockup-cw-deep-signal", "Deep Signal", miraArtworkUrl,
                "A coastal radio operator receives a message from tomorrow.", "deep-signal-s1-e4",
                "The Signal Below", 1, 4, 24L),
            ContinueSeed("mockup-cw-signal-08", "Signal / 08", hannahArtworkUrl,
                "A late night broadcast ties together eight lives.", "signal-08-s1-e8",
                "Tomorrow’s Broadcast", 1, 8, 12L),
            ContinueSeed("mockup-cw-afterlight", "Afterlight", miraArtworkUrl,
                "A photographer follows the final light across the city.", "afterlight-s1-e3",
                "The Blue Hour", 1, 3, 35L),
            ContinueSeed("mockup-cw-northbound-line", "The Northbound Line", coastArtworkUrl,
                "A train crew carries a lost traveler across the winter coast.", "northbound-line-s2-e1",
                "The Long Crossing", 2, 1, 38L),
            ContinueSeed("mockup-fixture-glass-harbor", "Glass Harbor", coastArtworkUrl,
                "A harbor town finds its stories written in the fog.", "glass-harbor-s1-e6",
                "Tides at Night", 1, 6, 19L),
            ContinueSeed("mockup-fixture-silent-meridian", "Silent Meridian", coastArtworkUrl,
                "Two cartographers map a shoreline that changes each evening.", "silent-meridian-s1-e2",
                "First Light", 1, 2, 6L),
            ContinueSeed("mockup-fixture-paper-moons", "Paper Moons", coastArtworkUrl,
                "A quiet artist begins painting the night sky.", "paper-moons-s2-e5",
                "The Fold", 2, 5, 27L),
            ContinueSeed("mockup-cw-atlas-of-ash", "Atlas of Ash", hannahArtworkUrl,
                "A mapmaker finds a route through a vanished country.", "atlas-of-ash-s1-e9",
                "A Map of Stars", 1, 9, 14L),
        )
        val allContinueTitles = (seededTitles.map { it.title } + continueOnlyTitles)
            .associateBy { it.stableId }
        for (seed in boardContinueSeeds.asReversed()) {
            val title = allContinueTitles[seed.stableId]
                ?: throw AssertionError("Continue Watching fixture metadata is missing for ${seed.title}")
            val durationMs = 60L * 60_000L
            val progressMs = durationMs - seed.minutesLeft * 60_000L
            assertTrue("${seed.title} records its mockup episode progress",
                library.recordPlaybackProgress(profileId, title, progressMs, durationMs,
                    seed.episodeId, seed.episodeTitle, seed.season, seed.episodeNumber))
            SystemClock.sleep(5L)
        }
        assertEquals("Continue Watching ordering matches the locked Home board",
            boardContinueSeeds.map { it.stableId },
            library.getContinueWatching(profileId, 8)
                .filter { it.stableId in FIXTURE_CONTINUE_TITLE_IDS }
                .map { it.stableId })
        assertTrue("Continue Watching-only fixture titles stay out of My Lists",
            library.getSavedTitles(profileId).none { it.stableId in continueOnlyStableIds })

        val collectionEntries = listOf(
            "Weekend watch" to listOf(
                "mockup-fixture-quiet-between", "mockup-fixture-northbound",
                "mockup-fixture-silent-meridian", "mockup-fixture-wild-current",
            ),
            "Studio Ghibli" to listOf(
                "mockup-fixture-emberline", "mockup-fixture-paper-moons",
                "mockup-fixture-wild-current", "mockup-fixture-blue-hours",
            ),
            "Family picks" to listOf(
                "mockup-fixture-quiet-between", "mockup-fixture-glass-harbor",
                "mockup-fixture-long-winter", "mockup-fixture-far-mountain",
            ),
        )
        val collections = collectionEntries.map { (name, stableIds) ->
            val collection = library.createCollection(profileId, name)
            stableIds.forEach { stableId ->
                assertTrue("fixture membership for $name/$stableId is inserted",
                    library.addToCollection(profileId, collection.id, stableId))
            }
            collection
        }
        val actual = library.getSavedTitles(profileId).filter { title ->
            seededTitles.any { it.title.stableId == title.stableId }
        }
        assertEquals("the deterministic fixture seeds exactly ten personal titles", 10, actual.size)
        val fullProfileTitles = library.getSavedTitles(profileId)
        assertEquals("the full-profile My Lists count remains exactly ten saved titles", 10,
            fullProfileTitles.size)
        assertEquals("the full-profile My Lists count remains exactly three collections", 3,
            library.getCollections(profileId).size)
        assertEquals("the full-profile Favorites count matches orbit-library-v3.html", 4,
            library.getFavorites(profileId).size)
        val expectedFullProfileStatusCounts = linkedMapOf(
            LocalLibraryStore.STATUS_WATCHING to 2,
            LocalLibraryStore.STATUS_PLAN_TO_WATCH to 3,
            LocalLibraryStore.STATUS_ON_HOLD to 1,
            LocalLibraryStore.STATUS_DROPPED to 1,
            LocalLibraryStore.STATUS_COMPLETED to 3,
        )
        expectedFullProfileStatusCounts.forEach { (status, expectedCount) ->
            assertEquals("the full-profile $status count matches orbit-library-v3.html", expectedCount,
                library.getTitlesForStatus(profileId, status).size)
        }
        assertEquals("fixture favorites match the approved sample and remain scoped to this profile", 4,
            actual.count { it.favorite })
        assertEquals("fixture rows cover all five watch statuses", setOf(
            LocalLibraryStore.STATUS_WATCHING,
            LocalLibraryStore.STATUS_PLAN_TO_WATCH,
            LocalLibraryStore.STATUS_ON_HOLD,
            LocalLibraryStore.STATUS_DROPPED,
            LocalLibraryStore.STATUS_COMPLETED,
        ), actual.map { it.watchStatus }.toSet())
        assertEquals("fixture collections remain scoped to this profile", 3, collections.size)
        return collections
    }

    private fun createScreenshotDirectory(): File {
        val external = target.getExternalFilesDir(null) ?: target.filesDir
        val directory = File(external, "$FIXTURE_DIRECTORY_NAME-${UUID.randomUUID().toString().take(8)}")
        assertTrue("screenshot output directory must be created", directory.mkdirs())
        return directory.canonicalFile
    }

    private fun captureScreenshot(name: String, expectedVisibleText: List<String> = emptyList()) {
        instrumentation.waitForIdleSync()
        awaitNextDraw(shell?.window?.decorView ?: error("OrbitShellActivity is unavailable"))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(50L)
        val expectedFrameViews = onMain {
            val activity = shell ?: error("OrbitShellActivity is unavailable")
            expectedVisibleText.map { text ->
                val view = findText(activity, text) ?: findContentDescription(activity, text)
                val rect = Rect()
                val isVisible = view?.getGlobalVisibleRect(rect) == true
                CapturedFrameText(text, isVisible, rect)
            }
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("UI Automation must capture $name", bitmap)
        val frame = bitmap!!
        try {
            val file = File(fixtureOutputDirectory ?: error("screenshot directory missing"), name)
            FileOutputStream(file).use { output ->
                assertTrue("screenshot $name must be encoded", frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.flush()
            }
            Log.i(TAG, "SCREENSHOT ${file.absolutePath} size=${frame.width}x${frame.height} " +
                "visibleText=${expectedFrameViews.map { "${it.text}:${it.visible}:${it.bounds.flattenToString()}" }} " +
                "sha256=${sha256(file.readBytes())}")
            assertEquals("every expected visible text or accessibility label must appear in the captured frame",
                expectedVisibleText, expectedFrameViews.filter { it.visible }.map { it.text })
            assertTrue("every expected text view must fit inside the captured frame",
                expectedFrameViews.all {
                    it.visible && it.bounds.left >= 0 && it.bounds.top >= 0
                        && it.bounds.right <= frame.width && it.bounds.bottom <= frame.height
                        && it.bounds.width() > 0 && it.bounds.height() > 0
                })
        } finally {
            frame.recycle()
        }
    }

    private fun awaitNextDraw(view: View) {
        val drawn = CountDownLatch(1)
        val observerRef = AtomicReference<ViewTreeObserver>()
        val listenerRef = AtomicReference<ViewTreeObserver.OnDrawListener>()
        onMain {
            val observer = view.viewTreeObserver
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    drawn.countDown()
                }
            }
            observerRef.set(observer)
            listenerRef.set(listener)
            observer.addOnDrawListener(listener)
            view.invalidate()
        }
        assertTrue("the Orbit screen must draw before its screenshot is captured",
            drawn.await(5, TimeUnit.SECONDS))
        onMain {
            val observer = observerRef.get()
            if (observer.isAlive) observer.removeOnDrawListener(listenerRef.get())
        }
    }

    private fun snapshotPreferences(name: String, preferences: SharedPreferences): PreferenceSnapshot {
        val file = File(target.applicationInfo.dataDir, "shared_prefs/$name.xml")
        val exists = file.isFile
        val bytes = if (exists) file.readBytes() else null
        val values = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        return PreferenceSnapshot(name, values, exists, bytes, sha256Preferences(values))
    }

    private fun restorePreferences(snapshot: PreferenceSnapshot, preferences: SharedPreferences) {
        if (snapshot.name == PROVIDER_PREFERENCES) logProviderPreferenceState("before-restore")
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
        assertTrue("${snapshot.name} preferences must restore synchronously", editor.commit())
        val file = File(target.applicationInfo.dataDir, "shared_prefs/${snapshot.name}.xml")
        if (snapshot.fileExisted) {
            assertNotNull("original preference file bytes must be captured", snapshot.fileBytes)
            FileOutputStream(file, false).use { output ->
                output.write(snapshot.fileBytes!!)
                output.flush()
                output.fd.sync()
            }
        } else {
            assertTrue("a previously absent preference file must be removed",
                target.deleteSharedPreferences(snapshot.name))
        }
        if (snapshot.name == PROVIDER_PREFERENCES) logProviderPreferenceState("after-restore")
        val restoredBytes = if (file.isFile) file.readBytes() else null
        assertEquals("${snapshot.name} preference file existence must be restored",
            snapshot.fileExisted, file.isFile)
        if (snapshot.fileExisted) {
            assertTrue("${snapshot.name} raw preference XML bytes must be restored",
                snapshot.fileBytes!!.contentEquals(restoredBytes!!))
        }
    }

    /** Restores only the two extension keys this synthetic fixture edits. */
    private fun restoreFixtureExtensionPreferences(
        snapshot: PreferenceSnapshot,
        preferences: SharedPreferences,
    ) {
        val fixtureKeys = listOf("installed_plugins_json", "profile_enabled_plugins_json")
        val editor = preferences.edit()
        for (key in fixtureKeys) {
            if (snapshot.values.containsKey(key)) {
                val original = snapshot.values[key]
                assertTrue("fixture-owned extension key $key must have been a string", original is String)
                editor.putString(key, original as String)
            } else {
                editor.remove(key)
            }
        }
        assertTrue("only the synthetic fixture's extension keys must be restored", editor.commit())

        val file = File(target.applicationInfo.dataDir, "shared_prefs/${snapshot.name}.xml")
        if (!snapshot.fileExisted && preferences.all.isEmpty()) {
            // The file did not previously exist and now has no records at all. Delete only that
            // empty fixture-created file; preserve any unrelated key that appeared meanwhile.
            assertTrue("the empty synthetic extension preference file must be removed",
                target.deleteSharedPreferences(snapshot.name))
        }
    }

    private fun assertFixtureExtensionPreferencesRestored(
        snapshot: PreferenceSnapshot,
        preferences: SharedPreferences,
    ) {
        val fixtureKeys = setOf("installed_plugins_json", "profile_enabled_plugins_json")
        val actual = preferences.all
        for ((key, value) in snapshot.values) {
            if (key !in fixtureKeys) {
                assertEquals("unrelated extension preference $key must be preserved", value, actual[key])
            }
        }
        for (key in fixtureKeys) {
            if (snapshot.values.containsKey(key)) {
                assertEquals("fixture-owned extension preference $key must be restored",
                    snapshot.values[key], actual[key])
            } else {
                assertFalse("new fixture-owned extension preference $key must be removed",
                    actual.containsKey(key))
            }
        }
    }

    private fun assertPreferencesRestored(
        snapshot: PreferenceSnapshot,
        preferences: SharedPreferences,
        message: String,
    ) {
        assertEquals(message, snapshot.digest, sha256Preferences(preferences.all))
        val file = File(target.applicationInfo.dataDir, "shared_prefs/${snapshot.name}.xml")
        assertEquals("${snapshot.name} preference file existence must match the captured baseline",
            snapshot.fileExisted, file.isFile)
        if (snapshot.fileExisted) {
            assertTrue("${snapshot.name} raw preference XML must match the captured baseline",
                snapshot.fileBytes!!.contentEquals(file.readBytes()))
        }
    }

    private fun logProviderPreferenceState(phase: String) {
        val file = File(target.applicationInfo.dataDir,
            "shared_prefs/$PROVIDER_PREFERENCES.xml")
        val xml = if (file.isFile) file.readText(Charsets.UTF_8) else "<absent>"
        val values = TreeMap<String, String>()
        for ((key, value) in providerPreferences.all) {
            values[key] = when (value) {
                is Set<*> -> "set:" + value.filterIsInstance<String>().sorted().joinToString("\u001f")
                null -> "<null>"
                else -> "${value.javaClass.name}:$value"
            }
        }
        Log.i(TAG, "PROVIDER_PREF_STATE phase=$phase exists=${file.isFile} " +
            "xmlSha256=${if (file.isFile) sha256(xml.toByteArray(Charsets.UTF_8)) else "<absent>"} " +
            "mapSha256=${sha256Preferences(providerPreferences.all)} xml=$xml map=$values")
    }

    private fun snapshotLibraryFiles(): FileSnapshot {
        val directory = File(target.applicationInfo.dataDir, "databases")
        val files = listOf("orbit_local_library.db", "orbit_local_library.db-wal", "orbit_local_library.db-shm")
            .associateWith { name -> File(directory, name) }
        val hashes = TreeMap<String, String>()
        for ((name, file) in files) hashes[name] = if (file.isFile) sha256(file.readBytes()) else "<absent>"
        return FileSnapshot(sha256Text(hashes.entries.joinToString("\n") { "${it.key}=${it.value}" }), hashes)
    }

    private fun snapshotLibraryLogicalState(): LibraryLogicalSnapshot {
        val database = application.localLibraryStore.readableDatabase
        val schema = StringBuilder()
        database.rawQuery(
            "SELECT type, name, tbl_name, COALESCE(sql, '') FROM sqlite_master " +
                "WHERE type IN ('table','index') ORDER BY type, name", null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                schema.append(cursor.getString(0)).append('\u001f')
                    .append(cursor.getString(1)).append('\u001f')
                    .append(cursor.getString(2)).append('\u001f')
                    .append(cursor.getString(3)).append('\n')
            }
        }

        val tableNames = ArrayList<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { cursor -> while (cursor.moveToNext()) tableNames.add(cursor.getString(0)) }
        val logicalRows = StringBuilder()
        var rowCount = 0
        for (tableName in tableNames) {
            val quotedName = "\"${tableName.replace("\"", "\"\"")}\""
            val tableRows = ArrayList<String>()
            database.rawQuery("SELECT * FROM $quotedName ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) {
                    rowCount++
                    val row = StringBuilder().append(tableName).append('\u001f')
                    for (column in 0 until cursor.columnCount) {
                        row.append(cursorValue(cursor, column)).append('\u001f')
                    }
                    tableRows.add(row.append('\n').toString())
                }
            }
            tableRows.sort()
            for (row in tableRows) logicalRows.append(row)
        }
        val summary = "tables=${tableNames.joinToString(",")};rows=$rowCount"
        return LibraryLogicalSnapshot(sha256Text("$schema\n$logicalRows"), summary)
    }

    private fun snapshotLibraryRows(tableName: String): Map<PlaybackProgressKey, ContentValues> {
        val rows = LinkedHashMap<PlaybackProgressKey, ContentValues>()
        val database = application.localLibraryStore.readableDatabase
        database.rawQuery("SELECT * FROM \"$tableName\"", null).use { cursor ->
            val profileIndex = cursor.getColumnIndexOrThrow("profile_id")
            val stableIdIndex = cursor.getColumnIndexOrThrow("stable_id")
            while (cursor.moveToNext()) {
                val values = ContentValues()
                for (column in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(column)
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(column))
                        Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(column))
                        else -> values.put(name, cursor.getString(column))
                    }
                }
                rows[PlaybackProgressKey(cursor.getString(profileIndex), cursor.getString(stableIdIndex))] = values
            }
        }
        return rows
    }

    private fun snapshotLibraryTable(tableName: String): List<ContentValues> {
        val rows = ArrayList<ContentValues>()
        val database = application.localLibraryStore.readableDatabase
        database.rawQuery("SELECT * FROM \"${tableName.replace("\"", "\"\"")}\" ORDER BY rowid", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val values = ContentValues()
                    for (column in 0 until cursor.columnCount) {
                        val name = cursor.getColumnName(column)
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                            Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(column))
                            Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(column))
                            Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(column))
                            else -> values.put(name, cursor.getString(column))
                        }
                    }
                    rows.add(values)
                }
            }
        return rows
    }

    private fun restoreTrackedLibraryRow(
        database: SQLiteDatabase,
        tableName: String,
        key: PlaybackProgressKey,
        original: ContentValues?,
        restoredMessage: String,
    ) {
        val where = "profile_id=? AND stable_id=?"
        val whereArgs = arrayOf(key.profileId, key.stableId)
        if (original == null) {
            val removed = database.delete(tableName, where, whereArgs)
            assertTrue("the fixture must not create duplicate $tableName rows", removed in 0..1)
            return
        }

        val mutableValues = ContentValues(original)
        mutableValues.remove("profile_id")
        mutableValues.remove("stable_id")
        val updated = database.update(tableName, mutableValues, where, whereArgs)
        if (updated == 0) {
            val inserted = database.insertWithOnConflict(tableName, null,
                ContentValues(original), SQLiteDatabase.CONFLICT_REPLACE)
            assertTrue(restoredMessage, inserted != -1L)
        } else {
            assertEquals(restoredMessage, 1, updated)
        }
    }

    private fun restoreLibraryTableSnapshot(
        database: SQLiteDatabase,
        tableName: String,
        current: Map<PlaybackProgressKey, ContentValues>,
        baseline: Map<PlaybackProgressKey, ContentValues>,
        restoredMessage: String,
    ) {
        for (key in current.keys) {
            if (!baseline.containsKey(key)) {
                val removed = database.delete(tableName, "profile_id=? AND stable_id=?",
                    arrayOf(key.profileId, key.stableId))
                assertTrue("the fixture must not create duplicate $tableName rows", removed in 0..1)
            }
        }
        for ((key, original) in baseline) {
            restoreTrackedLibraryRow(database, tableName, key, original, restoredMessage)
        }
    }

    private fun restoreLibraryTableSnapshot(
        database: SQLiteDatabase,
        tableName: String,
        snapshot: List<ContentValues>,
    ) {
        database.delete(tableName, null, null)
        for (row in snapshot) database.insertOrThrow(tableName, null, ContentValues(row))
    }

    @Synchronized
    private fun restoreLibrarySnapshotsAfterPlaybackStops() {
        if ((!playbackLibraryMutationAllowed && !populatedLibraryFixtureSeeded)
            || !::application.isInitialized) return
        val database = application.localLibraryStore.writableDatabase
        val currentPlaybackRows = snapshotLibraryRows("playback_progress")
        val currentSavedTitleRows = snapshotLibraryRows("saved_titles")
        database.beginTransaction()
        try {
            database.delete("collection_memberships", null, null)
            database.delete("collections", null, null)
            restoreLibraryTableSnapshot(database, "playback_progress", currentPlaybackRows,
                playbackProgressSnapshot, "pre-existing playback rows must be restored")
            restoreLibraryTableSnapshot(database, "saved_titles", currentSavedTitleRows,
                savedTitleSnapshot, "pre-existing saved-title rows must be restored")
            restoreLibraryTableSnapshot(database, "collections", collectionSnapshot)
            restoreLibraryTableSnapshot(database, "collection_memberships", collectionMembershipSnapshot)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        playbackLibraryMutationAllowed = false
        populatedLibraryFixtureSeeded = false
    }

    private fun cursorValue(cursor: Cursor, column: Int): String = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(column)}"
        Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(column)}"
        Cursor.FIELD_TYPE_BLOB -> "blob:" + android.util.Base64.encodeToString(
            cursor.getBlob(column), android.util.Base64.NO_WRAP)
        else -> "text:${cursor.getString(column)}"
    }

    private fun stateSummary(): String = JSONObject()
        .put("profilePrefs", if (::profileSnapshot.isInitialized) profileSnapshot.digest else "unavailable")
        .put("providerPrefs", if (::providerSnapshot.isInitialized) providerSnapshot.digest else "unavailable")
        .put("extensionPrefs", if (::extensionSnapshot.isInitialized) extensionSnapshot.digest else "unavailable")
        .put("libraryFiles", if (::librarySnapshot.isInitialized) librarySnapshot.files else "unavailable")
        .put("activeProfile", profileStore.activeProfile?.id ?: "")
        .put("runtimeProfile", if (::runtime.isInitialized) runtime.activeProfileId ?: "" else "unavailable")
        .toString()

    private fun assertSameIdentityOrder(message: String, expected: List<MainAPI>) {
        val current = APIHolder.INSTANCE.allProviders
        assertEquals(message, expected.size, current.size)
        for (index in expected.indices) assertTrue(message, expected[index] === current[index])
    }

    private fun closeActivityProviderBatches(activity: OrbitShellActivity) {
        for (field in OrbitShellActivity::class.java.declaredFields) {
            if (!ProviderBatch::class.java.isAssignableFrom(field.type)) continue
            field.isAccessible = true
            (field.get(activity) as? ProviderBatch<*>)?.close()
        }
    }

    private fun closeProviderDetails(activity: OrbitShellActivity) {
        val method = OrbitShellActivity::class.java.getDeclaredMethod("closeProviderTitleDetails", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(activity, false)
        for (field in OrbitShellActivity::class.java.declaredFields) {
            if (ProviderBatch::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                (field.get(activity) as? ProviderBatch<*>)?.close()
            }
        }
    }

    private fun shellState(activity: OrbitShellActivity?): ShellState {
        val owner = activity
            ?: throw AssertionError("the OrbitShellActivity instance is unavailable")
        return readField(owner, "shellState") as? ShellState
            ?: throw AssertionError("the OrbitShellActivity shellState is unavailable")
    }

    private fun invokeRender(activity: OrbitShellActivity) {
        val method = OrbitShellActivity::class.java.getDeclaredMethod("render")
        method.isAccessible = true
        method.invoke(activity)
    }

    private fun invokeIntMethod(instance: Any, name: String): Int {
        val method = OrbitShellActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        return method.invoke(instance) as Int
    }

    private fun readField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(instance)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun seedLoadedPluginHandle(
        plugin: BasePlugin,
        markerFile: File,
        loadTransaction: OrbitCs3HostServices.LoadTransaction,
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
        )
        constructor.isAccessible = true
        // The in-memory fixture owns no DEX resources, so its LoadedPlugin keeps a null loader.
        val loaded = constructor.newInstance(plugin, null, markerFile, loadTransaction, null, pluginId)
        val loadedPluginsField = CS3PluginRuntime::class.java.getDeclaredField("loadedPlugins")
        loadedPluginsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val loadedPlugins = loadedPluginsField.get(runtime) as MutableMap<String, Any>
        loadedPlugins[pluginId] = loaded
    }

    private fun assertNoLibraryRowsForPlayback(profileId: String, stableId: String) {
        val database = application.localLibraryStore.readableDatabase
        for (table in listOf("saved_titles", "playback_progress")) {
            database.rawQuery("SELECT 1 FROM $table WHERE profile_id=? AND stable_id=? LIMIT 1",
                arrayOf(profileId, stableId)).use { cursor ->
                assertFalse("the unique playback fixture must not collide with prior $table state",
                    cursor.moveToFirst())
            }
        }
    }

    private fun setField(activity: OrbitShellActivity, name: String, value: Any?) {
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                field.set(activity, value)
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

    private fun findContentDescription(activity: OrbitShellActivity, description: String): View? =
        findView(activity.window.decorView) { it.contentDescription?.toString() == description }

    private fun findAncestorRecyclerView(view: View): RecyclerView? {
        var parent = view.parent
        while (parent != null && parent !is RecyclerView) parent = parent.parent
        return parent as? RecyclerView
    }

    private fun isVisibleLaidOut(view: View?): Boolean = view != null
        && view.isShown && view.isAttachedToWindow && view.isLaidOut
        && view.width > 0 && view.height > 0

    private fun findVisibleTitleSearchSuccess(activity: OrbitShellActivity): Boolean {
        val outcomes = readField(activity, "titleSearchOutcomes") as? List<*>
            ?: return false
        return outcomes.any {
            it is ProviderOutcome<*> && it.status == ProviderOutcome.Status.SUCCESS
                && it.value != null
        }
    }

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findView(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun awaitProviderSubtitleTrack(activity: OrbitPlayerActivity, subtitleId: String) {
        await("Media3 exposes the provider subtitle track before selection") {
            val method = OrbitPlayerActivity::class.java.getDeclaredMethod(
                "getTrackChoices", Int::class.javaPrimitiveType!!,
            ).apply { isAccessible = true }
            val choices = method.invoke(activity, C.TRACK_TYPE_TEXT) as? List<*> ?: emptyList<Any>()
            choices.any { choice ->
                choice != null && readField(choice, "choiceId") == "external:$subtitleId"
            }
        }
    }

    private fun chooseProviderSubtitleThroughPlayerUi(
        activity: OrbitPlayerActivity,
        subtitleId: String,
        expectedSourceName: String,
    ) {
        val openDialog = OrbitPlayerActivity::class.java.getDeclaredMethod("openSourcesDialog")
            .apply { isAccessible = true }
        onMain { openDialog.invoke(activity) }
        val dialog = readField(activity, "sourcesDialog") as? android.app.Dialog
            ?: throw AssertionError("Orbit Player must open its Sources & subtitles dialog")
        val dialogRoot = onMain { dialog.window?.decorView }
            ?: throw AssertionError("the player subtitle dialog must have a rendered window")
        val sourceDialogTexts = visibleDialogTexts("Sources & subtitles")
            ?: throw AssertionError("the player must show its Sources & subtitles dialog")
        assertTrue("the player source menu preserves the provider-reported source name",
            sourceDialogTexts.any { it.contains(expectedSourceName) })
        val option = onMain {
            findView(dialogRoot) { view ->
                view is Button && view.text.toString().contains("Provider-reported · text/vtt")
                    && view.text.toString().endsWith("· en")
            } as? Button
        } ?: throw AssertionError("the player dialog must render the provider-reported English VTT row")
        onMain { assertTrue("the actual subtitle row accepts the selection", option.performClick()) }
        val apply = onMain {
            findView(dialogRoot) { view -> view is Button && view.text.toString() == "Apply & play" }
                as? Button
        } ?: throw AssertionError("the player subtitle dialog must render Apply & play")
        onMain { assertTrue("the actual player dialog applies the subtitle choice", apply.performClick()) }
        assertEquals("the dialog applies the requested provider subtitle ID",
            "external:$subtitleId", readField(activity, "selectedSubtitleId"))
    }

    private fun runRealFcastProviderHandoff(
        activity: OrbitShellActivity,
        profileId: String,
        providerId: String,
        selectedSourceUrl: String,
    ) {
        assertTrue("the real FCast branch selects its distinct local media fixture",
            selectedSourceUrl.endsWith(FCAST_SOURCE_PATH)
                && selectedSourceUrl.startsWith("http://127.0.0.1:"))
        await("Orbit's real FCast discovery finds the receiver on port ${FcastManager.TCP_PORT}",
            UI_TIMEOUT_MS * 2) {
            FcastManager.currentDevices.any { it.port == FcastManager.TCP_PORT }
        }
        val receiver = FcastManager.currentDevices.singleOrNull {
            it.port == FcastManager.TCP_PORT
        } ?: throw AssertionError("FCast discovery must expose one receiver on port ${FcastManager.TCP_PORT}")
        assertTrue("the discovered receiver has a numeric local address", !receiver.host.isNullOrBlank())

        val actionContext = readField(activity, "providerActionContext") as? Cs3ActionContext
            ?: throw AssertionError("Orbit must retain the real provider action context")
        assertEquals("the real provider action context remains scoped to the fixture profile",
            profileId, actionContext.profileId)
        val fcastAction = VideoClickActionHolder.allVideoClickActions
            .filterIsInstance<FcastAction>().singleOrNull()
            ?: throw AssertionError("Orbit must expose exactly one built-in FcastAction")
        assertTrue("the discovered receiver makes the real FCast action available",
            fcastAction.shouldShowSafe(activity, actionContext.video))

        val openWith = OrbitShellActivity::class.java
            .getDeclaredMethod("showProviderVideoActionChooser")
            .apply { isAccessible = true }
        assertTrue("Orbit opens its real provider action chooser",
            onMain { openWith.invoke(activity) as Boolean })
        await("the real Open with chooser exposes Fcast to device") {
            visibleDialogTexts("Open with")?.contains(FCAST_ACTION_LABEL) == true
        }
        captureScreenshot("fcast-open-with.png")
        clickDialogText(FCAST_ACTION_LABEL)

        await("FcastAction exposes the source selected by the provider flow") {
            visibleDialogTexts("Choose source")?.contains(FCAST_SOURCE_OPTION) == true
        }
        clickDialogText(FCAST_SOURCE_OPTION)
        await("the actual FcastAction device chooser shows the discovered receiver") {
            visibleDialogTexts("Choose cast device")?.contains(receiver.name) == true
        }
        val deviceDialogTexts = visibleDialogTexts("Choose cast device")
            ?: throw AssertionError("FcastAction must show its device chooser")
        assertTrue("the chooser names the receiver resolved by Orbit's FCast discovery",
            receiver.name in deviceDialogTexts)
        val pendingAction = readField(activity, "activeVideoActionLaunch")
            ?: throw AssertionError("the real FcastAction dispatch must retain a pending video action")
        val pendingActionRef = readField(pendingAction, "action") as? java.lang.ref.WeakReference<*>
        assertTrue("the pending provider action is Orbit's built-in FcastAction",
            pendingActionRef?.get() === fcastAction)
        assertEquals("the pending FCast handoff retains the fixture profile", profileId,
            readField(pendingAction, "profileId"))
        assertEquals("the pending FCast handoff retains the fixture provider", providerId,
            readField(pendingAction, "providerId"))
        captureScreenshot("fcast-device-chooser.png")
        clickDialogText(receiver.name)

        val served = mediaServer!!.awaitRequest(FCAST_SOURCE_PATH, UI_TIMEOUT_MS * 2, "GET")
            ?: throw AssertionError("the discovered FCast receiver must fetch the distinct loopback source")
        assertEquals("the receiver requests only the dedicated FCast fixture path",
            FCAST_SOURCE_PATH, served.requestTarget)
        assertTrue("the dedicated FCast source is served as successful bounded MP4 data",
            served.status == 200 || served.status == 206)
        assertEquals("the FCast URL sent by Orbit is the exact fetched local source",
            selectedSourceUrl,
            "http://${served.host}${served.requestTarget}")
        assertNull("the bounded local fixture server remains healthy", mediaServer!!.failure())
        await("Orbit releases its pending action after the FCast handoff completes") {
            readField(activity, "activeVideoActionLaunch") == null
        }
        Log.i(TAG, "FCAST_FLOW_PASS profile=$profileId provider=$providerId " +
            "receiver=${receiver.name} host=${receiver.host} port=${receiver.port} " +
            "source=$selectedSourceUrl request=${served.method} ${served.requestTarget} " +
            "status=${served.status} bytes=${served.bytes}")
    }

    private fun visibleDialogTexts(title: String): List<String>? {
        for (root in accessibilityRoots()) {
            val texts = ArrayList<String>()
            collectAccessibilityText(root, texts)
            if (texts.contains(title)) return texts
        }
        return null
    }

    private fun accessibilityRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { instrumentation.uiAutomation.rootInActiveWindow?.let(roots::add) }
        catch (_: RuntimeException) { }
        try {
            for (window in instrumentation.uiAutomation.windows) {
                val root = window.root ?: continue
                if (roots.none { it.windowId == root.windowId }) roots.add(root)
            }
        } catch (_: RuntimeException) { }
        return roots
    }

    private fun collectAccessibilityText(node: AccessibilityNodeInfo, output: MutableList<String>) {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) output.add(text)
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (description.isNotEmpty() && description != text) output.add(description)
        for (index in 0 until node.childCount) node.getChild(index)?.let {
            collectAccessibilityText(it, output)
        }
    }

    private fun clickDialogText(text: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            for (root in accessibilityRoots()) {
                val dialogTexts = ArrayList<String>()
                collectAccessibilityText(root, dialogTexts)
                if (text !in dialogTexts) continue
                val targetNode = findAccessibilityTextNode(root, text) ?: continue
                var clickable: AccessibilityNodeInfo? = targetNode
                while (clickable != null) {
                    if (clickable.isClickable
                        && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    clickable = clickable.parent
                }
                if (injectTap(targetNode)) return
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50L)
        }
        throw AssertionError("could not dispatch accessibility click to dialog row '$text'")
    }

    private fun findAccessibilityTextNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString()?.trim() == text) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findAccessibilityTextNode(child, text)?.let { return it }
        }
        return null
    }

    private fun injectTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 80L, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
                && instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun bringIntoView(view: View) {
        onMain {
            scrollAncestorToView(view, ScrollView::class.java)
            scrollAncestorToView(view, HorizontalScrollView::class.java)
        }
        instrumentation.waitForIdleSync()
    }

    private fun scrollToTop(view: View) {
        onMain { scrollAncestorToView(view, ScrollView::class.java, toStart = true) }
        instrumentation.waitForIdleSync()
    }

    private fun <T : ViewGroup> scrollAncestorToView(
        view: View,
        parentType: Class<T>,
        toStart: Boolean = false,
    ) {
        var parent = view.parent
        while (parent != null && !parentType.isInstance(parent)) parent = parent.parent
        val resolvedParent = parent ?: return
        if (!parentType.isInstance(resolvedParent)) return
        val scrollParent = parentType.cast(resolvedParent) ?: return
        val rect = Rect(0, 0, view.width, view.height)
        scrollParent.offsetDescendantRectToMyCoords(view, rect)
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        if (scrollParent is ScrollView) {
            scrollParent.scrollTo(scrollParent.scrollX,
                if (toStart) 0 else rect.top.coerceAtLeast(0))
        } else if (scrollParent is HorizontalScrollView) {
            scrollParent.scrollTo(if (toStart) 0 else rect.left.coerceAtLeast(0),
                scrollParent.scrollY)
        }
    }

    private fun locationX(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[0]
    }

    private fun locationY(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1]
    }

    private fun await(
        description: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
        failureDetails: (() -> String)? = null,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(predicate)) return
            SystemClock.sleep(40L)
        }
        val details = failureDetails?.invoke()?.let { "; $it" }.orEmpty()
        assertTrue("timed out waiting for $description$details", onMain(predicate))
    }

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun sha256Preferences(values: Map<String, *>): String {
        val rendered = TreeMap<String, String>()
        for ((key, value) in values) {
            rendered[key] = when (value) {
                is Set<*> -> "set:" + value.filterIsInstance<String>().sorted().joinToString("\u001f")
                null -> "<null>"
                else -> "${value.javaClass.name}:$value"
            }
        }
        return sha256Text(rendered.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun sha256Text(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class PreferenceSnapshot(
        val name: String,
        val values: Map<String, Any?>,
        val fileExisted: Boolean,
        val fileBytes: ByteArray?,
        val digest: String,
    )

    private data class CapturedFrameText(
        val text: String,
        val visible: Boolean,
        val bounds: Rect,
    )

    private data class FileSnapshot(val digest: String, val files: Map<String, String>)

    private data class PlaybackProgressKey(val profileId: String, val stableId: String)
    private data class LibraryLogicalSnapshot(val digest: String, val summary: String)

    private class FixtureSeriesApi(
        private val firstSourceUrl: String,
        private val selectedSourceUrl: String,
        private val queriedSourceUrl: String,
        private val episodeData: String = FIXTURE_EPISODE_DATA,
        private val pauseAfterFirstLinkCallback: Boolean = false,
        private val lateSubtitleUrl: String = "",
        private val hlsFailureSourceUrl: String = "",
        private val secondHlsFailureSourceUrl: String = "",
    ) : MainAPI() {
        val linkRequestData = CopyOnWriteArrayList<String>()
        val castingRequests = CopyOnWriteArrayList<Boolean>()
        val emittedLinkUrls = CopyOnWriteArrayList<String>()
        val completedLinkCallbacks = CopyOnWriteArrayList<Int>()
        val detailRequests = CopyOnWriteArrayList<String>()
        val firstLinkCallbackDelivered = CountDownLatch(1)
        val allowLinkResolutionReturn = CountDownLatch(1)
        val lateSubtitleCallbackDelivered = CountDownLatch(1)
        val allowNextLinkAfterLateSubtitle = CountDownLatch(1)
        @Volatile var allFailingNextCall = false
        @Volatile var linkCalls = 0
            private set

        override fun getName(): String = FIXTURE_PROVIDER_NAME
        override fun getMainUrl(): String = FIXTURE_PROVIDER_URL
        override fun getSupportedTypes(): Set<TvType> = setOf(TvType.TvSeries)
        override fun getHasMainPage(): Boolean = false

        override suspend fun search(query: String, page: Int): SearchResponseList {
            val matches = if (query.equals(FIXTURE_SERIES_SEARCH_QUERY, ignoreCase = true)
                && page == 1) listOf(TvSeriesSearchResponse(
                    FIXTURE_SERIES_TITLE, FIXTURE_SERIES_URL, FIXTURE_PROVIDER_NAME,
                    TvType.TvSeries, null, 2026, 1, null, null, emptyMap(),
                )) else emptyList()
            return SearchResponseList(matches, false)
        }

        override suspend fun load(url: String): LoadResponse {
            detailRequests.add(url)
            check(url == FIXTURE_SERIES_URL) { "fixture received an unknown series Details URL" }
            return TvSeriesLoadResponse(FIXTURE_SERIES_TITLE, FIXTURE_SERIES_URL,
                FIXTURE_PROVIDER_NAME, TvType.TvSeries, listOf(
                    Episode(episodeData, FIXTURE_EPISODE_TITLE, 1, 1,
                        null, null as Int?, "Episode tap fixture", null),
                ))
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            linkCalls++
            val callNumber = linkCalls
            linkRequestData.add(data)
            castingRequests.add(isCasting)
            check(data == episodeData) {
                "fixture loadLinks must receive the selected episode's opaque provider data"
            }
            delay(EPISODE_LINK_CALLBACK_DELAY_MS)
            if (allFailingNextCall) {
                allFailingNextCall = false
                for ((index, failingUrl) in listOf(
                    hlsFailureSourceUrl, secondHlsFailureSourceUrl,
                ).withIndex()) {
                    val failing = ExtractorLink(
                        "Loopback fixture", "Fixture HLS ${1440 - index * 360}p all-failing",
                        failingUrl, "", 1440 - index * 360, true, emptyMap(), null,
                    )
                    emittedLinkUrls.add(failing.url)
                    callback(failing)
                }
                completedLinkCallbacks.add(callNumber)
                return true
            }
            val first = ExtractorLink(
                "Loopback fixture", if (hlsFailureSourceUrl.isNotBlank()) "Fixture HLS 1440p" else "Fixture MP4 360p",
                if (hlsFailureSourceUrl.isNotBlank()) hlsFailureSourceUrl else firstSourceUrl, "",
                if (hlsFailureSourceUrl.isNotBlank()) 1440 else 360,
                hlsFailureSourceUrl.isNotBlank(), emptyMap(), null,
            )
            val selected = ExtractorLink(
                "Loopback fixture", "Fixture MP4 1080p", selectedSourceUrl, "", 1080,
                false, emptyMap(), null,
            )
            val queried = ExtractorLink(
                "Loopback fixture", "Fixture MP4 2160p Query", queriedSourceUrl, "", 2160,
                false, emptyMap(), null,
            )
            for ((index, link) in listOf(first, selected, queried).withIndex()) {
                if (index == 1 && pauseAfterFirstLinkCallback
                    && lateSubtitleUrl.isNotBlank()) {
                    subtitleCallback(SubtitleFile("en", lateSubtitleUrl).apply {
                        headers = emptyMap()
                    })
                    lateSubtitleCallbackDelivered.countDown()
                    check(allowNextLinkAfterLateSubtitle.await(
                        PROGRESSIVE_PROVIDER_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        "the progressive player fixture was not released after its late subtitle callback"
                    }
                }
                emittedLinkUrls.add(link.url)
                callback(link)
                if (index == 0) {
                    firstLinkCallbackDelivered.countDown()
                    if (pauseAfterFirstLinkCallback) {
                        check(allowLinkResolutionReturn.await(
                            PROGRESSIVE_PROVIDER_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            "the progressive player fixture was not released after its first callback"
                        }
                    }
                }
            }
            completedLinkCallbacks.add(callNumber)
            return true
        }
    }

    private class FixtureLiveApi : MainAPI() {
        val searchRequests = CopyOnWriteArrayList<String>()
        val detailRequests = CopyOnWriteArrayList<String>()
        val linkRequests = CopyOnWriteArrayList<String>()

        override fun getName(): String = FIXTURE_PROVIDER_NAME
        override fun getMainUrl(): String = FIXTURE_PROVIDER_URL
        override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Live)

        override suspend fun search(query: String, page: Int): SearchResponseList {
            searchRequests.add("$query:$page")
            val results = if (query == FIXTURE_LIVE_SEARCH_QUERY && page == 1) {
                listOf<SearchResponse>(LiveSearchResponse(
                    FIXTURE_LIVE_TITLE, FIXTURE_LIVE_URL, getName(), TvType.Live,
                    null, null, null, null, null, null,
                ))
            } else emptyList()
            return SearchResponseList(results, false)
        }

        override suspend fun load(url: String): LoadResponse {
            check(url == FIXTURE_LIVE_URL) { "fixture received an unknown LIVE details URL" }
            detailRequests.add(url)
            val callNumber = detailRequests.size
            return LiveStreamLoadResponse(
                FIXTURE_LIVE_TITLE, url, getName(), "fixture-live-data:$callNumber",
                null, null, "Dynamic LIVE details load $callNumber", TvType.Live, null,
                emptyList(), null, emptyList<com.lagradost.cloudstream3.TrailerData>(), emptyList(), emptyList(), false,
                linkedMapOf(), null, null, null, null, url,
            )
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            linkRequests.add(data)
            check(data == "fixture-live-data:${detailRequests.size}") {
                "fixture LIVE link action must use the current session's details payload"
            }
            // No sources are emitted; the Activity can verify link checks without launching Player.
            return true
        }
    }

    private class FixtureMainApi(
        movieAUrl: String,
        private val firstSourceUrl: String,
        private val selectedSourceUrl: String,
        private val queriedSourceUrl: String,
        private val subtitleUrl: String,
        movieAArtworkUrl: String,
        movieBArtworkUrl: String,
        additionalMovies: List<FixtureMovie>,
        private val providerName: String = FIXTURE_PROVIDER_NAME,
        private val providerUrl: String = FIXTURE_PROVIDER_URL,
        movieATitle: String = MOVIE_A_TITLE,
        movieBTitle: String = MOVIE_B_TITLE,
        movieBUrl: String = MOVIE_B_URL,
        private val shelfATitle: String = SHELF_A_TITLE,
        private val shelfBTitle: String = SHELF_B_TITLE,
        private val homeRequestAName: String = "Fixture Movies A",
        private val homeRequestBName: String = "Fixture Movies B",
        private val pauseAfterFirstLinkCallback: Boolean = false,
    ) : MainAPI() {
        private val movies = listOf(
            FixtureMovie(movieATitle, movieAUrl, movieAArtworkUrl),
            FixtureMovie(movieBTitle, movieBUrl, movieBArtworkUrl),
        ) + additionalMovies
        val homeRequests = CopyOnWriteArrayList<String>()
        val searchRequests = CopyOnWriteArrayList<String>()
        val searchResultUrls = CopyOnWriteArrayList<String>()
        val detailRequests = CopyOnWriteArrayList<String>()
        val linkRequestData = CopyOnWriteArrayList<String>()
        val castingRequests = CopyOnWriteArrayList<Boolean>()
        val emittedLinkUrls = CopyOnWriteArrayList<String>()
        val completedLinkCallbacks = CopyOnWriteArrayList<Int>()
        val linkResolutionStarted = CountDownLatch(1)
        val allowFirstLinkCallback = CountDownLatch(1)
        val firstLinkCallbackDelivered = CountDownLatch(1)
        val allowLinkResolutionReturn = CountDownLatch(1)
        val emittedSubtitleFiles = CopyOnWriteArrayList<SubtitleFile>()
        val emittedSubtitleLanguages = CopyOnWriteArrayList<String>()
        val emittedSubtitleUrls = CopyOnWriteArrayList<String>()
        @Volatile var linkCalls = 0
            private set

        override fun getName(): String = providerName
        override fun getMainUrl(): String = providerUrl
        override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Movie)
        override fun getHasMainPage(): Boolean = true
        override fun getMainPage(): List<MainPageData> = listOf(
            MainPageData(homeRequestAName, "fixture-feed-a", true),
            MainPageData(homeRequestBName, "fixture-feed-b", true),
        )

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            check(page == 1) { "fixture Home supports only page one" }
            homeRequests.add("$page:${request.name}")
            val movie = if (request.data == "fixture-feed-a") movies[0] else movies[1]
            val shelf = if (request.data == "fixture-feed-a") shelfATitle else shelfBTitle
            return HomePageResponse(listOf(HomePageList(shelf, listOf(movie.searchResponse(providerName)), true)), false)
        }

        override suspend fun search(query: String, page: Int): SearchResponseList {
            searchRequests.add("$query:$page")
            val results = if (query.equals(SEARCH_QUERY, ignoreCase = true) && page == 1) {
                movies.map { it.searchResponse(providerName) }
            } else emptyList()
            searchResultUrls.addAll(results.map { it.url })
            return SearchResponseList(results, false)
        }

        override suspend fun load(url: String): LoadResponse {
            detailRequests.add(url)
            delay(350L)
            val movie = movies.firstOrNull { it.url == url }
                ?: error("fixture received an unknown details URL")
            return MovieLoadResponse(
                movie.title, movie.url, providerName, TvType.Movie,
                "fixture-movie-data:${movie.url}", movie.posterUrl, 2026,
                "Local fixture details returned by MainAPI.load.", null,
                listOf("Fixture"), 105, arrayListOf<com.lagradost.cloudstream3.TrailerData>(), emptyList(), emptyList(),
                false, linkedMapOf(), null, null, null, "PG", movie.url,
            )
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            linkCalls++
            val callNumber = linkCalls
            linkRequestData.add(data)
            castingRequests.add(isCasting)
            check(data == "fixture-movie-data:${movies[0].url}") {
                "fixture loadLinks must receive the loaded movie provider data"
            }
            if (pauseAfterFirstLinkCallback) {
                linkResolutionStarted.countDown()
                check(allowFirstLinkCallback.await(PROGRESSIVE_PROVIDER_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "the progressive movie fixture was not released for its first callback"
                }
            }
            val subtitle = SubtitleFile("en", subtitleUrl).apply { headers = emptyMap() }
            emittedSubtitleFiles.add(subtitle)
            emittedSubtitleLanguages.add(subtitle.lang)
            emittedSubtitleUrls.add(subtitle.url)
            subtitleCallback(subtitle)
            val protectedSubtitle = SubtitleFile("fr", "https://captions.fixture.invalid/captions.fr.vtt")
                .apply { headers = mapOf("X-Subtitle-Fixture" to "subtitle-token") }
            emittedSubtitleFiles.add(protectedSubtitle)
            emittedSubtitleLanguages.add(protectedSubtitle.lang)
            emittedSubtitleUrls.add(protectedSubtitle.url)
            subtitleCallback(protectedSubtitle)
            val first = ExtractorLink(
                "Loopback fixture", "Fixture MP4 360p", firstSourceUrl, "", 360,
                false, emptyMap(), null,
            )
            val selected = ExtractorLink(
                "Loopback fixture", "Fixture MP4 1080p", selectedSourceUrl, "", 1080,
                false, emptyMap(), null,
            )
            val queried = ExtractorLink(
                "Loopback fixture", "Fixture MP4 720p Query", queriedSourceUrl, "", 720,
                false, emptyMap(), null,
            )
            if (pauseAfterFirstLinkCallback) {
                emittedLinkUrls.add(selected.url)
                callback(selected)
                firstLinkCallbackDelivered.countDown()
                check(allowLinkResolutionReturn.await(PROGRESSIVE_PROVIDER_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "the progressive movie fixture was not released after its first callback"
                }
                emittedLinkUrls.add(first.url)
                callback(first)
            } else {
                emittedLinkUrls.add(first.url)
                callback(first)
                emittedLinkUrls.add(selected.url)
                callback(selected)
            }
            emittedLinkUrls.add(queried.url)
            callback(queried)
            completedLinkCallbacks.add(callNumber)
            return true
        }
    }

    private data class FixtureMovie(val title: String, val url: String, val posterUrl: String) {
        fun searchResponse(providerName: String): SearchResponse = MovieSearchResponse(
            title, url, providerName, TvType.Movie, posterUrl, 2026,
            null, null, emptyMap(), null,
        )
    }

    /** Test-only loopback HTTP endpoint for bounded media, subtitles, and deterministic HLS failures. */
    private class LoopbackMediaFixtureServer(context: Context) : Closeable {
        private val media = context.assets.open("jellyfin-fixture.mp4").use { it.readBytes() }
        private val subtitle = WEBVTT_FIXTURE.toByteArray(StandardCharsets.UTF_8)
        private val hlsManifest = HLS_403_MANIFEST.toByteArray(StandardCharsets.UTF_8)
        private val secondHlsManifest = HLS_403_SECOND_MANIFEST.toByteArray(StandardCharsets.UTF_8)
        private val artwork = mapOf(
            "/provider-artwork/home-coast.png" to context.assets.open("mockup-home-hero-coast.png").use { it.readBytes() },
            "/provider-artwork/mira.png" to context.assets.open("mockup-poster-mira.png").use { it.readBytes() },
            "/provider-artwork/hannah.png" to context.assets.open("mockup-poster-hannah.png").use { it.readBytes() },
            "/provider-artwork/orbit-original.jpg" to context.assets.open("jellyfin-poster.jpg").use { it.readBytes() },
        )
        private val socket = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        private val requests = CopyOnWriteArrayList<MediaFixtureRequest>()
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var closed = false
        private val thread = Thread(::serve, "orbit-provider-flow-loopback-media").apply {
            isDaemon = true
        }

        init {
            require(media.isNotEmpty() && media.size <= MAX_MEDIA_BYTES) {
                "the test MP4 must remain a small bounded fixture"
            }
            require(artwork.values.all { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }) {
                "all test artwork must remain bounded fixture assets"
            }
            socket.soTimeout = ACCEPT_TIMEOUT_MS
            thread.start()
        }

        fun url(path: String): String {
            require(path == "first-source.mp4" || path == "selected-source.mp4"
                    || path == "fcast-source.mp4"
                    || path == "query-source.mp4?fixture=local-only"
                    || path == "failing-source.m3u8" || path == "failing-source-2.m3u8"
                    || path == "segment-403.ts" || path == "segment-403-second.ts"
                    || path == "captions.en.vtt"
                    || path == "provider-artwork/home-coast.png"
                    || path == "provider-artwork/mira.png"
                    || path == "provider-artwork/hannah.png"
                    || path == "provider-artwork/orbit-original.jpg")
            return "http://127.0.0.1:${socket.localPort}/$path"
        }

        fun requests(): List<MediaFixtureRequest> = ArrayList(requests)

        fun failure(): Throwable? = failure.get()

        fun awaitRequest(path: String, timeoutMs: Long, method: String? = null): MediaFixtureRequest? {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                requests.firstOrNull { it.path == path && (method == null || it.method == method) }
                    ?.let { return it }
                failure.get()?.let { throw AssertionError("loopback media fixture server failed", it) }
                SystemClock.sleep(40L)
            }
            return requests.firstOrNull { it.path == path && (method == null || it.method == method) }
        }

        private fun serve() {
            while (!closed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = REQUEST_TIMEOUT_MS
                        serveClient(client)
                    }
                } catch (_: SocketTimeoutException) {
                    // Poll the closed flag so fixture cleanup does not wait on accept().
                } catch (error: SocketException) {
                    if (!closed) failure.compareAndSet(null, error)
                } catch (error: Throwable) {
                    if (!closed) failure.compareAndSet(null, error)
                }
            }
        }

        private fun serveClient(client: Socket) {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val firstLine = readBoundedLine(reader, MAX_REQUEST_LINE_CHARS)
                ?: throw IllegalStateException("fixture received an empty media request")
            val request = firstLine.split(' ', limit = 3)
            if (request.size != 3) throw IllegalStateException("fixture received a malformed media request")
            val method = request[0]
            val rawTarget = request[1]
            val path = rawTarget.substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            var headerBytes = firstLine.length
            var headerCount = 0
            while (true) {
                val line = readBoundedLine(reader, MAX_HEADER_LINE_CHARS)
                    ?: throw IllegalStateException("fixture received an incomplete media request")
                if (line.isEmpty()) break
                headerBytes += line.length
                headerCount++
                if (headerBytes > MAX_HEADER_BYTES || headerCount > MAX_HEADER_COUNT) {
                    throw IllegalStateException("fixture media request headers exceeded the test bound")
                }
                val separator = line.indexOf(':')
                if (separator <= 0) throw IllegalStateException("fixture received a malformed media header")
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }

            val imageBytes = artwork[path]
            val allowedPath = path == "/first-source.mp4" || path == "/selected-source.mp4"
                || path == "/fcast-source.mp4"
                || path == "/failing-source.m3u8" || path == "/failing-source-2.m3u8"
                || path == "/segment-403.ts" || path == "/segment-403-second.ts"
                || path == "/captions.en.vtt" || imageBytes != null
            val response = when {
                rawTarget != path || !allowedPath || (method != "GET" && method != "HEAD") ->
                    MediaFixtureResponse(404, "Not Found", "text/plain", ByteArray(0), emptyMap())
                path == "/failing-source.m3u8" -> MediaFixtureResponse(
                    200, "OK", "application/vnd.apple.mpegurl", hlsManifest, emptyMap(),
                )
                path == "/failing-source-2.m3u8" -> MediaFixtureResponse(
                    200, "OK", "application/vnd.apple.mpegurl", secondHlsManifest, emptyMap(),
                )
                path == "/segment-403.ts" || path == "/segment-403-second.ts" -> MediaFixtureResponse(
                    403, "Forbidden", "text/plain", ByteArray(0), emptyMap(),
                )
                path == "/captions.en.vtt" -> MediaFixtureResponse(
                    200, "OK", "text/vtt; charset=utf-8", subtitle, emptyMap(),
                )
                imageBytes != null -> MediaFixtureResponse(
                    200, "OK", if (path.endsWith(".jpg")) "image/jpeg" else "image/png",
                    imageBytes, emptyMap(),
                )
                else -> rangeResponse(headers["range"])
            }
            writeResponse(client.getOutputStream(), method, response)
            requests.add(MediaFixtureRequest(method, path, rawTarget, headers["host"].orEmpty(), response.status,
                if (method == "HEAD") 0 else response.body.size, headers["range"].orEmpty(),
                headers.keys.toSet()))
        }

        private fun rangeResponse(rangeHeader: String?): MediaFixtureResponse {
            if (rangeHeader.isNullOrEmpty()) {
                return MediaFixtureResponse(200, "OK", "video/mp4", media,
                    mapOf("Accept-Ranges" to "bytes"))
            }
            val match = RANGE_PATTERN.matchEntire(rangeHeader)
                ?: return MediaFixtureResponse(416, "Range Not Satisfiable", "text/plain",
                    ByteArray(0), mapOf("Content-Range" to "bytes */${media.size}"))
            val requestedStart = match.groupValues[1]
            val requestedEnd = match.groupValues[2]
            val start = if (requestedStart.isEmpty()) {
                val suffix = requestedEnd.toLongOrNull()
                if (suffix == null || suffix <= 0L) return MediaFixtureResponse(
                    416, "Range Not Satisfiable", "text/plain", ByteArray(0),
                    mapOf("Content-Range" to "bytes */${media.size}"),
                )
                (media.size.toLong() - suffix).coerceAtLeast(0L)
        } else requestedStart.toLongOrNull()
                ?: return MediaFixtureResponse(416, "Range Not Satisfiable", "text/plain",
                    ByteArray(0), mapOf("Content-Range" to "bytes */${media.size}"))
            val end = if (requestedStart.isEmpty() || requestedEnd.isEmpty()) media.lastIndex.toLong()
                else requestedEnd.toLongOrNull()
                    ?: return MediaFixtureResponse(416, "Range Not Satisfiable", "text/plain",
                        ByteArray(0), mapOf("Content-Range" to "bytes */${media.size}"))
            if (start >= media.size || end < start) return MediaFixtureResponse(
                416, "Range Not Satisfiable", "text/plain", ByteArray(0),
                mapOf("Content-Range" to "bytes */${media.size}"),
            )
            val boundedEnd = end.coerceAtMost(media.lastIndex.toLong())
            val first = start.toInt()
            val last = boundedEnd.toInt()
            val body = media.copyOfRange(first, last + 1)
            return MediaFixtureResponse(206, "Partial Content", "video/mp4", body,
                mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes $first-$last/${media.size}",
                ))
        }

        private fun writeResponse(output: OutputStream, method: String, response: MediaFixtureResponse) {
            val headers = LinkedHashMap(response.headers)
            headers["Content-Type"] = response.contentType
            headers["Content-Length"] = response.body.size.toString()
            headers["Connection"] = "close"
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            if (method != "HEAD") output.write(response.body)
            output.flush()
        }

        private fun readBoundedLine(reader: BufferedReader, maximum: Int): String? {
            val line = reader.readLine() ?: return null
            require(line.length <= maximum) { "fixture media request line exceeded the test bound" }
            return line
        }

        override fun close() {
            closed = true
            socket.close()
            thread.join(SERVER_SHUTDOWN_MS)
            check(!thread.isAlive) { "loopback media fixture server must stop promptly" }
            failure.get()?.let { throw AssertionError("loopback media fixture server failed", it) }
        }

        data class MediaFixtureRequest(
            val method: String,
            val path: String,
            val requestTarget: String,
            val host: String,
            val status: Int,
            val bytes: Int,
            val range: String,
            val headerNames: Set<String>,
        )

        data class MediaFixtureResponse(
            val status: Int,
            val reason: String,
            val contentType: String,
            val body: ByteArray,
            val headers: Map<String, String>,
        )

        companion object {
            private const val MAX_MEDIA_BYTES = 2 * 1024 * 1024
            private const val MAX_ARTWORK_BYTES = 3 * 1024 * 1024
            private const val ACCEPT_TIMEOUT_MS = 500
            private const val REQUEST_TIMEOUT_MS = 3_000
            private const val SERVER_SHUTDOWN_MS = 2_000L
            private const val MAX_REQUEST_LINE_CHARS = 8_192
            private const val MAX_HEADER_LINE_CHARS = 8_192
            private const val MAX_HEADER_BYTES = 16_384
            private const val MAX_HEADER_COUNT = 32
            private val RANGE_PATTERN = Regex("^bytes=(\\d*)-(\\d*)$")
        }
    }

    companion object {
        private const val TAG = "OrbitCs3ProviderFlow"
        private const val UI_TIMEOUT_MS = 15_000L
        private const val PROGRESSIVE_PLAYBACK_TEST_TIMEOUT_MS = 60_000L
        private const val PROGRESSIVE_PROVIDER_GATE_TIMEOUT_MS = 120_000L
        private const val PLAYER_LATE_START_GRACE_MS = 15_000L
        private val SOURCE_SHA256_PATTERN = Regex("^[0-9A-Fa-f]{64}$")
        private const val PROVIDER_DRAIN_TIMEOUT_MS = 15_000L
        private const val FIXTURE_PLUGIN_ID = "orbit-local-provider-flow-fixture"
        private const val PROVIDER_ID_PREFIX = "cs3:"
        private const val FIXTURE_PROVIDER_NAME = "Orbit Provider Flow Fixture"
        private const val FIXTURE_PROVIDER_URL = "https://provider-flow.invalid"
        private const val FIXTURE_SERIES_TITLE = "Orbit Episode Fixture Series"
        private const val FIXTURE_SERIES_SEARCH_QUERY = FIXTURE_SERIES_TITLE
        private const val FIXTURE_SERIES_URL = "https://provider-flow.invalid/title/episode-series"
        private const val FIXTURE_LIVE_TITLE = "Orbit Dynamic Live Fixture"
        private const val FIXTURE_LIVE_SEARCH_QUERY = FIXTURE_LIVE_TITLE
        private const val FIXTURE_LIVE_URL = "https://provider-flow.invalid/live/dynamic"
        private const val FIXTURE_EPISODE_DATA = "fixture-episode-payload:episode-tap"
        private const val REPLACEMENT_EPISODE_DATA = "fixture-episode-payload:replacement-registration"
        private const val FIXTURE_EPISODE_TITLE = "The Episode Tap"
        private const val EPISODE_LINK_CALLBACK_DELAY_MS = 2_500L
        private const val SECONDARY_PROVIDER_NAME = "Orbit Secondary Provider Fixture"
        private const val SECONDARY_PROVIDER_URL = "https://secondary-provider-flow.invalid"
        private const val FIXTURE_REPOSITORY_URL = "https://provider-flow.invalid/repository.json"
        private const val FIXTURE_DIRECTORY_NAME = "cs3-populated-mockup-fixture-2026-11-05"
        private const val EXTENSION_PREFERENCES = "orbit_cloudstream_extensions_v1"
        private const val PROVIDER_PREFERENCES = "orbit_provider_selection_v1"
        private const val PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1"
        private const val SEARCH_QUERY = "Orbit Fixture Movie"
        private const val MOVIE_A_TITLE = "A Orbit Fixture Movie"
        private const val MOVIE_B_TITLE = "B Orbit Fixture Movie"
        private const val MOVIE_C_TITLE = "C Orbit Fixture Movie"
        private const val MOVIE_D_TITLE = "D Orbit Fixture Movie"
        private const val MOVIE_E_TITLE = "E Orbit Fixture Movie"
        private const val MOVIE_F_TITLE = "F Orbit Fixture Movie"
        private const val SECONDARY_MOVIE_G_TITLE = "G Orbit Fixture Movie"
        private const val SECONDARY_MOVIE_H_TITLE = "H Orbit Fixture Movie"
        private const val SECONDARY_HOME_REQUEST_A_NAME = "Secondary Movies A"
        private const val SECONDARY_HOME_REQUEST_B_NAME = "Secondary Movies B"
        private const val MOVIE_A_URL = "https://provider-flow.invalid/title/movie-a"
        private const val MOVIE_B_URL = "https://provider-flow.invalid/title/movie-b"
        private const val MOVIE_C_URL = "https://provider-flow.invalid/title/movie-c"
        private const val MOVIE_D_URL = "https://provider-flow.invalid/title/movie-d"
        private const val MOVIE_E_URL = "https://provider-flow.invalid/title/movie-e"
        private const val MOVIE_F_URL = "https://provider-flow.invalid/title/movie-f"
        private const val SECONDARY_MOVIE_G_URL = "https://secondary-provider-flow.invalid/title/movie-g"
        private const val SECONDARY_MOVIE_H_URL = "https://secondary-provider-flow.invalid/title/movie-h"
        private const val FCAST_ACTION_LABEL = "Fcast to device"
        private const val FCAST_SOURCE_OPTION = "Fixture MP4 1080p · 1080p"
        private const val FCAST_SOURCE_PATH = "/fcast-source.mp4"
        private val FIXTURE_LIBRARY_TITLE_IDS = setOf(
            "mockup-fixture-quiet-between", "mockup-fixture-northbound",
            "mockup-fixture-glass-harbor", "mockup-fixture-silent-meridian",
            "mockup-fixture-long-winter", "mockup-fixture-emberline",
            "mockup-fixture-paper-moons", "mockup-fixture-wild-current",
            "mockup-fixture-blue-hours", "mockup-fixture-far-mountain",
        )
        private val FIXTURE_CONTINUE_TITLE_IDS = setOf(
            "mockup-cw-deep-signal", "mockup-cw-signal-08", "mockup-cw-afterlight",
            "mockup-cw-northbound-line", "mockup-fixture-glass-harbor",
            "mockup-fixture-silent-meridian", "mockup-fixture-paper-moons",
            "mockup-cw-atlas-of-ash",
        )
        private const val WEBVTT_FIXTURE = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nOrbit fixture subtitle\n"
        private const val HLS_403_MANIFEST =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nsegment-403.ts\n#EXT-X-ENDLIST\n"
        private const val HLS_403_SECOND_MANIFEST =
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nsegment-403-second.ts\n#EXT-X-ENDLIST\n"
        private const val SHELF_A_TITLE = "Fixture Movie Shelf A"
        private const val SHELF_B_TITLE = "Fixture Movie Shelf B"
        private const val SECONDARY_SHELF_A_TITLE = "Secondary Fixture Shelf A"
        private const val SECONDARY_SHELF_B_TITLE = "Secondary Fixture Shelf B"
    }
}
