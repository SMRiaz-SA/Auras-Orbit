package com.auras.orbit.actions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auras.orbit.BuildConfig
import com.auras.orbit.OrbitApplication
import com.auras.orbit.navigation.ShellRoute
import com.auras.orbit.navigation.ShellState
import com.auras.orbit.providers.MediaItem
import com.auras.orbit.providers.MediaKind
import com.auras.orbit.providers.MediaProvider
import com.auras.orbit.providers.ProviderDescriptor
import com.auras.orbit.ui.OrbitShellActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.OrbitVideoActionRuntime
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.makeTempM3U8Intent
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.UiText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.lang.reflect.Constructor
import java.util.Collections
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic-only acceptance of the Orbit action registry and canceled player-result boundary. */
@RunWith(AndroidJUnit4::class)
class VideoClickActionAcceptanceInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var target: Context
    private lateinit var application: OrbitApplication
    private lateinit var fixtureRoot: File
    private lateinit var profilePreferences: SharedPreferences
    private var profilePreferenceSnapshot: Map<String, *> = emptyMap<String, Any>()
    private lateinit var providerSelectionPreferences: SharedPreferences
    private var providerSelectionPreferenceSnapshot: Map<String, *> = emptyMap<String, Any>()
    private lateinit var providerSelectionFile: File
    private var providerSelectionFileExisted = false
    private var providerSelectionFileSnapshot: ByteArray? = null
    private lateinit var providerSelectionBackupFile: File
    private var providerSelectionBackupFileExisted = false
    private var providerSelectionBackupFileSnapshot: ByteArray? = null
    private var originalActiveProfileId: String? = null
    private var shell: OrbitShellActivity? = null
    private var host: com.auras.orbit.extensions.OrbitCs3HostServices? = null
    private var initialActionOrder: List<VideoClickAction> = emptyList()
    private val fixturePluginIds = ArrayList<String>()
    private val fixtureFiles = ArrayList<File>()
    private val fixtureTransactions = ArrayList<com.auras.orbit.extensions.OrbitCs3HostServices.LoadTransaction>()
    private var playbackFixtureProfileId: String? = null
    private var playbackFixtureStableId: String? = null

    @Before
    fun attachToNormalDebugOrbit() {
        target = instrumentation.targetContext
        assertTrue("action acceptance must use the isolated side-by-side .debug target",
            target.packageName == "com.auras.orbit.debug")
        assertFalse("action acceptance must use normal Debug with CS3 activation closed",
            BuildConfig.CS3_CANARY_BUILD)
        application = target.applicationContext as? OrbitApplication
            ?: throw AssertionError("the explicit instrumentation gate must start OrbitApplication")
        assertTrue("the accepted CS3 plugin crosswalk must be visible in the product runtime",
            application.cS3PluginRuntime.isTargetPluginCrosswalkValidated)
        providerSelectionFile = File(target.applicationInfo.dataDir,
            "shared_prefs/$PROVIDER_SELECTION_PREFERENCES.xml")
        providerSelectionFileExisted = providerSelectionFile.isFile
        providerSelectionFileSnapshot = if (providerSelectionFileExisted) {
            providerSelectionFile.readBytes()
        } else null
        providerSelectionBackupFile = File(providerSelectionFile.path + ".bak")
        providerSelectionBackupFileExisted = providerSelectionBackupFile.isFile
        providerSelectionBackupFileSnapshot = if (providerSelectionBackupFileExisted) {
            providerSelectionBackupFile.readBytes()
        } else null
        providerSelectionPreferences = target.getSharedPreferences(
            PROVIDER_SELECTION_PREFERENCES, Context.MODE_PRIVATE)
        providerSelectionPreferenceSnapshot = HashMap(providerSelectionPreferences.all)
        profilePreferences = target.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
        profilePreferenceSnapshot = HashMap(profilePreferences.all)
        originalActiveProfileId = profilePreferences.getString(ACTIVE_PROFILE_KEY, "")
        val profile = application.localProfileStore.createProfile(
            "Temporary action acceptance ${UUID.randomUUID()}")
            ?: throw AssertionError("the isolated fixture profile could not be created")
        application.localProfileStore.selectProfile(profile.id)
        assertEquals("the disposable profile is active only inside this instrumentation run",
            profile.id, application.localProfileStore.activeProfile?.id)
        host = com.auras.orbit.extensions.OrbitCs3HostServices.get()
        initialActionOrder = VideoClickActionHolder.allVideoClickActions.toList()
        fixtureRoot = File(target.cacheDir, "video-action-acceptance-${UUID.randomUUID()}")
        assertTrue("fixture files stay in this app's cache directory",
            fixtureRoot.mkdirs() && fixtureRoot.canonicalPath.startsWith(target.cacheDir.canonicalPath))
    }

    @After
    fun removeOnlySyntheticStateAndLeaveHome() {
        val services = host
        if (services != null) {
            fixtureTransactions.asReversed().forEach { transaction ->
                try { transaction.rollback() } catch (_: Throwable) { }
            }
            fixturePluginIds.asReversed().forEach { pluginId ->
                try { services.unloadPlugin(pluginId) } catch (_: Throwable) { }
            }
        }
        val profileId = playbackFixtureProfileId
        val stableId = playbackFixtureStableId
        var playbackCleanupFailure: Throwable? = null
        if (profileId != null && stableId != null && ::application.isInitialized) {
            try {
                application.localLibraryStore.writableDatabase.delete(
                    "saved_titles", "profile_id=? AND stable_id=?", arrayOf(profileId, stableId))
                application.localLibraryStore.writableDatabase.delete(
                    "playback_progress", "profile_id=? AND stable_id=?", arrayOf(profileId, stableId))
            } catch (failure: Throwable) {
                playbackCleanupFailure = failure
            }
        }
        if (::profilePreferences.isInitialized) restoreProfilePreferences()
        fixtureFiles.forEach { try { it.delete() } catch (_: Throwable) { } }
        if (::fixtureRoot.isInitialized) fixtureRoot.deleteRecursively()
        val activity = shell
        if (activity != null && !activity.isDestroyed) {
            try {
                instrumentation.runOnMainSync {
                    shellState(activity).navigateTo(ShellRoute.HOME)
                    invokeRender(activity)
                }
                instrumentation.waitForIdleSync()
            } catch (_: Throwable) { }
        }
        if (::providerSelectionPreferences.isInitialized) restoreProviderSelectionPreferences()
        if (profileId != null && stableId != null && ::application.isInitialized) {
            playbackCleanupFailure?.let {
                throw AssertionError("the synthetic playback rows must be removed", it)
            }
            assertNoLibraryRow(profileId, stableId)
        }
        if (services != null) {
            val actual = VideoClickActionHolder.allVideoClickActions.toList()
            assertSameIdentityOrder("all action fixtures must be removed and prior registry order restored",
                initialActionOrder, actual)
        }
    }

    @Test
    fun holderKeepsPinnedBuiltinSlotsAndReservesPluginActionIds() {
        val expectedTypes = listOf(
            "com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction",
            "com.lagradost.cloudstream3.actions.temp.CopyClipboardAction",
            "com.lagradost.cloudstream3.actions.temp.ViewM3U8Action",
            "com.lagradost.cloudstream3.actions.temp.PlayMirrorAction",
            "com.lagradost.cloudstream3.actions.temp.VlcPackage",
            "com.lagradost.cloudstream3.actions.temp.MpvPackage",
            "com.lagradost.cloudstream3.actions.temp.MpvExPackage",
            "com.lagradost.cloudstream3.actions.temp.NextPlayerPackage",
            "com.lagradost.cloudstream3.actions.temp.JustPlayerPackage",
            "com.lagradost.cloudstream3.actions.temp.fcast.FcastAction",
            "com.lagradost.cloudstream3.actions.temp.LibreTorrentPackage",
            "com.lagradost.cloudstream3.actions.temp.BiglyBTPackage",
            "com.lagradost.cloudstream3.actions.temp.VlcNightlyPackage",
            "com.lagradost.cloudstream3.actions.temp.WebVideoCastPackage",
            "com.lagradost.cloudstream3.actions.temp.MpvYTDLPackage",
            "com.lagradost.cloudstream3.actions.temp.MpvKtPackage",
            "com.lagradost.cloudstream3.actions.temp.MpvKtPreviewPackage",
            "com.lagradost.cloudstream3.actions.temp.OnlyPlayer",
            "com.lagradost.cloudstream3.actions.temp.MpvRxPackage",
            "com.lagradost.cloudstream3.actions.AlwaysAskAction",
        )
        val actions = VideoClickActionHolder.allVideoClickActions.toList()

        assertEquals("the host reserves the exact pinned built-in action slots", expectedTypes,
            actions.map { it.javaClass.name })
        actions.forEach { action ->
            assertTrue("the exact seeded action identity is Orbit-owned", 
                VideoClickActionHolder.isOrbitOwnedBuiltinAction(action))
        }
        assertFalse("an ownerless-looking action is not trusted by metadata alone",
            VideoClickActionHolder.isOrbitOwnedBuiltinAction(FixtureAction("lookalike built-in")))
        expectedTypes.indices.forEach { index ->
            assertSame("ID ${1000 + index} resolves to its pinned built-in slot",
                actions[index], VideoClickActionHolder.getActionById(1000 + index))
            assertEquals("uniqueId maps back to its pinned slot", 1000 + index,
                VideoClickActionHolder.uniqueIdToId(actions[index].uniqueId()))
        }

        val visibleIds = VideoClickActionHolder.makeOptionMap(shell, testVideo()).map { it.second }
        assertTrue("the supported Browser slot remains visible", visibleIds.contains(1000))
        assertTrue("the supported clipboard slot remains visible", visibleIds.contains(1001))
        assertTrue("the supported M3U8 slot remains visible", visibleIds.contains(1002))
        assertFalse("PlayMirror stays hidden without its unsupported GeneratorPlayer host",
            actions[3].shouldShowSafe(shell, testVideo()))
        assertFalse("Fcast stays hidden without Orbit's cast-discovery host",
            actions[9].shouldShowSafe(shell, testVideo()))
        assertFalse("Always Ask stays hidden without a profile-scoped default caller",
            actions[19].shouldShowSafe(shell, testVideo()))
        assertFalse("Always Ask is also hidden outside a profile-scoped caller",
            actions[19].shouldShowSafe(shell, null))
    }

    @Test
    fun alwaysAskRequiresProfileScopedCallerContext() {
        val action = VideoClickActionHolder.getActionById(1019)
            ?: throw AssertionError("the pinned Always Ask slot must be present")
        assertFalse("Always Ask is hidden without a caller context",
            action.shouldShowSafe(null, null))

        val activity = launchShellOnHome()
        assertTrue("Always Ask is visible for the profile-scoped Orbit shell with no video",
            action.shouldShowSafe(activity, null))
        assertFalse("Always Ask stays hidden once a video is selected",
            action.shouldShowSafe(activity, testVideo()))
    }

    @Test
    fun manifestSerializerHandlesDefaultsAndExplicitFields() {
        val json = Json
        val serializer = BasePlugin.Manifest.serializer()
        val defaults = json.decodeFromString(serializer, "{}")
        assertNull(defaults.name)
        assertNull(defaults.pluginClassName)
        assertFalse(defaults.requiresResources)
        assertNull(defaults.version)
        assertEquals("{}", json.encodeToString(serializer, BasePlugin.Manifest()))

        val explicit = json.decodeFromString(serializer,
            """{"name":"Orbit Fixture","pluginClassName":"fixture.SyntheticPlugin","requiresResources":true,"version":47}""")
        assertEquals("Orbit Fixture", explicit.name)
        assertEquals("fixture.SyntheticPlugin", explicit.pluginClassName)
        assertTrue(explicit.requiresResources)
        assertEquals(47, explicit.version)
        assertEquals(
            """{"name":"Orbit Fixture","pluginClassName":"fixture.SyntheticPlugin","requiresResources":true,"version":47}""",
            json.encodeToString(serializer, explicit))
    }

    @Test
    fun ownerlessBuiltinActionCannotBeRemovedOutsideHostMutation() {
        val isolatedRegistry = com.auras.orbit.extensions.OrbitCs3HostServices.newVideoActionRegistry()
        val builtinAction = FixtureAction("Synthetic ownerless built-in")
        assertTrue(isolatedRegistry.add(builtinAction))
        assertTrue(isolatedRegistry.any { it === builtinAction })

        expectActionRemovalRejected("an ownerless built-in") {
            isolatedRegistry.remove(builtinAction)
        }

        assertTrue("the rejected callback removal preserves the ownerless built-in",
            isolatedRegistry.any { it === builtinAction })
    }

    @Test
    fun activePluginCannotSortAnotherOwnersActions() {
        val fixture = beginForeignPermutationFixture("sort")
        val desiredOrder = fixture.startingOrder.toMutableList()
        val firstIndex = desiredOrder.indexOfFirst { it === fixture.firstAction }
        val secondIndex = desiredOrder.indexOfFirst { it === fixture.secondAction }
        assertTrue(firstIndex >= 0 && secondIndex >= 0)
        Collections.swap(desiredOrder, firstIndex, secondIndex)
        val desiredRanks = java.util.IdentityHashMap<VideoClickAction, Int>()
        desiredOrder.forEachIndexed { index, action -> desiredRanks[action] = index }

        expectIllegalState("a plugin sort permutation of another owner's actions") {
            Collections.sort(fixture.registry, Comparator { left, right ->
                desiredRanks.getValue(left).compareTo(desiredRanks.getValue(right))
            })
        }

        assertSameIdentityOrder("rejected sort leaves every action at its original index",
            fixture.startingOrder, fixture.registry.toList())
        finishForeignPermutationFixture(fixture)
    }

    @Test
    fun activePluginCannotReplaceAllWithAnotherOwnersPermutation() {
        val fixture = beginForeignPermutationFixture("replace-all")

        expectIllegalState("a plugin replaceAll permutation of another owner's actions") {
            fixture.registry.replaceAll { action ->
                when {
                    action === fixture.firstAction -> fixture.secondAction
                    action === fixture.secondAction -> fixture.firstAction
                    else -> action
                }
            }
        }

        assertSameIdentityOrder("rejected replaceAll leaves every action at its original index",
            fixture.startingOrder, fixture.registry.toList())
        finishForeignPermutationFixture(fixture)
    }

    @Test
    fun samePathReloadSortKeepsPriorActionOwnershipUntilUnload() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val registry = VideoClickActionHolder.allVideoClickActions
        val packagePath = createSyntheticPackagePath("same-path-reload")
        val originalOwner = FixturePlugin()
        val first = FixtureAction("Reload first action")
        val second = FixtureAction("Reload second action")
        val originalPluginId = fixturePluginId("same-path-original")
        val originalLoad = beginPluginLoad(services, originalPluginId, packagePath.absolutePath, originalOwner)
        originalOwner.registerVideoClickAction(first)
        originalOwner.registerVideoClickAction(second)
        originalLoad.commit()
        fixtureTransactions.remove(originalLoad)

        val firstIndex = registry.indexOfFirst { it === first }
        val reloadOwner = FixturePlugin()
        val reloadPluginId = fixturePluginId("same-path-reload")
        val reload = beginPluginLoad(services, reloadPluginId, packagePath.absolutePath, reloadOwner)
        Collections.sort(registry.subList(firstIndex, firstIndex + 2), Comparator { left, right ->
            when {
                left === first && right === second -> 1
                left === second && right === first -> -1
                else -> 0
            }
        })
        assertSameIdentityOrder("sorting reorders the same two action identities",
            listOf(second, first), registry.subList(firstIndex, firstIndex + 2).toList())
        registry.replaceAll { action ->
            when {
                action === first -> second
                action === second -> first
                else -> action
            }
        }
        assertSameIdentityOrder("same-path reload may replaceAll-permute its prior actions",
            listOf(first, second), registry.subList(firstIndex, firstIndex + 2).toList())
        val orderBeforeWholeSort = registry.toList()
        val expectedAfterWholeSort = orderBeforeWholeSort.toMutableList().apply {
            val firstPosition = indexOfFirst { it === first }
            val secondPosition = indexOfFirst { it === second }
            val displaced = this[firstPosition]
            this[firstPosition] = this[secondPosition]
            this[secondPosition] = displaced
        }
        val expectedSortPositions = java.util.IdentityHashMap<VideoClickAction, Int>()
        expectedAfterWholeSort.forEachIndexed { index, action -> expectedSortPositions[action] = index }
        Collections.sort(registry, Comparator { left, right ->
            expectedSortPositions.getValue(left).compareTo(expectedSortPositions.getValue(right))
        })
        assertSameIdentityOrder("whole-list sort only permutes action positions",
            expectedAfterWholeSort, registry.toList())
        reload.commit()
        fixtureTransactions.remove(reload)

        assertTrue("the original load retains ownership after same-path reordering",
            services.unloadPlugin(originalPluginId))
        fixturePluginIds.remove(originalPluginId)
        assertFalse("unloading the original owner removes both reordered actions",
            registry.any { it === first || it === second })
        assertTrue("the empty reload transaction unloads cleanly", services.unloadPlugin(reloadPluginId))
        fixturePluginIds.remove(reloadPluginId)
        assertSameIdentityOrder("same-path reload and unload restore the starting registry",
            initialActionOrder, registry.toList())
    }

    @Test
    fun replaceAllPreflightsBeforeALaterForeignOwnerRejection() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val registry = VideoClickActionHolder.allVideoClickActions
        val sharedPath = createSyntheticPackagePath("replace-all-same-owner")
        val owner = FixturePlugin()
        val ownedAction = FixtureAction("Replaceable same-owner action")
        val ownerId = fixturePluginId("replace-all-owner")
        val ownerLoad = beginPluginLoad(services, ownerId, sharedPath.absolutePath, owner)
        owner.registerVideoClickAction(ownedAction)
        ownerLoad.commit()
        fixtureTransactions.remove(ownerLoad)

        val foreignPath = createSyntheticPackagePath("replace-all-foreign-owner")
        val foreignOwner = FixturePlugin()
        val foreignAction = FixtureAction("Later foreign action")
        val foreignId = fixturePluginId("replace-all-foreign")
        val foreignLoad = beginPluginLoad(services, foreignId, foreignPath.absolutePath, foreignOwner)
        foreignOwner.registerVideoClickAction(foreignAction)
        foreignLoad.commit()
        fixtureTransactions.remove(foreignLoad)

        val before = registry.toList()
        val reloadOwner = FixturePlugin()
        val reloadId = fixturePluginId("replace-all-reload")
        val reload = beginPluginLoad(services, reloadId, sharedPath.absolutePath, reloadOwner)
        val sameOwnerReplacement = FixtureAction("Replacement that must not be applied early")
        val foreignReplacement = FixtureAction("Forbidden foreign replacement")
        expectIllegalState("replaceAll with a later foreign owner") {
            registry.replaceAll { action ->
                when (action) {
                    ownedAction -> sameOwnerReplacement
                    foreignAction -> foreignReplacement
                    else -> action
                }
            }
        }

        assertSameIdentityOrder("a later foreign-owner rejection preserves every earlier entry",
            before, registry.toList())
        assertFalse("same-owner changes are not staged before full replaceAll preflight",
            registry.any { it === sameOwnerReplacement })
        assertFalse("foreign changes are not staged before full replaceAll preflight",
            registry.any { it === foreignReplacement })

        reload.rollback()
        fixtureTransactions.remove(reload)
        fixturePluginIds.remove(reloadId)
        assertFalse("failed preflight leaves no outstanding reload transaction",
            services.hasOutstandingRegistrations(reloadId))
        assertTrue(services.unloadPlugin(ownerId))
        fixturePluginIds.remove(ownerId)
        assertTrue(services.unloadPlugin(foreignId))
        fixturePluginIds.remove(foreignId)
        assertSameIdentityOrder("bulk replacement cleanup restores the starting registry",
            initialActionOrder, registry.toList())
    }

    @Test
    fun actionRegistryStagesCommitsRollsBackAndRejectsForeignMutationViews() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val registry = VideoClickActionHolder.allVideoClickActions
        val actionA = FixtureAction("Committed fixture action")
        val ownerA = FixturePlugin()
        val pathA = createSyntheticPackagePath("owner-a")
        val pluginIdA = fixturePluginId("owner-a")
        val loadA = beginPluginLoad(services, pluginIdA, pathA.absolutePath, ownerA)
        ownerA.registerVideoClickAction(actionA)
        assertFalse("a staged action is hidden until transaction commit", registry.any { it === actionA })
        assertEquals("the action records the exact synthetic package path", pathA.absolutePath,
            actionA.sourcePlugin)
        loadA.commit()
        assertTrue("commit publishes the exact action identity", registry.any { it === actionA })
        assertTrue(services.hasCommittedRegistrations(pluginIdA))

        val optionMap = VideoClickActionHolder.makeOptionMap(shell, testVideo())
        val option = optionMap.single { it.first.asString(target) == "Committed fixture action" }
        assertEquals("action chooser IDs retain CloudStream's 1000 offset",
            VideoClickActionHolder.allVideoClickActions.indexOf(actionA) + 1000, option.second)
        assertEquals("plugin actions start after all 20 pinned built-ins", 1020, option.second)
        assertSame(actionA, VideoClickActionHolder.getActionById(option.second))
        assertEquals(option.second, VideoClickActionHolder.uniqueIdToId(actionA.uniqueId()))

        val invocationCount = AtomicInteger()
        actionA.onInvoke = { invocationCount.incrementAndGet() }
        runBlocking {
            withTimeout(5_000L) {
                actionA.runActionSafe(target, testVideo(), LinkLoadingResult(
                    emptyList(), emptyList(), HashMap()), null).join()
            }
        }
        assertEquals("the committed synthetic callback runs through the action runtime", 1,
            invocationCount.get())

        val indexA = registry.indexOfFirst { it === actionA }
        assertTrue(indexA >= 0)
        val preFailedLoadOrder = registry.toList()
        val samePathPlugin = FixturePlugin()
        val samePathAction = FixtureAction("Failed load action")
        val failedId = fixturePluginId("failed-load")
        val failed = beginPluginLoad(services, failedId, pathA.absolutePath, samePathPlugin)
        samePathPlugin.registerVideoClickAction(samePathAction)
        assertSame("rollback fixture removes only its package's committed action",
            actionA, registry.removeAt(indexA))
        failed.rollback()
        assertFalse("failed-load staged action never enters the live registry",
            registry.any { it === samePathAction })
        assertSameIdentityOrder("failed load restores the removed committed action at its prior position",
            preFailedLoadOrder, registry.toList())

        val actionC = FixtureAction("Second committed owner action")
        val ownerC = FixturePlugin()
        val pathC = createSyntheticPackagePath("owner-c")
        val pluginIdC = fixturePluginId("owner-c")
        val loadC = beginPluginLoad(services, pluginIdC, pathC.absolutePath, ownerC)
        ownerC.registerVideoClickAction(actionC)
        loadC.commit()
        fixtureTransactions.remove(loadC)
        val beforeForeignMutationsWithC = registry.toList()
        val pluginB = FixturePlugin()
        val actionB = FixtureAction("Other owner staged action")
        val pathB = createSyntheticPackagePath("owner-b")
        val pluginIdB = fixturePluginId("owner-b")
        val loadB = beginPluginLoad(services, pluginIdB, pathB.absolutePath, pluginB)
        pluginB.registerVideoClickAction(actionB)
        val beforeForeignMutations = registry.toList()
        fun rejectsForeignMutation(label: String, operation: () -> Unit) {
            expectIllegalState(label, operation)
            assertSameIdentityOrder("$label left all owner actions unchanged", beforeForeignMutations,
                registry.toList())
        }
        rejectsForeignMutation("remove") { registry.remove(actionA) }
        rejectsForeignMutation("set") { registry[indexA] = FixtureAction("Replacement") }
        rejectsForeignMutation("iterator.remove") {
            val iterator = registry.iterator()
            while (iterator.hasNext()) if (iterator.next() === actionA) {
                iterator.remove()
                return@rejectsForeignMutation
            }
            throw AssertionError("the committed owner action was absent from the iterator")
        }
        rejectsForeignMutation("listIterator.remove") {
            val iterator = registry.listIterator(indexA)
            iterator.next()
            iterator.remove()
        }
        rejectsForeignMutation("listIterator.set") {
            val iterator = registry.listIterator(indexA)
            iterator.next()
            iterator.set(FixtureAction("Iterator replacement"))
        }
        rejectsForeignMutation("subList.clear") { registry.subList(indexA, indexA + 1).clear() }
        rejectsForeignMutation("removeAll") { registry.removeAll(listOf(actionA)) }
        rejectsForeignMutation("retainAll") {
            registry.retainAll(beforeForeignMutations.filterNot { it === actionA })
        }
        rejectsForeignMutation("removeIf") { registry.removeIf { it === actionA } }
        rejectsForeignMutation("replaceAll") {
            registry.replaceAll { value -> if (value === actionA) FixtureAction("Bulk replacement") else value }
        }
        rejectsForeignMutation("sort") {
            Collections.sort(registry.subList(indexA, indexA + 2), Comparator { left, right ->
                when {
                    left === actionA && right === actionC -> 1
                    left === actionC && right === actionA -> -1
                    else -> 0
                }
            })
        }
        Collections.sort(registry, Comparator { _, _ -> 0 })
        assertSameIdentityOrder("AtomicMutableList.sort delegates without changing stable order",
            beforeForeignMutations, registry.toList())
        rejectsForeignMutation("clear") { registry.clear() }
        loadB.rollback()
        assertSameIdentityOrder("the second committed owner's action survives foreign-load rollback",
            beforeForeignMutationsWithC, registry.toList())
        assertFalse("the other owner's failed load leaves no outstanding state",
            services.hasOutstandingRegistrations(pluginIdB))

        assertTrue("committed owner unload succeeds", services.unloadPlugin(pluginIdA))
        assertTrue("the second committed owner's unload succeeds", services.unloadPlugin(pluginIdC))
        assertFalse("unload removes the committed action", registry.any { it === actionA })
        assertFalse("unload releases transaction ownership", services.hasOutstandingRegistrations(pluginIdA))
        assertSameIdentityOrder("all transaction action mutations restore the original registry",
            initialActionOrder, registry.toList())
    }

    @Test
    fun directRunActionJobKeepsPluginUnloadPendingUntilNonCancellableCleanupCompletes() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val registry = VideoClickActionHolder.allVideoClickActions
        val packagePath = createSyntheticPackagePath("direct-action-job")
        val pluginId = fixturePluginId("direct-action-job")
        val owner = FixturePlugin()
        val transaction = beginPluginLoad(services, pluginId, packagePath.absolutePath, owner)
        val action = HoldingCleanupAction("Synthetic unload cleanup action")
        owner.registerVideoClickAction(action)
        transaction.commit()

        var actionJob: kotlinx.coroutines.Job? = null
        try {
            actionJob = action.runActionSafe(target, testVideo(), LinkLoadingResult(
                emptyList(), emptyList(), HashMap()), null)
            assertTrue("direct runActionSafe callback starts",
                action.started.await(ACTION_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("the runtime centrally tracks the job under its exact plugin path",
                "ACTIVE", services.getVideoActionJobLifecycle(packagePath.absolutePath))
            assertEquals("the action retains the exact plugin path owner",
                packagePath.absolutePath, services.getVideoClickActionPluginPath(action))

            var unloadFailure: Throwable? = null
            try {
                services.unloadPlugin(pluginId)
            } catch (failure: Throwable) {
                unloadFailure = failure
            }
            assertTrue("unload stays cleanup-pending while noncancellable action cleanup is held",
                unloadFailure is IllegalStateException
                    && unloadFailure.message?.contains("have not quiesced") == true)
            assertTrue("cancellation enters the action's NonCancellable cleanup",
                action.cleanupStarted.await(ACTION_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("host records the cancellation request until cleanup completes",
                "CANCEL_REQUESTED", services.getVideoActionJobLifecycle(packagePath.absolutePath))
            assertTrue("cleanup-pending unload retains the committed registration",
                services.hasOutstandingRegistrations(pluginId))
            assertTrue("cleanup-pending unload retains the registry identity",
                registry.any { it === action })
            assertFalse("quiescing action is not callable during cleanup",
                services.isVideoClickActionAvailable(action))
        } finally {
            actionJob?.cancel()
            action.releaseCleanup.countDown()
            actionJob?.let { job ->
                runBlocking { withTimeout(ACTION_COMPLETION_TIMEOUT_MS) { job.join() } }
            }
        }

        val completedJob = actionJob ?: throw AssertionError("runActionSafe did not return a Job")
        assertTrue("the action completion is awaited before retrying unload", completedJob.isCompleted)
        assertTrue("NonCancellable cleanup completes after release",
            action.cleanupCompleted.await(0L, TimeUnit.MILLISECONDS))
        assertFalse("the bounded cleanup wait did not expire", action.cleanupTimedOut)
        assertEquals("the host retains only the pending cleanup handle after job completion",
            "QUIESCED_PENDING_CLEANUP", services.getVideoActionJobLifecycle(packagePath.absolutePath))

        assertTrue("retry unload completes after action completion", services.unloadPlugin(pluginId))
        fixtureTransactions.remove(transaction)
        fixturePluginIds.remove(pluginId)
        assertFalse("successful retry removes the plugin registration",
            services.hasOutstandingRegistrations(pluginId))
        assertFalse("successful retry removes the action identity from the registry",
            registry.any { it === action })
        assertEquals("successful retry releases exact action ownership", "",
            services.getVideoClickActionPluginPath(action))
        assertEquals("successful retry clears host action-job tracking", "QUIESCED",
            services.getVideoActionJobLifecycle(packagePath.absolutePath))
        val runtimeJobs = OrbitVideoActionRuntime::class.java.getDeclaredField("jobs")
            .apply { isAccessible = true }
            .get(OrbitVideoActionRuntime) as Map<*, *>
        assertFalse("successful retry leaves no stale runtime Job reference",
            runtimeJobs.containsKey(completedJob))
        assertSameIdentityOrder("successful retry restores the starting registry",
            initialActionOrder, registry.toList())
    }

    @Test
    fun canceledExternalActionResultDoesNotSaveReturnedPlaybackProgress() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-video-action-cancel-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        assertNoLibraryRow(profile.id, stableId)

        val action = FixtureAction("Synthetic cancellation action")
        commitPluginAction(action, "generic-result-cancel")
        val title = MediaItem(stableId, "Synthetic cancellation fixture", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "video-action-cancel-fixture", title,
            null, null, action)

        val canceledResult = Intent().putExtra("position", 48_000L).putExtra("duration", 90_000L)
        deliverExternalResult(activity, Activity.RESULT_CANCELED, canceledResult)

        assertNull("cancel clears external action result state", readField(activity, "externalVideoActionResult"))
        assertNull("cancel clears active action launch state", readField(activity, "activeVideoActionLaunch"))
        assertFalse("cancel clears the Details handoff marker",
            readField(activity, "providerDetailsHandoffPending") as Boolean)
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun committedActionGetsFreshGenerationAfterSamePathReload() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val path = createSyntheticPackagePath("open-in-app-generation-reload")
        val pluginId = fixturePluginId("open-in-app-generation-reload")
        val firstOwner = FixturePlugin()
        val firstAction = FixtureAction("Synthetic generation owner")
        val firstLoad = beginPluginLoad(services, pluginId, path.absolutePath, firstOwner)
        firstOwner.registerVideoClickAction(firstAction)
        firstLoad.commit()
        fixtureTransactions.remove(firstLoad)

        val tokenMethod = services.javaClass.getMethod(
            "getVideoClickActionGenerationToken", VideoClickAction::class.java)
        val firstToken = tokenMethod.invoke(services, firstAction)
            ?: throw AssertionError("a committed action must have an owner generation token")
        val activeMethod = services.javaClass.getMethod(
            "isVideoClickActionGenerationActive", VideoClickAction::class.java,
            firstToken.javaClass)
        assertTrue("the token identifies the currently committed action generation",
            activeMethod.invoke(services, firstAction, firstToken) as Boolean)

        assertTrue("the first generation unloads", services.unloadPlugin(pluginId))
        val secondOwner = FixturePlugin()
        val secondAction = FixtureAction("Synthetic generation owner")
        val secondLoad = beginPluginLoad(services, pluginId, path.absolutePath, secondOwner)
        secondOwner.registerVideoClickAction(secondAction)
        secondLoad.commit()
        fixtureTransactions.remove(secondLoad)

        val secondToken = tokenMethod.invoke(services, secondAction)
            ?: throw AssertionError("the reloaded action must receive a fresh owner token")
        assertTrue("a same-path, same-uniqueId reload never reuses its generation token",
            firstToken !== secondToken)
        assertFalse("the old exact action generation is stale after reload",
            activeMethod.invoke(services, firstAction, firstToken) as Boolean)
        assertTrue("the new action generation is active",
            activeMethod.invoke(services, secondAction, secondToken) as Boolean)
    }

    @Test
    fun openInAppActionFacadeExposesPinnedResultAndM3u8Entrypoints() {
        val actionType = Class.forName("com.lagradost.cloudstream3.actions.OpenInAppAction")
        assertTrue("OpenInAppAction is a VideoClickAction subtype",
            VideoClickAction::class.java.isAssignableFrom(actionType))
        actionType.getDeclaredConstructor(UiText::class.java, String::class.java,
            String::class.java, String::class.java)
        assertTrue("the defaulted intentClass/action constructor bridge exists",
            actionType.declaredConstructors.any { constructor ->
                constructor.parameterTypes.size == 6
                    && constructor.parameterTypes[4] == Int::class.javaPrimitiveType
                    && constructor.parameterTypes[5].name == "kotlin.jvm.internal.DefaultConstructorMarker"
            })
        actionType.getDeclaredMethod("shouldShow", Context::class.java, ResultEpisode::class.java)
        actionType.getDeclaredMethod("onResult", Activity::class.java, Intent::class.java)
        actionType.getDeclaredMethod("onResultSafe", Activity::class.java, Intent::class.java)
        val continuation = Class.forName("kotlin.coroutines.Continuation")
        actionType.getDeclaredMethod("putExtra", Context::class.java, Intent::class.java,
            ResultEpisode::class.java, LinkLoadingResult::class.java, Int::class.javaObjectType,
            continuation)
        actionType.getDeclaredMethod("runAction", Context::class.java, ResultEpisode::class.java,
            LinkLoadingResult::class.java, Int::class.javaObjectType, continuation)
        val facade = Class.forName("com.lagradost.cloudstream3.actions.OpenInAppActionKt")
        facade.getDeclaredMethod("updateDurationAndPosition",
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        facade.getDeclaredMethod("makeTempM3U8Intent", Context::class.java, Intent::class.java,
            LinkLoadingResult::class.java)
    }

    @Test
    fun openInAppResultOkDispatchesOnceWithNullIntent() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-null-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val callbackSawConsumedState = AtomicBoolean(false)
        val action = FixtureOpenInAppAction("Synthetic external player") {
            callbackSawConsumedState.set(readField(activity, "externalVideoActionResult") == null
                && readField(activity, "activeVideoActionLaunch") == null)
        }
        commitPluginAction(action, "open-in-app-null")
        val title = MediaItem(stableId, "Synthetic external player title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        val pending = prepareExternalResult(activity, profile.id, "open-in-app-null-provider", title,
            null, null, action)
        val actionReference = readField(pending, "action")
        assertTrue("pending state stores only a weak exact-action reference",
            actionReference is java.lang.ref.WeakReference<*>)
        assertSame("the weak reference points at the exact registered action",
            action, (actionReference as java.lang.ref.WeakReference<*>).get())
        assertNotNull("pending state includes an opaque load-generation token",
            readField(pending, "actionGeneration"))

        deliverExternalResult(activity, Activity.RESULT_OK, null)

        assertEquals("RESULT_OK with null data still dispatches once", 1, action.resultCalls)
        assertTrue("the callback receives null data unchanged", action.receivedNullIntent)
        assertTrue("pending state is consumed before extension dispatch", callbackSawConsumedState.get())
        assertNull("the one-shot result is consumed before returning",
            readField(activity, "externalVideoActionResult"))
        assertNull("the active launch is cleared before returning",
            readField(activity, "activeVideoActionLaunch"))
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun openInAppCallbackFailureIsContainedAndConsumesPendingResult() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-throw-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val action = FixtureOpenInAppAction("Synthetic throwing player").apply {
            throwFromResult = true
        }
        commitPluginAction(action, "open-in-app-throw")
        val title = MediaItem(stableId, "Synthetic throwing player title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-throw-provider", title,
            null, null, action)

        deliverExternalResult(activity, Activity.RESULT_OK, Intent())

        assertEquals("a throwing extension callback is still invoked once", 1, action.resultCalls)
        assertNull("callback exceptions do not retain the result", readField(activity, "externalVideoActionResult"))
        assertNull("callback exceptions do not retain the active launch", readField(activity, "activeVideoActionLaunch"))
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun canceledOpenInAppResultSkipsCallbackAndProgress() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-cancel-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val action = FixtureOpenInAppAction("Synthetic cancel player") {
            updateDurationAndPosition(48_000L, 90_000L)
        }
        commitPluginAction(action, "open-in-app-cancel")
        val title = MediaItem(stableId, "Synthetic cancel title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-cancel-provider", title,
            null, null, action)

        deliverExternalResult(activity, Activity.RESULT_CANCELED,
            Intent().putExtra("position", 48_000L).putExtra("duration", 90_000L))

        assertEquals("canceled results never call the extension", 0, action.resultCalls)
        assertNull("cancel clears result state", readField(activity, "externalVideoActionResult"))
        assertNull("cancel clears launch state", readField(activity, "activeVideoActionLaunch"))
        assertFalse("cancel clears the handoff marker",
            readField(activity, "providerDetailsHandoffPending") as Boolean)
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun launchFailureClearsBothPendingExternalActionStates() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-launch-failure-${UUID.randomUUID()}"
        val action = FixtureOpenInAppAction("Synthetic unavailable player")
        commitPluginAction(action, "open-in-app-launch-failure")
        val title = MediaItem(stableId, "Synthetic unavailable title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-launch-failure-provider", title,
            null, null, action, external = false)

        val threw = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            try {
                activity.launchVideoActionForResult(
                    Intent("com.auras.orbit.synthetic.MISSING_PLAYER").setPackage("com.auras.orbit.no.player"),
                    null)
            } catch (_: RuntimeException) {
                threw.set(true)
            }
        }

        assertTrue("an unavailable external target causes a launch failure", threw.get())
        assertNull("failed launch clears external result state", readField(activity, "externalVideoActionResult"))
        assertNull("failed launch clears the active action", readField(activity, "activeVideoActionLaunch"))
        assertFalse("failed launch clears the handoff marker",
            readField(activity, "providerDetailsHandoffPending") as Boolean)
    }

    @Test
    fun unloadedActionGenerationIsDroppedWhenExternalPlayerReturns() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-unload-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val pluginId = fixturePluginId("open-in-app-unload")
        val action = FixtureOpenInAppAction("Synthetic unloading player")
        commitPluginAction(action, "open-in-app-unload", pluginId)
        val title = MediaItem(stableId, "Synthetic unloading title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-unload-provider", title,
            null, null, action)

        assertTrue("the action owner unloads before the external result", services.unloadPlugin(pluginId))
        deliverExternalResult(activity, Activity.RESULT_OK,
            Intent().putExtra("position", 48_000L).putExtra("duration", 90_000L))

        assertEquals("an unloaded action receives no callback", 0, action.resultCalls)
        assertNull("stale return clears pending state", readField(activity, "externalVideoActionResult"))
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun samePathReloadCannotReceiveAnOldOpenInAppResult() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-reload-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val path = createSyntheticPackagePath("open-in-app-stale-reload")
        val pluginId = fixturePluginId("open-in-app-stale-reload")
        val firstAction = FixtureOpenInAppAction("Synthetic same-id player")
        commitPluginAction(firstAction, "open-in-app-stale-reload", pluginId, path)
        val title = MediaItem(stableId, "Synthetic reloaded title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-reload-provider", title,
            null, null, firstAction)

        assertTrue("the old plugin generation unloads", services.unloadPlugin(pluginId))
        val reloadedAction = FixtureOpenInAppAction("Synthetic same-id player")
        commitPluginAction(reloadedAction, "open-in-app-stale-reload", pluginId, path)
        assertEquals("the new registration has the same uniqueId",
            firstAction.uniqueId(), reloadedAction.uniqueId())
        deliverExternalResult(activity, Activity.RESULT_OK,
            Intent().putExtra("position", 21_000L).putExtra("duration", 90_000L))

        assertEquals("the old exact action is never called after reload", 0, firstAction.resultCalls)
        assertEquals("the new action cannot receive the previous generation's result", 0,
            reloadedAction.resultCalls)
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun profileDriftDropsOpenInAppResultWithoutCrossProfileProgressWrite() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-profile-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val action = FixtureOpenInAppAction("Synthetic profile player") {
            updateDurationAndPosition(21_000L, 90_000L)
        }
        commitPluginAction(action, "open-in-app-profile")
        val title = MediaItem(stableId, "Synthetic profile title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-profile-provider", title,
            null, null, action)
        val alternateProfile = application.localProfileStore.createProfile(
            "Temporary external-result drift ${UUID.randomUUID()}")
            ?: throw AssertionError("the disposable alternate profile could not be created")
        assertFalse("the drift profile is distinct from the callback's profile",
            profile.id == alternateProfile.id)

        try {
            application.localProfileStore.selectProfile(alternateProfile.id)
            deliverExternalResult(activity, Activity.RESULT_OK, Intent())
        } finally {
            application.localProfileStore.selectProfile(profile.id)
        }

        assertEquals("profile drift prevents callback dispatch", 0, action.resultCalls)
        assertNoLibraryRow(profile.id, stableId)
        assertNoLibraryRow(alternateProfile.id, stableId)
    }

    @Test
    fun openInAppProgressHelperWritesExactProfileTitleAndEpisodeInsideCallback() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-progress-${UUID.randomUUID()}"
        val episodeId = "synthetic-episode-s2e7"
        val resultPosition = 44_500L
        val resultDuration = 123_400L
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val action = FixtureOpenInAppAction("Synthetic progress player") {
            updateDurationAndPosition(resultPosition, resultDuration)
        }
        commitPluginAction(action, "open-in-app-progress")
        val title = MediaItem(stableId, "Synthetic exact series", MediaKind.SERIES,
            "https://orbit.invalid/details", "https://orbit.invalid/poster", "2024",
            "Synthetic exact synopsis", "synthetic-provider-data")
        val episode = MediaItem.EpisodeItem(episodeId, "Episode Seven", 2, 7,
            "https://orbit.invalid/episode-poster", "Episode synopsis", 1800,
            "", 0L, "", "synthetic-episode-data")
        prepareExternalResult(activity, profile.id, "open-in-app-progress-provider", title,
            episode, null, action)

        deliverExternalResult(activity, Activity.RESULT_OK, null)
        updateDurationAndPosition(8_000L, 10_000L)

        assertEquals("the valid progress callback runs once", 1, action.resultCalls)
        application.localLibraryStore.readableDatabase.rawQuery(
            "SELECT profile_id,stable_id,source_id,title,media_type,detail_url,poster_url,year,synopsis," +
                "progress_ms,duration_ms,resume_episode_id,resume_episode_title,resume_season," +
                "resume_episode_number FROM playback_progress WHERE profile_id=? AND stable_id=?",
            arrayOf(profile.id, stableId)).use { cursor ->
            assertTrue("the callback progress helper records one exact row", cursor.moveToFirst())
            assertEquals(profile.id, cursor.getString(0))
            assertEquals(stableId, cursor.getString(1))
            assertEquals("open-in-app-progress-provider", cursor.getString(2))
            assertEquals("Synthetic exact series", cursor.getString(3))
            assertEquals("SERIES", cursor.getString(4))
            assertEquals("https://orbit.invalid/details", cursor.getString(5))
            assertEquals("https://orbit.invalid/poster", cursor.getString(6))
            assertEquals("2024", cursor.getString(7))
            assertEquals("Synthetic exact synopsis", cursor.getString(8))
            assertEquals(resultPosition, cursor.getLong(9))
            assertEquals(resultDuration, cursor.getLong(10))
            assertEquals(episodeId, cursor.getString(11))
            assertEquals("Episode Seven", cursor.getString(12))
            assertEquals(2, cursor.getInt(13))
            assertEquals(7, cursor.getInt(14))
            assertFalse("only one matching row is written", cursor.moveToNext())
        }
    }

    @Test
    fun openInAppProgressHelperIgnoresNonpositiveValues() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-invalid-progress-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val action = FixtureOpenInAppAction("Synthetic invalid progress player") {
            updateDurationAndPosition(0L, 90_000L)
            updateDurationAndPosition(1_000L, 0L)
            updateDurationAndPosition(-1L, 90_000L)
        }
        commitPluginAction(action, "open-in-app-invalid-progress")
        val title = MediaItem(stableId, "Synthetic invalid progress", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-invalid-progress-provider", title,
            null, null, action)

        deliverExternalResult(activity, Activity.RESULT_OK, Intent())

        assertEquals("invalid inputs do not prevent callback execution", 1, action.resultCalls)
        assertNoLibraryRow(profile.id, stableId)
    }

    @Test
    fun openInAppResultLeaseKeepsUnloadPendingUntilCallbackReturns() {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-open-in-app-result-lease-${UUID.randomUUID()}"
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        val pluginId = fixturePluginId("open-in-app-result-lease")
        val actionLeaseUnloadDeferred = AtomicBoolean(false)
        val actionLeaseLifecycle = AtomicReference("")
        val actionOwnerPath = AtomicReference("")
        val action = FixtureOpenInAppAction("Synthetic leased player") {
            try {
                services.unloadPlugin(pluginId)
            } catch (_: IllegalStateException) {
                actionLeaseUnloadDeferred.set(true)
            }
            actionLeaseLifecycle.set(services.getVideoActionJobLifecycle(actionOwnerPath.get()))
        }
        val owner = commitPluginAction(action, "open-in-app-result-lease", pluginId)
        actionOwnerPath.set(owner.path.absolutePath)
        val title = MediaItem(stableId, "Synthetic leased title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-provider-data")
        prepareExternalResult(activity, profile.id, "open-in-app-result-lease-provider", title,
            null, null, action)

        deliverExternalResult(activity, Activity.RESULT_OK, Intent())

        assertEquals("the callback invoked while leased runs once", 1, action.resultCalls)
        assertTrue("unload is held as cleanup-pending while the callback is active",
            actionLeaseUnloadDeferred.get())
        assertEquals("the host reports an active result callback lease",
            "RESULT_CALLBACK_ACTIVE", actionLeaseLifecycle.get())
        assertTrue("the owner remains outstanding after the callback releases its lease",
            services.hasOutstandingRegistrations(pluginId))
        assertTrue("retry unload completes after callback return", services.unloadPlugin(pluginId))
        assertFalse("retry removes the plugin action", services.getVideoClickActionSnapshot().contains(action))
    }

    @Test
    fun makeTempM3u8IntentSharesOneLinkDirectlyAndMultipleLinksFromCacheOnly() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val single = payloadLink("single", 720, "https://orbit.invalid/single.m3u8")
        val directIntent = Intent()
        makeTempM3U8Intent(target, directIntent,
            LinkLoadingResult(listOf(single), emptyList(), hashMapOf()))
        assertEquals(Uri.parse(single.url), directIntent.data)
        assertEquals("video/*", directIntent.type)

        val before = (target.cacheDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("mirrorlist") && it.name.endsWith(".m3u8") }
            .map { it.canonicalPath }.toSet()
        val multiIntent = Intent()
        makeTempM3U8Intent(target, multiIntent, LinkLoadingResult(
            listOf(single, payloadLink("backup", 480, "https://orbit.invalid/backup.m3u8")),
            emptyList(), hashMapOf()))
        val authority = target.applicationContext.packageName + ".provider"
        try {
            assertEquals("content", multiIntent.data?.scheme)
            assertEquals(authority, multiIntent.data?.authority)
            assertEquals("application/x-mpegURL", multiIntent.type)
            assertTrue("playlist grants read access",
                multiIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue("playlist grants write access as pinned",
                multiIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue("playlist grants persistable access as pinned",
                multiIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
            assertTrue("playlist grants prefix access as pinned",
                multiIntent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
            assertTrue("configured FileProvider remains non-exported",
                target.packageManager.resolveContentProvider(authority, 0)?.exported == false)
            val content = target.contentResolver.openInputStream(multiIntent.data!!)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw AssertionError("the cache playlist URI could not be read")
            assertTrue(content.startsWith("#EXTM3U\n#EXT-X-VERSION:3"))
            assertTrue(content.contains("\n#EXTINF:0,single\nhttps://orbit.invalid/single.m3u8"))
            assertTrue(content.contains("\n#EXTINF:0,backup\nhttps://orbit.invalid/backup.m3u8"))
            assertTrue(content.endsWith("#EXT-X-ENDLIST"))
        } finally {
            (target.cacheDir.listFiles() ?: emptyArray())
                .filter { it.name.startsWith("mirrorlist") && it.name.endsWith(".m3u8")
                    && it.canonicalPath !in before }
                .forEach { assertTrue("test-created M3U8 cache files are removed", it.delete()) }
        }
    }

    @Test
    fun successfulExternalActionResultRecordsExactPlaybackProgressAndCleansUp() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-video-action-success-${UUID.randomUUID()}"
        val resultPositionMs = 37_250L
        val resultDurationMs = 123_000L
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        assertNoLibraryRow(profile.id, stableId)

        val action = FixtureAction("Synthetic generic progress action")
        commitPluginAction(action, "generic-result-success")
        val title = MediaItem(stableId, "Synthetic successful fixture title", MediaKind.MOVIE,
            "", "", "", "", "synthetic-success-provider-data")
        prepareExternalResult(activity, profile.id, "video-action-success-fixture", title,
            null, null, action)

        val successfulResult = Intent()
            .putExtra("position", resultPositionMs)
            .putExtra("duration", resultDurationMs)
        deliverExternalResult(activity, Activity.RESULT_OK, successfulResult)

        assertNull("success clears external action result state",
            readField(activity, "externalVideoActionResult"))
        assertNull("success clears active action launch state",
            readField(activity, "activeVideoActionLaunch"))
        assertFalse("success clears the Details handoff marker",
            readField(activity, "providerDetailsHandoffPending") as Boolean)

        val database = application.localLibraryStore.readableDatabase
        database.rawQuery(
            "SELECT profile_id,stable_id,source_id,title,media_type,detail_url,poster_url,year,synopsis," +
                "saved_at,favorite,watch_status,progress_ms,duration_ms,resume_episode_id," +
                "resume_episode_title,resume_season,resume_episode_number,updated_at " +
                "FROM playback_progress WHERE profile_id=? AND stable_id=?",
            arrayOf(profile.id, stableId)).use { cursor ->
            assertTrue("RESULT_OK creates one playback-progress row", cursor.moveToFirst())
            assertEquals(profile.id, cursor.getString(0))
            assertEquals(stableId, cursor.getString(1))
            assertEquals("video-action-success-fixture", cursor.getString(2))
            assertEquals("Synthetic successful fixture title", cursor.getString(3))
            assertEquals("MOVIE", cursor.getString(4))
            assertEquals("", cursor.getString(5))
            assertEquals("", cursor.getString(6))
            assertEquals("", cursor.getString(7))
            assertEquals("", cursor.getString(8))
            assertEquals(0L, cursor.getLong(9))
            assertEquals(0, cursor.getInt(10))
            assertEquals("NONE", cursor.getString(11))
            assertEquals(resultPositionMs, cursor.getLong(12))
            assertEquals(resultDurationMs, cursor.getLong(13))
            assertEquals("", cursor.getString(14))
            assertEquals("", cursor.getString(15))
            assertEquals(-1, cursor.getInt(16))
            assertEquals(-1, cursor.getInt(17))
            assertTrue("progress row records its update time", cursor.getLong(18) > 0L)
            assertFalse("only one matching progress row is written", cursor.moveToNext())
        }
        database.rawQuery(
            "SELECT 1 FROM saved_titles WHERE profile_id=? AND stable_id=? LIMIT 1",
            arrayOf(profile.id, stableId)).use { cursor ->
            assertFalse("playback progress does not add an unsaved title to the library",
                cursor.moveToFirst())
        }
    }

    @Test
    fun orbitOwnedVlcBuiltinUsesExactIdentityAllowlistForScopedProgressResult() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val stableId = "orbit-test-vlc-builtin-result-${UUID.randomUUID()}"
        val resultPositionMs = 28_500L
        val resultDurationMs = 91_000L
        playbackFixtureProfileId = profile.id
        playbackFixtureStableId = stableId
        assertNoLibraryRow(profile.id, stableId)

        val action = VideoClickActionHolder.getActionById(1004) as? OpenInAppAction
            ?: throw AssertionError("the pinned VLC slot must expose its OpenInAppAction")
        assertNull("the seeded host action remains ownerless", action.sourcePlugin)
        assertTrue("only the exact app-classloader seeded object is accepted",
            VideoClickActionHolder.isOrbitOwnedBuiltinAction(action))
        val title = MediaItem(stableId, "Synthetic VLC builtin title", MediaKind.MOVIE,
            "https://orbit.invalid/vlc-details", "", "", "", "builtin-progress-provider")
        val pending = prepareExternalResult(activity, profile.id, "builtin-progress-provider", title,
            null, null, action)
        assertNull("ownerless built-ins do not receive a plugin generation token",
            readField(pending, "actionGeneration"))

        deliverExternalResult(activity, Activity.RESULT_OK,
            Intent().putExtra("extra_position", resultPositionMs)
                .putExtra("extra_duration", resultDurationMs))

        assertNull("the successful callback consumes external result state",
            readField(activity, "externalVideoActionResult"))
        application.localLibraryStore.readableDatabase.rawQuery(
            "SELECT profile_id,stable_id,source_id,title,media_type,detail_url,progress_ms,duration_ms " +
                "FROM playback_progress WHERE profile_id=? AND stable_id=?",
            arrayOf(profile.id, stableId)).use { cursor ->
            assertTrue("the allowlisted VLC callback writes one profile-scoped progress row",
                cursor.moveToFirst())
            assertEquals(profile.id, cursor.getString(0))
            assertEquals(stableId, cursor.getString(1))
            assertEquals("builtin-progress-provider", cursor.getString(2))
            assertEquals("Synthetic VLC builtin title", cursor.getString(3))
            assertEquals("MOVIE", cursor.getString(4))
            assertEquals("https://orbit.invalid/vlc-details", cursor.getString(5))
            assertEquals(resultPositionMs, cursor.getLong(6))
            assertEquals(resultDurationMs, cursor.getLong(7))
            assertFalse("the callback writes only one matching row", cursor.moveToNext())
        }
    }

    @Test
    fun actionPayloadUsesPinnedStableLinkAndSubtitleOrderBeforeDispatch() {
        val profile = application.localProfileStore.activeProfile
            ?: throw AssertionError("the isolated active profile disappeared")
        val activity = launchShellOnHome()
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val pluginId = fixturePluginId("ordered-action-payload")
        val packagePath = createSyntheticPackagePath("ordered-action-payload")
        val plugin = FixturePlugin()
        val action = PayloadCaptureAction()
        val transaction = beginPluginLoad(services, pluginId, packagePath.absolutePath, plugin)
        plugin.registerVideoClickAction(action)
        transaction.commit()
        fixtureTransactions.remove(transaction)

        val low = payloadLink("Low", 360, "https://payload-order.invalid/low.mp4")
        val high = payloadLink("High", 1080, "https://payload-order.invalid/high.mp4")
        val equalQualityFirst = payloadLink("Tie first", 720,
            "https://payload-order.invalid/tie-first.mp4")
        val equalQualitySecond = payloadLink("Tie second", 720,
            "https://payload-order.invalid/tie-second.mp4")
        val equalValueDifferentIdentity = payloadLink("High", 1080,
            "https://payload-order.invalid/high.mp4")
        val inputLinks = listOf(low, high, equalQualityFirst, high,
            equalQualitySecond, equalValueDifferentIdentity)

        val subtitleZulu = payloadSubtitle("Zulu", "", "https://payload-order.invalid/zulu.vtt")
        val subtitleAlphaTwo = payloadSubtitle("Alpha", "2",
            "https://payload-order.invalid/alpha-two.vtt")
        val subtitleAlphaOne = payloadSubtitle("Alpha", "1",
            "https://payload-order.invalid/alpha-one.vtt")
        val inputSubtitles = listOf(subtitleZulu, subtitleAlphaTwo, subtitleAlphaTwo.copy(),
            subtitleAlphaOne, subtitleZulu)
        val inputResult = LinkLoadingResult(inputLinks, inputSubtitles, hashMapOf("sync" to "kept"))
        val context = com.auras.orbit.providers.Cs3ActionContext(
            pluginId, profile.id, testVideo(), inputResult)

        val payloadMethod = OrbitShellActivity::class.java.getDeclaredMethod(
            "linksForVideoAction", VideoClickAction::class.java,
            com.auras.orbit.providers.Cs3ActionContext::class.java)
        payloadMethod.isAccessible = true
        val actionPayload = payloadMethod.invoke(activity, action, context) as LinkLoadingResult

        assertEquals("quality order is descending with first-seen ties stable", 5,
            actionPayload.links.size)
        assertSame(high, actionPayload.links[0])
        assertSame("distinct ExtractorLink objects use identity equality", equalValueDifferentIdentity,
            actionPayload.links[1])
        assertSame("equal-quality first callback remains first", equalQualityFirst,
            actionPayload.links[2])
        assertSame("equal-quality second callback remains second", equalQualitySecond,
            actionPayload.links[3])
        assertSame(low, actionPayload.links[4])
        assertEquals(listOf("Alpha/1", "Alpha/2", "Zulu/"), actionPayload.subs.map {
            "${it.originalName}/${it.nameSuffix}"
        })
        assertEquals("subtitle data-class equality removes a duplicate value", 3,
            actionPayload.subs.size)
        assertEquals(mapOf("sync" to "kept"), actionPayload.syncData)
        assertSame("the host leaves the provider's original callback list unchanged", high,
            inputResult.links[1])
        assertEquals(6, inputResult.links.size)

        val selectedIndex = 3
        runBlocking {
            withTimeout(ACTION_COMPLETION_TIMEOUT_MS) {
                action.runActionSafe(activity, context.video, actionPayload, selectedIndex).join()
            }
        }
        assertSame("oneSource dispatch receives the normalized list", actionPayload,
            action.receivedResult)
        assertEquals("oneSource dispatch retains the selected index in sorted-list coordinates",
            selectedIndex, action.receivedIndex)
        assertSame("the selected index names the stable-sorted source", equalQualitySecond,
            action.receivedResult?.links?.get(action.receivedIndex!!))
    }

    private fun payloadLink(label: String, quality: Int, url: String) =
        ExtractorLink("Synthetic payload", label, url, "", quality,
            false, emptyMap(), null)

    private data class FixtureActionOwner(val pluginId: String, val path: File)

    private fun commitPluginAction(
        action: VideoClickAction,
        label: String,
        pluginId: String? = null,
        packagePath: File? = null,
    ): FixtureActionOwner {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val ownerId = pluginId ?: fixturePluginId(label)
        val path = packagePath ?: createSyntheticPackagePath(label)
        val plugin = FixturePlugin()
        val transaction = beginPluginLoad(services, ownerId, path.absolutePath, plugin)
        plugin.registerVideoClickAction(action)
        transaction.commit()
        fixtureTransactions.remove(transaction)
        return FixtureActionOwner(ownerId, path)
    }

    private fun prepareExternalResult(
        activity: OrbitShellActivity,
        profileId: String,
        providerId: String,
        title: MediaItem,
        episode: MediaItem.EpisodeItem?,
        context: com.auras.orbit.providers.Cs3ActionContext?,
        action: VideoClickAction,
        external: Boolean = true,
    ): Any {
        val pending = newPendingVideoAction(profileId, providerId, title, episode, context, action)
        if (external) setField(activity, "externalVideoActionResult", pending)
        setField(activity, "activeVideoActionLaunch", pending)
        setField(activity, "providerDetailsHandoffPending", true)
        return pending
    }

    private fun newPendingVideoAction(
        profileId: String,
        providerId: String,
        title: MediaItem,
        episode: MediaItem.EpisodeItem?,
        context: com.auras.orbit.providers.Cs3ActionContext?,
        action: VideoClickAction,
    ): Any {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val token = services.getVideoClickActionGenerationToken(action)
        if (action.sourcePlugin != null && token == null) {
            throw AssertionError("the committed plugin action must have a generation token")
        }
        val pendingType = Class.forName("com.auras.orbit.ui.OrbitShellActivity\$PendingVideoAction")
        val tokenType = Class.forName(
            "com.auras.orbit.extensions.OrbitCs3HostServices\$VideoClickActionGenerationToken")
        val constructor: Constructor<*> = pendingType.getDeclaredConstructor(
            String::class.java, String::class.java, MediaItem::class.java,
            MediaItem.EpisodeItem::class.java, com.auras.orbit.providers.Cs3ActionContext::class.java,
            VideoClickAction::class.java, tokenType)
        constructor.isAccessible = true
        return constructor.newInstance(profileId, providerId, title, episode, context, action, token)
    }

    private fun deliverExternalResult(activity: OrbitShellActivity, resultCode: Int, data: Intent?) {
        instrumentation.runOnMainSync {
            val callback = OrbitShellActivity::class.java.getDeclaredMethod(
                "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Intent::class.java)
            callback.isAccessible = true
            callback.invoke(activity, 73, resultCode, data)
        }
        instrumentation.waitForIdleSync()
    }

    private fun payloadSubtitle(originalName: String, suffix: String, url: String) =
        SubtitleData(originalName, suffix, url, SubtitleOrigin.URL,
            "text/vtt", emptyMap(), "en")

    private fun createSyntheticPackagePath(label: String): File {
        val path = File(fixtureRoot, "$label-${UUID.randomUUID()}.fixture")
        assertTrue("synthetic transaction paths are regular fixture files", path.createNewFile())
        fixtureFiles.add(path)
        return path
    }

    private fun fixturePluginId(label: String): String =
        "https://orbit.invalid/androidTest/video-action/$label/${UUID.randomUUID()}"
            .also(fixturePluginIds::add)

    private fun beginPluginLoad(
        services: com.auras.orbit.extensions.OrbitCs3HostServices,
        pluginId: String,
        packagePath: String,
        plugin: Plugin,
    ): com.auras.orbit.extensions.OrbitCs3HostServices.LoadTransaction =
        services.beginPluginLoad(pluginId, packagePath, plugin).also(fixtureTransactions::add)

    private data class ForeignPermutationFixture(
        val services: com.auras.orbit.extensions.OrbitCs3HostServices,
        val registry: com.lagradost.cloudstream3.utils.AtomicMutableList<VideoClickAction>,
        val firstAction: FixtureAction,
        val secondAction: FixtureAction,
        val firstOwnerId: String,
        val secondOwnerId: String,
        val activeLoad: com.auras.orbit.extensions.OrbitCs3HostServices.LoadTransaction,
        val activePluginId: String,
        val startingOrder: List<VideoClickAction>,
    )

    private fun beginForeignPermutationFixture(label: String): ForeignPermutationFixture {
        val services = host ?: throw AssertionError("Orbit host services are unavailable")
        val registry = VideoClickActionHolder.allVideoClickActions
        val firstPath = createSyntheticPackagePath("$label-first-owner")
        val firstOwner = FixturePlugin()
        val firstAction = FixtureAction("$label first foreign action")
        val firstOwnerId = fixturePluginId("$label-first-owner")
        val firstLoad = beginPluginLoad(services, firstOwnerId, firstPath.absolutePath, firstOwner)
        firstOwner.registerVideoClickAction(firstAction)
        firstLoad.commit()
        fixtureTransactions.remove(firstLoad)

        val secondPath = createSyntheticPackagePath("$label-second-owner")
        val secondOwner = FixturePlugin()
        val secondAction = FixtureAction("$label second foreign action")
        val secondOwnerId = fixturePluginId("$label-second-owner")
        val secondLoad = beginPluginLoad(services, secondOwnerId, secondPath.absolutePath, secondOwner)
        secondOwner.registerVideoClickAction(secondAction)
        secondLoad.commit()
        fixtureTransactions.remove(secondLoad)

        val activePackagePath = createSyntheticPackagePath("$label-active-plugin")
        val activePluginId = fixturePluginId("$label-active-plugin")
        val activeLoad = beginPluginLoad(services, activePluginId, activePackagePath.absolutePath, FixturePlugin())
        return ForeignPermutationFixture(services, registry, firstAction, secondAction,
            firstOwnerId, secondOwnerId, activeLoad, activePluginId, registry.toList())
    }

    private fun finishForeignPermutationFixture(fixture: ForeignPermutationFixture) {
        fixture.activeLoad.rollback()
        fixtureTransactions.remove(fixture.activeLoad)
        fixturePluginIds.remove(fixture.activePluginId)
        assertTrue("the first foreign action owner unloads cleanly",
            fixture.services.unloadPlugin(fixture.firstOwnerId))
        fixturePluginIds.remove(fixture.firstOwnerId)
        assertTrue("the second foreign action owner unloads cleanly",
            fixture.services.unloadPlugin(fixture.secondOwnerId))
        fixturePluginIds.remove(fixture.secondOwnerId)
        assertSameIdentityOrder("foreign permutation fixture restores the starting registry",
            initialActionOrder, fixture.registry.toList())
    }

    private fun launchShellOnHome(): OrbitShellActivity {
        val launched = instrumentation.startActivitySync(
            Intent(target, OrbitShellActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        val activity = launched as? OrbitShellActivity
            ?: throw AssertionError("the actual OrbitShellActivity must be launched")
        shell = activity
        instrumentation.runOnMainSync {
            shellState(activity).navigateTo(ShellRoute.HOME)
            invokeRender(activity)
        }
        instrumentation.waitForIdleSync()
        await("the shell profile confirmation or Home route appears") {
            findTagged(activity, "nav_HOME") != null || findText(activity, "Continue") != null
        }
        val continueButtonRef = java.util.concurrent.atomic.AtomicReference<TextView?>()
        instrumentation.runOnMainSync { continueButtonRef.set(findText(activity, "Continue")) }
        val continueButton = continueButtonRef.get()
        if (continueButton != null) instrumentation.runOnMainSync {
            assertTrue("the disposable test profile continues into Orbit", continueButton.performClick())
        }
        await("profile reconciliation completes and Home navigation appears") {
            findTagged(activity, "nav_HOME") != null
        }
        assertEquals(ShellRoute.HOME, shellState(activity).route)
        val homeTagRef = java.util.concurrent.atomic.AtomicReference<View?>()
        instrumentation.runOnMainSync { homeTagRef.set(findTagged(activity, "nav_HOME")) }
        assertNotNull("the synthetic cancellation fixture starts on the normal Home screen", homeTagRef.get())
        return activity
    }

    private fun assertNoLibraryRow(profileId: String, stableId: String) {
        val database = application.localLibraryStore.readableDatabase
        for (table in listOf("saved_titles", "playback_progress")) {
            database.rawQuery("SELECT 1 FROM $table WHERE profile_id=? AND stable_id=? LIMIT 1",
                arrayOf(profileId, stableId)).use { cursor ->
                assertFalse("the unique playback fixture must leave $table unchanged", cursor.moveToFirst())
            }
        }
    }

    private fun restoreProfilePreferences() {
        val editor = profilePreferences.edit().clear()
        profilePreferenceSnapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> throw AssertionError("unsupported profile preference type: ${value.javaClass.name}")
            }
        }
        assertTrue("the exact pre-test profile preferences are restored", editor.commit())
        assertEquals("the persisted profile values match the pre-test snapshot",
            profilePreferenceSnapshot, profilePreferences.all)
        assertEquals("the exact pre-test active profile is restored",
            originalActiveProfileId, application.localProfileStore.activeProfileId)
    }

    private fun restoreProviderSelectionPreferences() {
        val editor = providerSelectionPreferences.edit().clear()
        providerSelectionPreferenceSnapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> throw AssertionError("unsupported provider preference type: ${value.javaClass.name}")
            }
        }
        assertTrue("the exact pre-test provider preference values are restored", editor.commit())
        assertEquals("the provider preferences in memory match the pre-test snapshot",
            providerSelectionPreferenceSnapshot, providerSelectionPreferences.all)
        restoreRawPreferenceFile(providerSelectionFile, providerSelectionFileExisted,
            providerSelectionFileSnapshot, "provider preference file")
        restoreRawPreferenceFile(providerSelectionBackupFile, providerSelectionBackupFileExisted,
            providerSelectionBackupFileSnapshot, "provider preference backup")
    }

    private fun restoreRawPreferenceFile(file: File, existed: Boolean, snapshot: ByteArray?,
                                        description: String) {
        if (existed) {
            val expected = snapshot
                ?: throw AssertionError("the original $description bytes were not captured")
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw AssertionError("could not recreate the $description parent directory")
            }
            file.writeBytes(expected)
            assertTrue("the exact original $description bytes are restored",
                file.isFile && file.readBytes().contentEquals(expected))
        } else {
            if (file.exists()) {
                assertTrue("the previously absent $description is removed", file.delete())
            }
            assertFalse("the previously absent $description stays absent", file.exists())
        }
    }

    private fun expectIllegalState(message: String, block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return }
        throw AssertionError("$message mutation must be rejected for a different plugin owner")
    }

    private fun expectActionRemovalRejected(message: String, block: () -> Unit) {
        try { block() } catch (_: IllegalStateException) { return }
        throw AssertionError("$message removal must be rejected outside an internal host mutation")
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val ready = java.util.concurrent.atomic.AtomicBoolean(false)
            instrumentation.runOnMainSync { ready.set(predicate()) }
            if (ready.get()) return
            android.os.SystemClock.sleep(50L)
        }
        throw AssertionError("timed out waiting for $description")
    }

    private fun assertSameIdentityOrder(message: String, expected: List<VideoClickAction>,
                                        actual: List<VideoClickAction>) {
        assertEquals(message, expected.size, actual.size)
        expected.indices.forEach { index -> assertSame(message, expected[index], actual[index]) }
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

    private fun readField(instance: Any, name: String): Any? = findField(instance.javaClass, name).get(instance)

    private fun setField(instance: Any, name: String, value: Any?) {
        findField(instance.javaClass, name).set(instance, value)
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field {
        var cursor: Class<*>? = type
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cursor = cursor.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun findTagged(activity: OrbitShellActivity, tag: String): View? =
        findView(activity.window.decorView) { it.tag == tag }

    private fun findText(activity: OrbitShellActivity, text: String): TextView? =
        findView(activity.window.decorView) {
            it is TextView && it.text?.toString() == text
        } as? TextView

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findView(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun testVideo() = ResultEpisode(
        headerName = "Synthetic action episode", name = null, poster = null,
        episode = 1, seasonIndex = null, season = null, data = "synthetic-provider-data",
        apiName = "Synthetic action provider", id = 1, index = 0, position = 0L,
        duration = 90_000L, score = null, description = null, isFiller = null,
        tvType = TvType.Movie, parentId = 1, videoWatchState = VideoWatchState.None,
    )

    private class FixturePlugin : Plugin()

    private class FixtureAction(label: String) : VideoClickAction() {
        override val name: UiText = UiText.DynamicString(label)
        var onInvoke: (() -> Unit)? = null
        override fun shouldShow(context: Context?, video: ResultEpisode?) = true
        override suspend fun runAction(context: Context?, video: ResultEpisode,
                                       result: LinkLoadingResult, index: Int?) {
            onInvoke?.invoke()
        }
    }

    private class FixtureOpenInAppAction(
        label: String,
        private val onResultCallback: (() -> Unit)? = null,
    ) : OpenInAppAction(UiText.DynamicString(label), "com.auras.orbit.synthetic.player") {
        var resultCalls = 0
        var receivedNullIntent = false
        var throwFromResult = false

        override suspend fun putExtra(
            context: Context,
            intent: Intent,
            video: ResultEpisode,
            result: LinkLoadingResult,
            index: Int?,
        ) = Unit

        override fun onResult(activity: Activity, intent: Intent?) {
            resultCalls++
            receivedNullIntent = intent == null
            onResultCallback?.invoke()
            if (throwFromResult) throw IllegalStateException("synthetic callback failure")
        }
    }

    private class PayloadCaptureAction : VideoClickAction() {
        override val name: UiText = UiText.DynamicString("Payload order fixture")
        override val oneSource: Boolean = true
        @Volatile var receivedResult: LinkLoadingResult? = null
        @Volatile var receivedIndex: Int? = null

        override fun shouldShow(context: Context?, video: ResultEpisode?) = true
        override suspend fun runAction(context: Context?, video: ResultEpisode,
                result: LinkLoadingResult, index: Int?) {
            receivedResult = result
            receivedIndex = index
        }
    }

    private class HoldingCleanupAction(label: String) : VideoClickAction() {
        override val name: UiText = UiText.DynamicString(label)
        val started = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupCompleted = CountDownLatch(1)
        @Volatile var cleanupTimedOut = false

        override fun shouldShow(context: Context?, video: ResultEpisode?) = true

        override suspend fun runAction(context: Context?, video: ResultEpisode,
                                       result: LinkLoadingResult, index: Int?) {
            started.countDown()
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.countDown()
                    try {
                        if (!releaseCleanup.await(ACTION_CLEANUP_RELEASE_TIMEOUT_MS,
                                TimeUnit.MILLISECONDS)) cleanupTimedOut = true
                    } finally {
                        cleanupCompleted.countDown()
                    }
                }
            }
        }
    }

    companion object {
        private const val PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1"
        private const val PROVIDER_SELECTION_PREFERENCES = "orbit_provider_selection_v1"
        private const val ACTIVE_PROFILE_KEY = "active_profile_id"
        private const val ACTION_CALLBACK_TIMEOUT_MS = 5_000L
        private const val ACTION_COMPLETION_TIMEOUT_MS = 5_000L
        private const val ACTION_CLEANUP_RELEASE_TIMEOUT_MS = 10_000L
    }
}
