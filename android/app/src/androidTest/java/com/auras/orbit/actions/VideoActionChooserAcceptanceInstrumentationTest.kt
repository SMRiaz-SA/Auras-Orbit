package com.auras.orbit.actions

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auras.orbit.BuildConfig
import com.auras.orbit.OrbitApplication
import com.auras.orbit.extensions.CloudStreamExtensionManager
import com.auras.orbit.extensions.OrbitCs3HostServices
import com.auras.orbit.navigation.ShellState
import com.auras.orbit.navigation.ShellRoute
import com.auras.orbit.player.OrbitPlayerActivity
import com.auras.orbit.player.PlaybackRequest
import com.auras.orbit.profile.LocalProfileStore
import com.auras.orbit.providers.MediaItem
import com.auras.orbit.providers.MediaKind
import com.auras.orbit.providers.MediaProvider
import com.auras.orbit.providers.Cs3ProviderRegistry
import com.auras.orbit.ui.OrbitShellActivity
import com.auras.orbit.providers.Cs3ActionContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.PlayMirrorAction
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.UiText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic-only acceptance of the actual Details “Open with” chooser and source dispatch. */
@RunWith(AndroidJUnit4::class)
class VideoActionChooserAcceptanceInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var target: Context
    private lateinit var application: OrbitApplication
    private lateinit var profileStore: LocalProfileStore
    private lateinit var profilePreferences: SharedPreferences
    private lateinit var extensionPreferences: SharedPreferences
    private lateinit var providerPreferences: SharedPreferences
    private lateinit var profileSnapshot: PreferenceSnapshot
    private lateinit var extensionSnapshot: PreferenceSnapshot
    private lateinit var providerSnapshot: PreferenceSnapshot
    private lateinit var shell: OrbitShellActivity
    private lateinit var hostServices: OrbitCs3HostServices
    private lateinit var extensionManager: CloudStreamExtensionManager
    private lateinit var pluginId: String
    private lateinit var profileId: String
    private lateinit var fixtureRoot: File
    private lateinit var registrationMarker: File
    private lateinit var fixtureApi: FixtureMainApi
    private lateinit var multiSourceAction: FixtureAction
    private lateinit var oneSourceAction: FixtureAction
    private var playbackMonitor: Instrumentation.ActivityMonitor? = null
    private var playbackActivity: OrbitPlayerActivity? = null
    private lateinit var dialogEvidenceFile: File
    private var transaction: OrbitCs3HostServices.LoadTransaction? = null
    private var profileCreated = false
    private var profileSnapshotCaptured = false
    private var extensionSnapshotCaptured = false
    private var providerSnapshotCaptured = false
    private var originalActiveProfileId: String? = null
    private var originalRuntimeProfileId: String? = null
    private var initialActionOrder: List<VideoClickAction> = emptyList()
    private var initialProviderOrder: List<MainAPI> = emptyList()

    @Before
    fun prepareIsolatedDebugFixture() {
        target = instrumentation.targetContext
        assertEquals("chooser acceptance uses only the side-by-side .debug app",
            "com.auras.orbit.debug", target.packageName)
        assertFalse("the normal CS3 gate remains false", BuildConfig.CS3_CANARY_BUILD)
        application = target.applicationContext as? OrbitApplication
            ?: throw AssertionError("the explicit instrumentation marker must start OrbitApplication")
        assertTrue("the accepted CS3 crosswalk is active while the canary remains disabled",
            application.cS3PluginRuntime.isTargetPluginCrosswalkValidated)
        extensionManager = application.cloudStreamExtensionManager
        hostServices = OrbitCs3HostServices.get()
        assertTrue("extension metadata recovery completes before the fixture writes preferences",
            extensionManager.isStorageRecoveryReady)

        profilePreferences = application.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
        extensionPreferences = extensionPreferencesFor(application)
        providerPreferences = application.getSharedPreferences(PROVIDER_PREFERENCES, Context.MODE_PRIVATE)
        profileSnapshot = snapshotPreferences(PROFILE_PREFERENCES, profilePreferences)
        profileSnapshotCaptured = true
        extensionSnapshot = snapshotPreferences(EXTENSION_PREFERENCES, extensionPreferences)
        extensionSnapshotCaptured = true
        providerSnapshot = snapshotPreferences(PROVIDER_PREFERENCES, providerPreferences)
        // Profile startup and Home rendering can create provider-selection keys before the
        // synthetic provider fixture is registered. Restore that snapshot even if setup fails.
        providerSnapshotCaptured = true
        originalActiveProfileId = application.localProfileStore.activeProfileId
        val baselineProfileId = originalActiveProfileId?.takeIf { it.isNotBlank() }
        val baselineFailures = application.cS3PluginRuntime.reconcileProfile(
            baselineProfileId, extensionManager)
        assertTrue("the runtime baseline reconciles with target CS3 activation closed",
            baselineFailures.isEmpty())
        originalRuntimeProfileId = application.cS3PluginRuntime.activeProfileId
        assertEquals("the captured runtime baseline matches the active profile",
            baselineProfileId, originalRuntimeProfileId)

        val profile = application.localProfileStore.createProfile(
            "Temporary chooser acceptance ${UUID.randomUUID()}")
            ?: throw AssertionError("the isolated chooser profile could not be created")
        profileCreated = true
        profileId = profile.id
        application.localProfileStore.selectProfile(profileId)

        val launched = instrumentation.startActivitySync(
            Intent(target, OrbitShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        shell = launched as? OrbitShellActivity
            ?: throw AssertionError("the fixture must launch the actual OrbitShellActivity")
        continueIntoOrbitHome()
        profileStore = application.localProfileStore
        await("the isolated profile runtime reconciliation") {
            application.cS3PluginRuntime.activeProfileId == profileId
        }

        initialActionOrder = VideoClickActionHolder.allVideoClickActions.toList()
        initialProviderOrder = ArrayList(APIHolder.INSTANCE.allProviders)
        pluginId = "https://orbit.invalid/androidTest/video-action-chooser/${UUID.randomUUID()}"
        val evidenceName = "video-action-chooser-${pluginId.substringAfterLast('/')}-$DIALOG_EVIDENCE_FILE"
        dialogEvidenceFile = File(target.cacheDir, evidenceName)
        dialogEvidenceFile.writeText("")
        assertNull("the synthetic metadata-only fixture ID must be unique",
            extensionManager.getInstalledPlugin(pluginId))
        assertFalse("the fixture must begin without a provider/action registration",
            hostServices.hasOutstandingRegistrations(pluginId))

        fixtureRoot = File(target.cacheDir, "video-action-chooser-${UUID.randomUUID()}")
        assertTrue("synthetic registration artifacts remain under app cache",
            fixtureRoot.mkdirs() && fixtureRoot.canonicalPath.startsWith(target.cacheDir.canonicalPath))
        registrationMarker = File(fixtureRoot, "registration.fixture-marker")
        assertTrue("the host transaction path is a synthetic marker, not a package",
            registrationMarker.createNewFile())

        fixtureApi = FixtureMainApi()
        multiSourceAction = FixtureAction("Fixture action all sources", false)
        oneSourceAction = FixtureAction("Fixture action choose source", true)
        registerSyntheticProviderAndActions()
    }

    @After
    fun restoreAllSyntheticStateAndLeaveHome() {
        if (!::target.isInitialized) return
        val cleanupFailures = ArrayList<Throwable>()
        fun attemptCleanup(step: String, action: () -> Unit): Boolean = try {
            action()
            true
        } catch (error: Throwable) {
            cleanupFailures.add(AssertionError("$step failed", error))
            false
        }
        fun cleanupGate(step: String, action: () -> Boolean): Boolean = try {
            if (action()) true else {
                cleanupFailures.add(AssertionError(step))
                false
            }
        } catch (error: Throwable) {
            cleanupFailures.add(AssertionError("$step failed", error))
            false
        }

        val player = playbackActivity
        if (player != null && !player.isDestroyed && !player.isFinishing) {
            attemptCleanup("closing Orbit Player") {
                instrumentation.runOnMainSync { player.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        val activity = if (::shell.isInitialized) shell else null
        if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
            attemptCleanup("closing the chooser or provider Details screen") {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
            }
            attemptCleanup("closing provider title Details") {
                instrumentation.runOnMainSync { closeProviderTitleDetails(activity) }
            }
        }
        val monitor = playbackMonitor
        if (monitor != null) {
            attemptCleanup("removing the Orbit Player activity monitor") {
                instrumentation.removeMonitor(monitor)
                playbackMonitor = null
            }
        }

        if (::application.isInitialized && ::pluginId.isInitialized && transaction != null) {
            val registry = try {
                application.cs3ProviderRegistry
            } catch (error: Throwable) {
                cleanupFailures.add(AssertionError("obtaining the provider registry for drain failed", error))
                null
            }
            if (registry != null) {
                val blockSucceeded = attemptCleanup("blocking synthetic provider calls") {
                    registry.blockPluginCalls(pluginId)
                }
                // Run every quiescence check even if one fails, but do not roll back unless
                // every required drain gate has succeeded.
                val pluginCallsDrained = cleanupGate("synthetic provider calls drain before rollback") {
                    registry.pauseAndAwaitPluginCalls(pluginId, PROVIDER_DRAIN_TIMEOUT_MS)
                }
                val providerWorkDrained = cleanupGate("all provider calls drain before rollback") {
                    registry.awaitAllProviderWorkQuiesced(PROVIDER_DRAIN_TIMEOUT_MS)
                }
                val providerSnapshotsDrained = cleanupGate("provider snapshots drain before rollback") {
                    registry.awaitProviderSnapshotsQuiesced(PROVIDER_DRAIN_TIMEOUT_MS)
                }
                if (blockSucceeded && pluginCallsDrained && providerWorkDrained && providerSnapshotsDrained) {
                    val activeTransaction = transaction
                    if (activeTransaction != null && attemptCleanup("rolling back the synthetic registration") {
                            activeTransaction.rollback()
                            transaction = null
                        }) {
                        attemptCleanup("finishing synthetic provider registry cleanup") {
                            registry.onPluginCleanupFinished(pluginId)
                        }
                    }
                }
            }
        }

        // Restore the exact captured extension file even if fixture commit threw before returning.
        if (extensionSnapshotCaptured) {
            attemptCleanup("restoring extension preferences") {
                restorePreferences(extensionSnapshot, extensionPreferences)
            }
        }

        if (::application.isInitialized && ::pluginId.isInitialized && ::hostServices.isInitialized) {
            attemptCleanup("checking synthetic provider registration rollback") {
                assertFalse("rollback removes the synthetic provider/action registration",
                    hostServices.hasOutstandingRegistrations(pluginId))
            }
            if (::fixtureApi.isInitialized) {
                attemptCleanup("checking APIHolder releases the fake MainAPI instance") {
                    assertFalse("APIHolder releases the fake MainAPI instance",
                        APIHolder.INSTANCE.allProviders.any { it === fixtureApi })
                }
            }
            attemptCleanup("checking APIHolder provider order") {
                assertSameIdentityOrder("APIHolder returns to its baseline", initialProviderOrder,
                    APIHolder.INSTANCE.allProviders)
            }
        }

        if (profileCreated && ::application.isInitialized) {
            attemptCleanup("restoring the original runtime profile") {
                val restoredRuntimeProfile = originalRuntimeProfileId?.takeIf { it.isNotBlank() }
                val failures = application.cS3PluginRuntime.reconcileProfile(
                    restoredRuntimeProfile, extensionManager)
                assertTrue("the original runtime profile restores with CS3 activation closed", failures.isEmpty())
            }
        }
        if (profileSnapshotCaptured) {
            val restored = attemptCleanup("restoring profile preferences") {
                restorePreferences(profileSnapshot, profilePreferences)
            }
            if (restored) profileCreated = false
        }
        if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
            attemptCleanup("returning the shell to Home") {
                instrumentation.runOnMainSync {
                    shellState(activity).navigateTo(ShellRoute.HOME)
                    invokeRender(activity)
                }
            }
            attemptCleanup("waiting for Home rendering to become idle") { instrumentation.waitForIdleSync() }
        }
        if (::application.isInitialized && ::pluginId.isInitialized && ::hostServices.isInitialized
            && hostServices.hasOutstandingRegistrations(pluginId)) {
            val registry = try {
                application.cs3ProviderRegistry
            } catch (error: Throwable) {
                cleanupFailures.add(AssertionError("obtaining the provider registry for final Home cleanup failed", error))
                null
            }
            if (registry != null) {
                val blockSucceeded = attemptCleanup("blocking synthetic provider calls after Home") {
                    registry.blockPluginCalls(pluginId)
                }
                val pluginCallsDrained = cleanupGate("synthetic provider calls drain after Home") {
                    registry.pauseAndAwaitPluginCalls(pluginId, PROVIDER_DRAIN_TIMEOUT_MS)
                }
                val providerWorkDrained = cleanupGate("all provider calls drain after Home") {
                    registry.awaitAllProviderWorkQuiesced(PROVIDER_DRAIN_TIMEOUT_MS)
                }
                val providerSnapshotsDrained = cleanupGate("provider snapshots drain after Home") {
                    registry.awaitProviderSnapshotsQuiesced(PROVIDER_DRAIN_TIMEOUT_MS)
                }
                if (blockSucceeded && pluginCallsDrained && providerWorkDrained && providerSnapshotsDrained) {
                    attemptCleanup("removing synthetic registrations recreated by Home") {
                        assertTrue("the synthetic registration is unloaded after Home",
                            hostServices.unloadPlugin(pluginId))
                        registry.onPluginCleanupFinished(pluginId)
                    }
                }
            }
        }

        // Home may recreate persisted state after the first restoration. Reapply each exact
        // snapshot, then reconcile the runtime to the profile that the restored preferences select.
        if (extensionSnapshotCaptured) {
            attemptCleanup("restoring extension preferences after Home rendering") {
                restorePreferences(extensionSnapshot, extensionPreferences)
            }
        }
        if (profileSnapshotCaptured) {
            val restored = attemptCleanup("restoring profile preferences after Home rendering") {
                restorePreferences(profileSnapshot, profilePreferences)
            }
            if (restored) profileCreated = false
        }
        if (providerSnapshotCaptured || ::providerSnapshot.isInitialized) {
            attemptCleanup("restoring provider preferences after final Home rendering") {
                restorePreferences(providerSnapshot, providerPreferences)
            }
            providerSnapshotCaptured = false
        }
        if (::application.isInitialized && ::extensionManager.isInitialized && profileSnapshotCaptured) {
            attemptCleanup("reconciling runtime to the restored active profile") {
                val restoredActiveProfileId = application.localProfileStore.activeProfileId
                    .takeIf { it.isNotBlank() }
                val failures = application.cS3PluginRuntime.reconcileProfile(
                    restoredActiveProfileId, extensionManager)
                assertTrue("the restored active profile reconciles with CS3 activation closed",
                    failures.isEmpty())
                assertEquals("runtime profile follows the restored active profile preference",
                    restoredActiveProfileId, application.cS3PluginRuntime.activeProfileId)
            }
        }

        if (::registrationMarker.isInitialized) {
            attemptCleanup("removing the synthetic host registration marker") {
                assertTrue("the synthetic host registration marker is removed",
                    registrationMarker.delete() || !registrationMarker.exists())
            }
        }
        if (::fixtureRoot.isInitialized) {
            attemptCleanup("checking the synthetic cache directory is empty") {
                assertTrue("the synthetic cache directory is empty after cleanup",
                    fixtureRoot.listFiles().isNullOrEmpty())
            }
            attemptCleanup("removing the synthetic cache directory") {
                assertTrue("the synthetic cache directory is removed",
                    fixtureRoot.delete() || !fixtureRoot.exists())
            }
        }
        if (::hostServices.isInitialized) {
            if (::pluginId.isInitialized) {
                attemptCleanup("checking synthetic provider registration rollback after Home") {
                    assertFalse("Home leaves no synthetic provider/action registration",
                        hostServices.hasOutstandingRegistrations(pluginId))
                }
            }
            if (::fixtureApi.isInitialized) {
                attemptCleanup("checking APIHolder releases the fake MainAPI after Home") {
                    assertFalse("Home leaves no fake MainAPI instance in APIHolder",
                        APIHolder.INSTANCE.allProviders.any { it === fixtureApi })
                }
            }
            attemptCleanup("checking APIHolder provider order after Home") {
                assertSameIdentityOrder("APIHolder returns to its baseline after Home", initialProviderOrder,
                    APIHolder.INSTANCE.allProviders)
            }
            attemptCleanup("checking the video action registry order") {
                assertSameIdentityOrder("video action registry returns to the pre-test order",
                    initialActionOrder, VideoClickActionHolder.allVideoClickActions.toList())
            }
        }
        if (extensionSnapshotCaptured) {
            attemptCleanup("verifying extension preference restoration") {
                assertSnapshotRestored(extensionSnapshot, extensionPreferences)
            }
        }
        if (providerSnapshotCaptured || ::providerSnapshot.isInitialized) {
            attemptCleanup("verifying provider preference restoration") {
                assertSnapshotRestored(providerSnapshot, providerPreferences)
            }
        }
        if (profileSnapshotCaptured) {
            attemptCleanup("verifying profile preference restoration") {
                assertSnapshotRestored(profileSnapshot, profilePreferences)
            }
            if (::application.isInitialized) {
                attemptCleanup("verifying the original active profile ID") {
                    assertEquals("the original active profile ID is restored",
                        originalActiveProfileId, application.localProfileStore.activeProfileId)
                }
            }
        }
        if (::application.isInitialized) {
            attemptCleanup("verifying the captured runtime profile matches the active profile baseline") {
                val capturedActiveProfileId = originalActiveProfileId?.takeIf { it.isNotBlank() }
                val capturedRuntimeProfileId = originalRuntimeProfileId?.takeIf { it.isNotBlank() }
                assertEquals("captured runtime and active profile IDs match",
                    capturedActiveProfileId, capturedRuntimeProfileId)
            }
            attemptCleanup("verifying runtime returns to its captured profile after Home") {
                val capturedRuntimeProfileId = originalRuntimeProfileId?.takeIf { it.isNotBlank() }
                assertEquals("runtime retains the captured pre-test profile after Home",
                    capturedRuntimeProfileId, application.cS3PluginRuntime.activeProfileId)
                assertEquals("runtime remains aligned with the captured active profile baseline",
                    originalActiveProfileId?.takeIf { it.isNotBlank() },
                    application.cS3PluginRuntime.activeProfileId)
            }
            attemptCleanup("verifying the CS3 canary gate remains disabled") {
                assertFalse("cleanup leaves the CS3 canary gate disabled", BuildConfig.CS3_CANARY_BUILD)
            }
            attemptCleanup("verifying target plugin crosswalk activation remains closed") {
                assertTrue("cleanup preserves the accepted target plugin crosswalk state",
                    application.cS3PluginRuntime.isTargetPluginCrosswalkValidated)
            }
        }
        if (::dialogEvidenceFile.isInitialized) {
            attemptCleanup("deleting the chooser evidence file") {
                assertTrue("the exact chooser evidence file is deleted from target cache",
                    dialogEvidenceFile.delete() || !dialogEvidenceFile.exists())
            }
            attemptCleanup("verifying the chooser evidence file is absent") {
                assertFalse("target cache has no chooser evidence file after cleanup",
                    dialogEvidenceFile.exists())
            }
        }

        if (cleanupFailures.isNotEmpty()) {
            val cleanupFailure = AssertionError("chooser fixture cleanup failed", cleanupFailures.first())
            cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
    }

    @Test
    fun detailsOpenWithListsActionsAndDispatchesMultiSourceAction() {
        openDetailsAndShowChooser()
        clickDialogText(multiSourceAction.label)
        assertTrue("multi-source action callback completes",
            multiSourceAction.invoked.await(ACTION_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val invocation = multiSourceAction.lastInvocation.get()
        assertNotNull("multi-source callback result is captured", invocation)
        assertNull("multi-source dispatch passes a null selected index", invocation!!.index)
        assertEquals("multi-source callback receives both original CS3 links", 2, invocation.links.size)
        assertEquals("multi-source callback retains the loaded video data", MOVIE_DATA, invocation.videoData)
        recordDialogEvidence("dispatch=multi index=${invocation.index} links=${invocation.links} " +
            "videoData=${invocation.videoData}")
    }

    @Test
    fun detailsOpenWithOneSourceChooserDispatchesSelectedIndex() {
        openDetailsAndShowChooser()
        clickDialogText(oneSourceAction.label)
        await("the one-source action exposes exact callback names and qualities") {
            visibleDialogTexts("Choose source") != null
        }
        assertDialogRows("Choose source", listOf(
            "$SOURCE_B_LABEL · 1080p", "$SOURCE_A_LABEL · 360p"))
        clickDialogText("$SOURCE_B_LABEL · 1080p")
        assertTrue("one-source action callback completes",
            oneSourceAction.invoked.await(ACTION_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val invocation = oneSourceAction.lastInvocation.get()
        assertNotNull("one-source callback result is captured", invocation)
        assertEquals("the quality-sorted chooser dispatches the first row's link index", 0, invocation!!.index)
        assertEquals("one-source callback receives the complete link result", 2, invocation.links.size)
        assertEquals("the callback links retain CloudStream's descending-quality order",
            listOf(SOURCE_B_URL, SOURCE_A_URL), invocation.links)
        assertEquals("one-source callback uses the loaded video data", MOVIE_DATA, invocation.videoData)
        recordDialogEvidence("dispatch=one-source index=${invocation.index} links=${invocation.links} " +
            "videoData=${invocation.videoData}")
        assertEquals("Details resolves synthetic links once", 1, fixtureApi.linkCalls)
    }

    @Test
    fun detailsOpenWithPlayMirrorHandsSelectedSafeSourceToOrbitPlayer() {
        fixtureApi.sourceBUrl = PLAY_MIRROR_SOURCE_B_URL
        val chooserOptions = openDetailsAndShowChooser()
        assertTrue("the actual Open with chooser exposes the built-in Play mirror option",
            PLAY_MIRROR_LABEL in chooserOptions)
        val loadedDetailsItem = readField(shell, "providerDetailsItem") as? MediaItem
            ?: throw AssertionError("the real Details flow must retain the loaded synthetic MediaItem")
        val playMirrorAction = VideoClickActionHolder.allVideoClickActions
            .filterIsInstance<PlayMirrorAction>().singleOrNull()
            ?: throw AssertionError("the built-in Play mirror action must be registered exactly once")
        assertTrue("Play mirror is an Orbit-owned built-in rather than a fixture action",
            VideoClickActionHolder.isOrbitOwnedBuiltinAction(playMirrorAction))

        playbackMonitor = instrumentation.addMonitor(OrbitPlayerActivity::class.java.name, null, false)
        clickDialogText(PLAY_MIRROR_LABEL)
        await("the Play mirror action to expose the quality-sorted safe sources") {
            visibleDialogTexts("Choose source") != null
        }
        assertDialogRows("Choose source", listOf(
            "$SOURCE_B_LABEL · 1080p", "$SOURCE_A_LABEL · 360p"))
        clickDialogText("$SOURCE_B_LABEL · 1080p")

        val launched = playbackMonitor!!.waitForActivityWithTimeout(UI_TIMEOUT_MS)
        assertNotNull("Play mirror must hand the selected source to OrbitPlayerActivity", launched)
        playbackActivity = launched as? OrbitPlayerActivity
            ?: throw AssertionError("the built-in Play mirror action must open OrbitPlayerActivity")
        val request = readField(playbackActivity!!, "request") as? PlaybackRequest
            ?: throw AssertionError("OrbitPlayerActivity must consume the in-process PlaybackRequest")
        val selected = request.getDefaultSource()

        assertEquals("the playback request stays in the disposable fixture profile", profileId,
            request.profileId)
        assertEquals("Play mirror selects the second callback source by stable source ID",
            "provider-source-1", request.defaultSourceId)
        assertEquals("the single-source Play mirror request contains only the chosen safe URL",
            listOf(PLAY_MIRROR_SOURCE_B_URL), request.sources.map { it.uri })
        assertEquals("the chosen source remains the request default", PLAY_MIRROR_SOURCE_B_URL, selected.uri)
        val selectedUri = java.net.URI(selected.uri)
        assertEquals("the selected source uses HTTPS", "https", selectedUri.scheme)
        assertEquals("the selected source host is the IPv4 loopback address", "127.0.0.1",
            selectedUri.host)
        assertTrue("the selected source address is loopback",
            java.net.InetAddress.getByName(selectedUri.host).isLoopbackAddress)
        assertEquals("the selected source uses the synthetic loopback port", PLAY_MIRROR_SOURCE_PORT,
            selectedUri.port)
        assertNull("the selected source has no query string", selectedUri.rawQuery)
        assertEquals("the request labels the selected provider source and quality",
            "$PROVIDER_NAME · 1080p · MP4", selected.label)
        assertEquals("the selected direct video retains its MP4 MIME type", "video/mp4",
            selected.mimeType)
        assertTrue("the metadata-only synthetic source carries no credentials or request headers",
            selected.headers.isEmpty())
        assertNull("the metadata-only fixture adds no real plugin playback hook",
            selected.cs3PlaybackHooks)

        assertEquals("media identity survives the Play mirror handoff",
            loadedDetailsItem.stableId, request.media.stableId)
        assertEquals("provider metadata stays attached to the selected source",
            "$PROVIDER_ID_PREFIX$pluginId:provider-0", request.media.providerId)
        assertEquals("the loaded Details item retains the fixture title", TITLE, loadedDetailsItem.title)
        assertEquals("the selected source retains the loaded Details title",
            loadedDetailsItem.title, request.media.title)
        assertEquals("the loaded Details item retains the fixture media kind", MediaKind.MOVIE,
            loadedDetailsItem.kind)
        assertEquals("the selected source retains the fixture movie type", "Movie",
            request.media.mediaType)
        assertEquals("the loaded Details item retains the fixture year", "2026", loadedDetailsItem.year)
        assertEquals("the selected source retains the loaded Details year",
            loadedDetailsItem.year, request.media.year)
        assertEquals("the loaded Details item retains the exact MovieLoadResponse plot",
            "Synthetic chooser details", loadedDetailsItem.overview)
        assertEquals("the selected source retains the loaded Details plot",
            loadedDetailsItem.overview, request.media.summary)
        recordDialogEvidence("playMirror=OrbitPlayerActivity source=${selected.id} " +
            "url=${selected.uri} label=${selected.label} mime=${selected.mimeType} " +
            "media=${request.media.stableId}/${request.media.title}/${request.media.mediaType}/${request.media.year}")
        assertEquals("Details resolves the synthetic links once", 1, fixtureApi.linkCalls)
    }

    @Test
    fun detailsOpenWithPlayMirrorRoutesMagnetToPeerConsent() {
        fixtureApi.sourceBUrl = BIG_BUCK_BUNNY_MAGNET
        val chooserOptions = openDetailsAndShowChooser()
        assertTrue("the actual Open with chooser exposes Play mirror for a magnet source",
            PLAY_MIRROR_LABEL in chooserOptions)

        clickDialogText(PLAY_MIRROR_LABEL)
        await("Play mirror lists the magnet among its supported sources") {
            visibleDialogTexts("Choose source") != null
        }
        assertDialogRows("Choose source", listOf(
            "$SOURCE_B_LABEL · 1080p", "$SOURCE_A_LABEL · 360p"))
        clickDialogText("$SOURCE_B_LABEL · 1080p")

        await("selecting the CS3 magnet reaches Orbit's peer-consent gate") {
            visibleDialogTexts("Peer-to-peer playback") != null
        }
        val consentTexts = visibleDialogTexts("Peer-to-peer playback").orEmpty()
        assertTrue("the consent gate explains the peer traffic being authorized",
            consentTexts.any { it.contains("network address") && it.contains("upload pieces") })
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        await("declining consent closes the torrent handoff") {
            visibleDialogTexts("Peer-to-peer playback") == null
        }
        recordDialogEvidence("magnet=${BIG_BUCK_BUNNY_MAGNET.substringBefore('&')} " +
            "chooser=${PLAY_MIRROR_LABEL} selected=$SOURCE_B_LABEL consent=shown decision=cancel")
    }

    @Test
    fun detailsOpenWithPlayMirrorOmitsMalformedMagnetsButKeepsHttpSource() {
        fixtureApi.sourceBUrl = INVALID_BTIH_MAGNET
        fixtureApi.additionalMagnetUrls = listOf(
            MALFORMED_MAGNET,
            "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&index=-1",
            "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&index=1000001",
            "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&index=0&index=1")
        val chooserOptions = openDetailsAndShowChooser()
        assertTrue("the valid HTTP source keeps Play mirror available",
            PLAY_MIRROR_LABEL in chooserOptions)

        clickDialogText(PLAY_MIRROR_LABEL)
        await("Play mirror opens its source chooser with the valid HTTP candidate") {
            visibleDialogTexts("Choose source") != null
        }
        assertDialogRows("Choose source", listOf("$SOURCE_A_LABEL · 360p"))
        recordDialogEvidence("malformedMagnets=omitted httpSource=$SOURCE_A_LABEL retained")
    }

    private fun openDetailsAndShowChooser(): List<String> {
        val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
        val providers = application.getMediaProvidersForProfile(profileId)
        assertEquals("only the synthetic provider is available to this isolated profile", 1, providers.size)
        val provider = providers.single()
        assertEquals(providerId, provider.descriptor().id)
        assertEquals(PROVIDER_NAME, provider.descriptor().name)
        assertTrue("the fake registration is exposed through the real provider registry",
            hostServices.committedProviderRegistrations.any {
                it.pluginId == pluginId && it.provider === fixtureApi && it.registrationIndex == 0
            })
        val installed = extensionManager.getInstalledPlugin(pluginId)
            ?: throw AssertionError("the metadata-only fixture must be visible to provider registry")
        assertEquals("the persisted metadata belongs to the exact fixture ID", pluginId, installed.id)
        assertTrue("the exact temporary profile reads back the fixture grant",
            extensionManager.getEnabledPluginIds(profileId).contains(pluginId))
        assertFalse("the registration uses no installed or downloaded .cs3 bytes",
            File(installed.filePath).exists())

        val titleSeed = MediaItem("synthetic-chooser-title", TITLE, MediaKind.MOVIE,
            TITLE_URL, "", "2026", "Synthetic Details chooser fixture", "")
        instrumentation.runOnMainSync {
            val method = OrbitShellActivity::class.java.getDeclaredMethod(
                "showProviderTitleDetails", MediaProvider::class.java, MediaItem::class.java,
                Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
            method.invoke(shell, provider, titleSeed, false, "provider_details_back")
        }
        await("real provider Details to load its synthetic movie") {
            readField(shell, "providerDetailsItem") is MediaItem
                && readField(shell, "providerDetailsLoading") == false
                && findTagged(shell, "provider_details_play") != null
        }
        assertEquals("Details calls the synthetic MainAPI.load with the fixture item URL",
            listOf(TITLE_URL), fixtureApi.detailsRequests)
        assertEquals("the actual Details screen displays the synthetic title", TITLE,
            (findTagged(shell, "provider_details_title") as? TextView)?.text?.toString())

        val play = findTagged(shell, "provider_details_play")
            ?: throw AssertionError("the actual provider Details screen exposes Play")
        instrumentation.runOnMainSync { assertTrue("the Play interaction resolves provider links", play.performClick()) }
        val expectedLinkCount = 2 + fixtureApi.additionalMagnetUrls.size
        await("the synthetic MainAPI.loadLinks callback and Details batch to complete") {
            fixtureApi.linkCalls == 1 && fixtureApi.linkCallbackCount == expectedLinkCount
                && readField(shell, "providerDetailsLinksLoaded") == true
        }
        assertEquals("Details link resolution uses the opaque MovieLoadResponse dataUrl",
            listOf(MOVIE_DATA), fixtureApi.linkRequestData)
        assertEquals("the fixture callback returns its exact synthetic link count", expectedLinkCount,
            fixtureApi.linkCallbackCount)
        val actionContext = readField(shell, "providerActionContext") as? Cs3ActionContext
        val actionContextPresent = actionContext != null
        assertNotNull("the provider callback must retain the real Cs3ActionContext", actionContext)
        assertEquals("the action context carries each original CS3 callback link", expectedLinkCount,
            actionContext!!.result.links.size)
        assertEquals("the action context is owned by the synthetic profile grant", pluginId,
            actionContext.pluginId)
        assertEquals("the action context is bound to the active fixture profile", profileId,
            actionContext.profileId)
        val availableActions = OrbitShellActivity::class.java
            .getDeclaredMethod("availableProviderVideoActions").apply { isAccessible = true }
            .invoke(shell) as? List<VideoClickAction>
        assertNotNull("the production provider action filter returns a list", availableActions)
        assertTrue("both committed fixture actions pass the production shouldShow/source-type filter",
            availableActions.orEmpty().any { it === multiSourceAction }
                && availableActions.orEmpty().any { it === oneSourceAction })

        val nativeOption = (readField(shell, "providerPlayableCandidates") as? List<*>)
            ?.isNotEmpty() == true
        val chooserOptions = buildList {
            if (nativeOption) add(ORBIT_PLAYER_LABEL)
            for (action in availableActions.orEmpty()) {
                val label = try { action.name.asString(target) }
                catch (_: Throwable) { "Provider action" }
                add(label.ifEmpty { "Provider action" })
            }
        }
        val providerStatus = readField(shell, "providerDetailsStatus")
        val availableWindows = visibleAccessibilityText()
        val chooserDiagnostic = "linksLoaded=${readField(shell, "providerDetailsLinksLoaded")}, " +
            "actionContextPresent=$actionContextPresent, availableActions=${availableActions?.size}, " +
            "playableCandidates=${(readField(shell, "providerPlayableCandidates") as? List<*>)?.size}, " +
            "providerStatus=$providerStatus, windowTexts=$availableWindows"
        await("the real Open with dialog to appear ($chooserDiagnostic)") {
            visibleDialogTexts("Open with") != null
        }
        assertDialogRows("Open with", chooserOptions)
        assertTrue("the first real dialog title is visible", visibleDialogTexts("Open with")!!.contains("Open with"))
        return chooserOptions
    }

    private fun registerSyntheticProviderAndActions() {
        val installed = JSONArray(extensionPreferences.getString(INSTALLED_KEY, "[]"))
        for (index in 0 until installed.length()) {
            assertFalse("synthetic plugin ID collision is unsafe",
                pluginId == installed.optJSONObject(index)?.optString("id"))
        }
        val metadata = JSONObject()
            .put("id", pluginId)
            .put("repositoryUrl", FIXTURE_REPOSITORY_URL)
            .put("name", PROVIDER_NAME)
            .put("internalName", PROVIDER_NAME)
            .put("version", 1L)
            .put("sha256", "0".repeat(64))
            .put("hashWasPublished", false)
            .put("pluginClassName", "fixture.OrbitChooserNeverLoaded")
            .put("requiresResources", false)
            .put("fileName", "video-action-chooser.fixture")
            .put("activationError", JSONObject.NULL)
            .put("installedAt", 1L)
        installed.put(metadata)
        val enabled = JSONObject(extensionPreferences.getString(ENABLED_KEY, "{}") ?: "{}")
        val enabledIds = enabled.optJSONArray(profileId) ?: JSONArray()
        for (index in 0 until enabledIds.length()) {
            assertFalse("synthetic grant must not duplicate a plugin ID", pluginId == enabledIds.optString(index))
        }
        enabledIds.put(pluginId)
        enabled.put(profileId, enabledIds)
        assertTrue("metadata-only plugin metadata and disposable-profile grant are stored",
            extensionPreferences.edit().putString(INSTALLED_KEY, installed.toString())
                .putString(ENABLED_KEY, enabled.toString()).commit())
        val fixtureGrantEnabled = extensionManager.isEnabledForProfile(profileId, pluginId)
        val enabledPlugins = extensionManager.getEnabledPluginIds(profileId)
        val installedPlugins = extensionManager.getInstalledPlugins().map { it.id }
        assertTrue("the fixture grant is limited to the temporary profile " +
            "(enabled=$enabledPlugins, installed=$installedPlugins)", fixtureGrantEnabled)
        assertTrue("the metadata-only fixture is readable through the extension manager",
            extensionManager.getInstalledPlugin(pluginId)?.id == pluginId)
        assertTrue("the exact temporary profile reads back the synthetic plugin grant",
            extensionManager.getEnabledPluginIds(profileId).contains(pluginId))

        val providerId = "$PROVIDER_ID_PREFIX$pluginId:provider-0"
        application.providerSelectionStore.setOrderedProviderIds(profileId, listOf(providerId))
        application.providerSelectionStore.setSelectedProviderIds(profileId, listOf(providerId), listOf(providerId))

        val fixturePlugin = FixturePlugin()
        transaction = hostServices.beginPluginLoad(pluginId, registrationMarker.absolutePath, fixturePlugin)
        fixturePlugin.registerMainAPI(fixtureApi)
        fixturePlugin.registerVideoClickAction(multiSourceAction)
        fixturePlugin.registerVideoClickAction(oneSourceAction)
        transaction!!.commit()
        assertEquals("synthetic MainAPI metadata is ready before taking a provider snapshot",
            Cs3ProviderRegistry.MetadataReadiness.READY,
            application.cs3ProviderRegistry.awaitProviderMetadata(profileId, pluginId, 0, UI_TIMEOUT_MS))
        assertTrue("the two action rows are committed before chooser interaction",
            VideoClickActionHolder.allVideoClickActions.contains(multiSourceAction)
                && VideoClickActionHolder.allVideoClickActions.contains(oneSourceAction))
    }

    private fun continueIntoOrbitHome() {
        await("the shell profile confirmation or Home navigation") {
            findTagged(shell, "nav_HOME") != null || findText(shell, "Continue") != null
        }
        val continueButton = findText(shell, "Continue")
        if (continueButton != null) instrumentation.runOnMainSync {
            assertTrue("the temporary profile continues into the actual Orbit shell",
                continueButton.performClick())
        }
        instrumentation.runOnMainSync {
            shellState(shell).navigateTo(ShellRoute.HOME)
            invokeRender(shell)
        }
        await("the actual Orbit Home screen") {
            findTagged(shell, "nav_HOME") != null && shellState(shell).route == ShellRoute.HOME
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(40L)
        }
        throw AssertionError("timed out waiting for $description")
    }

    private fun assertDialogRows(title: String, expectedRows: List<String>) {
        val texts = visibleDialogTexts(title)
            ?: throw AssertionError("the $title dialog is not visible in the accessibility windows")
        val rows = texts.filter { it != title && !it.equals(CANCEL_LABEL, ignoreCase = true) }
        assertEquals("$title displays the exact expected option rows", expectedRows, rows)
        assertTrue("$title exposes its Cancel control", texts.any { it.equals(CANCEL_LABEL, ignoreCase = true) })
        recordDialogEvidence("dialog=$title texts=$texts options=$rows")
    }

    private fun visibleDialogTexts(title: String): List<String>? {
        for (root in accessibilityRoots()) {
            val texts = ArrayList<String>()
            collectAccessibilityText(root, texts)
            if (texts.contains(title)) return texts
        }
        return null
    }

    private fun visibleAccessibilityText(): List<List<String>> = accessibilityRoots().map { root ->
        ArrayList<String>().also { collectAccessibilityText(root, it) }
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

    private fun recordDialogEvidence(line: String) {
        val entry = "$line\n"
        dialogEvidenceFile.appendText(entry, Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply { putString("chooser_evidence", line) })
    }

    private fun findAccessibilityTextNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString()?.trim() == text) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findAccessibilityTextNode(child, text)?.let { return it }
        }
        return null
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

    private fun shellState(activity: OrbitShellActivity?): ShellState {
        val owner = activity
            ?: throw AssertionError("the OrbitShellActivity instance is unavailable")
        val field = OrbitShellActivity::class.java.getDeclaredField("shellState")
            .apply { isAccessible = true }
        return field.get(owner) as? ShellState
            ?: throw AssertionError("the OrbitShellActivity shellState is unavailable")
    }

    private fun invokeRender(activity: OrbitShellActivity) {
        OrbitShellActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    }

    private fun closeProviderTitleDetails(activity: OrbitShellActivity) {
        OrbitShellActivity::class.java.getDeclaredMethod("closeProviderTitleDetails", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
    }

    private fun readField(instance: Any, name: String): Any? = findField(instance.javaClass, name).get(instance)

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field {
        var cursor: Class<*>? = type
        while (cursor != null) {
            try { return cursor.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { cursor = cursor.superclass }
        }
        throw NoSuchFieldException(name)
    }

    private fun snapshotPreferences(name: String, preferences: SharedPreferences): PreferenceSnapshot {
        val file = File(target.applicationInfo.dataDir, "shared_prefs/$name.xml")
        return PreferenceSnapshot(name, preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }, file.isFile, if (file.isFile) file.readBytes() else null)
    }

    private fun restorePreferences(snapshot: PreferenceSnapshot, preferences: SharedPreferences) {
        val editor = preferences.edit().clear()
        for ((key, value) in snapshot.values) when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> throw AssertionError("unsupported preference type ${value.javaClass.name} for ${snapshot.name}")
        }
        assertTrue("${snapshot.name} preferences restore synchronously", editor.commit())
        val file = File(target.applicationInfo.dataDir, "shared_prefs/${snapshot.name}.xml")
        if (snapshot.fileExisted) {
            FileOutputStream(file, false).use { output ->
                output.write(snapshot.fileBytes!!)
                output.flush()
                output.fd.sync()
            }
        } else {
            assertTrue("previously absent ${snapshot.name} preferences are removed",
                application.deleteSharedPreferences(snapshot.name))
        }
        assertSnapshotRestored(snapshot, preferences)
    }

    private fun assertSnapshotRestored(snapshot: PreferenceSnapshot, preferences: SharedPreferences) {
        assertEquals("${snapshot.name} values match the exact pre-test snapshot", snapshot.values, preferences.all)
        val file = File(target.applicationInfo.dataDir, "shared_prefs/${snapshot.name}.xml")
        assertEquals("${snapshot.name} file existence is restored", snapshot.fileExisted, file.isFile)
        if (snapshot.fileExisted) assertTrue("${snapshot.name} raw XML bytes are restored",
            snapshot.fileBytes!!.contentEquals(file.readBytes()))
    }

    private fun assertSameIdentityOrder(message: String, expected: List<*>, actual: List<*>) {
        assertEquals(message, expected.size, actual.size)
        expected.indices.forEach { index -> assertSame(message, expected[index], actual[index]) }
    }

    private data class PreferenceSnapshot(
        val name: String,
        val values: Map<String, Any?>,
        val fileExisted: Boolean,
        val fileBytes: ByteArray?,
    )

    private data class Invocation(
        val index: Int?,
        val links: List<String>,
        val videoData: String,
    )

    private class FixturePlugin : Plugin()

    private class FixtureAction(
        val label: String,
        override val oneSource: Boolean,
    ) : VideoClickAction() {
        override val name: UiText = UiText.DynamicString(label)
        val invoked = CountDownLatch(1)
        val lastInvocation = AtomicReference<Invocation?>()

        override fun shouldShow(context: Context?, video: ResultEpisode?) = true

        override suspend fun runAction(context: Context?, video: ResultEpisode,
                                       result: LinkLoadingResult, index: Int?) {
            lastInvocation.set(Invocation(index, result.links.map { it.url }, video.data))
            invoked.countDown()
        }
    }

    private class FixtureMainApi : MainAPI() {
        val detailsRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val linkRequestData = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var sourceBUrl = SOURCE_B_URL
        @Volatile var additionalMagnetUrls: List<String> = emptyList()
        @Volatile var linkCalls = 0
        @Volatile var linkCallbackCount = 0

        override fun getName(): String = PROVIDER_NAME
        override fun getMainUrl(): String = FIXTURE_PROVIDER_URL
        override fun getSupportedTypes(): Set<TvType> = setOf(TvType.Movie)
        override fun getHasMainPage(): Boolean = false

        override suspend fun load(url: String): LoadResponse {
            detailsRequests.add(url)
            check(url == TITLE_URL) { "synthetic fixture received an unexpected details URL" }
            return MovieLoadResponse(TITLE, TITLE_URL, PROVIDER_NAME, TvType.Movie,
                MOVIE_DATA, null, 2026, "Synthetic chooser details", null,
                listOf("Fixture"), 90, arrayListOf<com.lagradost.cloudstream3.TrailerData>(), emptyList(), emptyList(),
                false, linkedMapOf(), null, null, null, "PG", TITLE_URL)
        }

        override suspend fun loadLinks(data: String, isCasting: Boolean,
                                       subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
                                       callback: (ExtractorLink) -> Unit): Boolean {
            check(data == MOVIE_DATA) { "synthetic link callback received unexpected provider data" }
            check(!isCasting) { "the chooser fixture should not use casting" }
            linkCalls++
            linkRequestData.add(data)
            callback(ExtractorLink("Synthetic fixture", SOURCE_A_LABEL, SOURCE_A_URL, "", 360,
                false, emptyMap(), null))
            linkCallbackCount++
            callback(ExtractorLink("Synthetic fixture", SOURCE_B_LABEL, sourceBUrl, "", 1080,
                false, emptyMap(), null))
            linkCallbackCount++
            additionalMagnetUrls.forEachIndexed { index, url ->
                callback(ExtractorLink("Synthetic fixture", "Invalid magnet ${index + 1}", url,
                    "", 720 - index, false, emptyMap(), null))
                linkCallbackCount++
            }
            return true
        }
    }

    companion object {
        private val extensionPreferencesLock = Any()
        private var cachedExtensionPreferences: SharedPreferences? = null

        private fun extensionPreferencesFor(application: OrbitApplication): SharedPreferences =
            synchronized(extensionPreferencesLock) {
                cachedExtensionPreferences ?: application.getSharedPreferences(
                    EXTENSION_PREFERENCES, Context.MODE_PRIVATE,
                ).also { cachedExtensionPreferences = it }
            }

        private const val UI_TIMEOUT_MS = 15_000L
        private const val ACTION_CALLBACK_TIMEOUT_MS = 10_000L
        private const val PROVIDER_DRAIN_TIMEOUT_MS = 15_000L
        private const val PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1"
        private const val EXTENSION_PREFERENCES = "orbit_cloudstream_extensions_v1"
        private const val PROVIDER_PREFERENCES = "orbit_provider_selection_v1"
        private const val INSTALLED_KEY = "installed_plugins_json"
        private const val ENABLED_KEY = "profile_enabled_plugins_json"
        private const val PROVIDER_ID_PREFIX = "cs3:"
        private const val PROVIDER_NAME = "Orbit Video Action Chooser Fixture"
        private const val FIXTURE_REPOSITORY_URL = "https://open-with.invalid/repository.json"
        private const val FIXTURE_PROVIDER_URL = "https://open-with.invalid"
        private const val TITLE_URL = "https://open-with.invalid/title/chooser-fixture"
        private const val TITLE = "Synthetic Open With Fixture"
        private const val MOVIE_DATA = "synthetic-open-with-provider-data"
        private const val SOURCE_A_URL = "https://open-with.invalid/sources/fixture-a.mp4"
        private const val SOURCE_B_URL = "https://open-with.invalid/sources/fixture-b.mp4"
        private const val MALFORMED_MAGNET = "magnet:?"
        private const val INVALID_BTIH_MAGNET = "magnet:?xt=urn:btih:not-a-valid-hash"
        private const val PLAY_MIRROR_SOURCE_PORT = 65000
        private const val PLAY_MIRROR_SOURCE_B_URL = "https://127.0.0.1:65000/sources/fixture-b.mp4"
        private const val BIG_BUCK_BUNNY_MAGNET =
            "magnet:?xt=urn:btih:DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C&dn=Big%20Buck%20Bunny&index=0"
        private const val SOURCE_A_LABEL = "Fixture Source A"
        private const val SOURCE_B_LABEL = "Fixture Source B"
        private const val PLAY_MIRROR_LABEL = "Play mirror"
        private const val ORBIT_PLAYER_LABEL = "Orbit Player"
        private const val CANCEL_LABEL = "Cancel"
        private const val DIALOG_EVIDENCE_FILE = "video-action-chooser-dialog-evidence.txt"
    }
}
