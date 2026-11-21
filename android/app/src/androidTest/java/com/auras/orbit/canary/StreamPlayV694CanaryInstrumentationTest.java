package com.auras.orbit.canary;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.auras.orbit.BuildConfig;
import com.auras.orbit.OrbitApplication;
import com.auras.orbit.extensions.CloudStreamExtensionManager;
import com.auras.orbit.extensions.CS3PluginRuntime;
import com.auras.orbit.extensions.Cs3DexCapabilityPreflight;
import com.auras.orbit.extensions.OrbitCs3HostServices;
import com.auras.orbit.extensions.PinnedStreamPlayV694Fixture;
import com.lagradost.cloudstream3.plugins.BasePlugin;
import com.lagradost.cloudstream3.utils.ExtractorApi;
import com.auras.orbit.profile.LocalProfileStore;
import com.lagradost.cloudstream3.APIHolder;
import com.lagradost.cloudstream3.MainAPI;
import com.lagradost.cloudstream3.actions.VideoClickAction;
import com.lagradost.cloudstream3.actions.VideoClickActionHolder;

import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Static diagnostics plus an explicitly gated one-shot StreamPlay v694 canary. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class StreamPlayV694CanaryInstrumentationTest {
    private static final String TAG = "OrbitCS3CanaryTest";
    private static final String JOURNAL_FILE = "orbit-cs3-canary-instrumentation-stages.log";
    private static final long STATIC_PREFLIGHT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2L);
    private static final long AUTHORIZED_CANARY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(4L);
    private static final String AUTHORIZED_CANARY_ARGUMENT = "orbitRunStreamPlayV694Canary";
    private static final String AUTHORIZED_CANARY_ARGUMENT_VALUE = "authorize-one-shot";
    private static final String STREAMPLAY_V694_REPOSITORY_URL = PinnedStreamPlayV694Fixture.REPOSITORY_URL;
    private static final String STREAMPLAY_V694_PLUGIN_ID = PinnedStreamPlayV694Fixture.PLUGIN_ID;
    private static final String STREAMPLAY_V694_SHA256 = PinnedStreamPlayV694Fixture.PACKAGE_SHA256;
    private static final long STREAMPLAY_V694_SIZE = PinnedStreamPlayV694Fixture.PACKAGE_SIZE;
    private static final long STREAMPLAY_V694_VERSION = PinnedStreamPlayV694Fixture.VERSION;
    private static final String[] PINNED_BUILTIN_VIDEO_ACTION_TYPES = {
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
            "com.lagradost.cloudstream3.actions.AlwaysAskAction"
    };

    /** Exercises diagnostic redaction without resolving or loading any plugin class. */
    @Test
    public void canaryFailureDiagnosticIsRedactedAndBoundedWithoutLoadingPlugin() {
        StringBuilder longClass = new StringBuilder("com.");
        while (longClass.length() < 96) longClass.append('C');
        StringBuilder longMethod = new StringBuilder();
        while (longMethod.length() < 48) longMethod.append('m');

        IllegalStateException deepest = new IllegalStateException(
                "Authorization: Bearer private-token Cookie: private-cookie body=private-payload");
        deepest.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("https://private.example/Authorization", "Cookie", "secret.Source.java", 9876),
                new StackTraceElement(longClass.toString(), longMethod.toString(), "secret.Source.java", 9876),
                new StackTraceElement(longClass.toString(), longMethod.toString(), "secret.Source.java", 9876),
                new StackTraceElement(longClass.toString(), longMethod.toString(), "secret.Source.java", 9876)
        });
        RuntimeException wrapper = new RuntimeException("https://private.example/headers?token=private-token");
        wrapper.initCause(deepest);

        String diagnostic = CS3PluginRuntime.safeCanaryFailureDiagnostic(wrapper);
        assertTrue("Diagnostic exceeded its fixed bound.",
                diagnostic.length() <= CS3PluginRuntime.MAX_CANARY_FAILURE_DIAGNOSTIC_CHARS);
        assertTrue("Diagnostic did not retain the deepest exception class.",
                diagnostic.startsWith("exception=java.lang.IllegalStateException"));
        assertTrue("Diagnostic did not retain class and method identifiers.", diagnostic.contains("#"));
        assertTrue("Diagnostic retained more than a few frames.", diagnostic.split(",").length <= 3);
        assertTrue("Diagnostic retained an exception message or stack metadata.",
                !diagnostic.contains("Authorization")
                        && !diagnostic.contains("private")
                        && !diagnostic.contains("Cookie")
                        && !diagnostic.contains("body")
                        && !diagnostic.contains("https")
                        && !diagnostic.contains("Source.java")
                        && !diagnostic.contains("9876"));

        RuntimeException inaccessible = new RuntimeException("secret message") {
            @Override
            public synchronized Throwable getCause() {
                throw new SecurityException("secret cause");
            }

            @Override
            public synchronized StackTraceElement[] getStackTrace() {
                throw new SecurityException("secret stack");
            }
        };
        String fallback = CS3PluginRuntime.safeCanaryFailureDiagnostic(inaccessible);
        assertTrue("Diagnostic extraction failure escaped its length bound.",
                fallback.length() <= CS3PluginRuntime.MAX_CANARY_FAILURE_DIAGNOSTIC_CHARS);
        assertTrue("Diagnostic extraction failure exposed a message.",
                !fallback.contains("secret") && fallback.startsWith("exception="));
    }

    /** Exercises bounded cause classification using synthetic throwables only. */
    @Test
    public void canaryFailureCategoryBoundsCauseTraversalWithoutLoadingPlugin() {
        final AtomicInteger causeReads = new AtomicInteger();
        class EndlessCause extends RuntimeException {
            private final AtomicInteger reads;

            EndlessCause(AtomicInteger reads) {
                super("private cause message");
                this.reads = reads;
            }

            @Override
            public synchronized Throwable getCause() {
                reads.incrementAndGet();
                return new EndlessCause(reads);
            }
        }

        assertEquals("other_error", CS3PluginRuntime.safeCanaryFailureCategory(
                new EndlessCause(causeReads)));
        assertEquals("Cause traversal exceeded its fixed depth bound.",
                CS3PluginRuntime.MAX_CANARY_FAILURE_CAUSE_DEPTH, causeReads.get());

        final AtomicInteger cycleReads = new AtomicInteger();
        final AtomicReference<Throwable> cycleNext = new AtomicReference<>();
        RuntimeException cycleStart = new RuntimeException("private cycle") {
            @Override
            public synchronized Throwable getCause() {
                cycleReads.incrementAndGet();
                return cycleNext.get();
            }
        };
        RuntimeException cycleEnd = new RuntimeException("private cycle") {
            @Override
            public synchronized Throwable getCause() {
                cycleReads.incrementAndGet();
                return cycleStart;
            }
        };
        cycleNext.set(cycleEnd);
        assertEquals("other_error", CS3PluginRuntime.safeCanaryFailureCategory(cycleStart));
        assertEquals("Cause cycle was not detected after the two distinct throwables.",
                2, cycleReads.get());

        RuntimeException inaccessible = new RuntimeException("private message") {
            @Override
            public synchronized Throwable getCause() {
                throw new SecurityException("private cause");
            }
        };
        assertEquals("other_error", CS3PluginRuntime.safeCanaryFailureCategory(inaccessible));
    }

    /** A staged extractor is retained as evidence but cannot satisfy the provider canary. */
    @Test
    public void canaryRequiresProviderEvidenceWithoutLoadingPlugin() {
        assertTrue("Provider-only staging should satisfy the provider canary.",
                CS3PluginRuntime.hasCanaryProviderRegistrationEvidence(1, 0));
        assertTrue("Provider and extractor staging should satisfy the provider canary.",
                CS3PluginRuntime.hasCanaryProviderRegistrationEvidence(1, 1));
        assertTrue("Extractor-only staging must not satisfy the provider canary.",
                !CS3PluginRuntime.hasCanaryProviderRegistrationEvidence(0, 1));
        assertTrue("Zero registrations must not satisfy the provider canary.",
                !CS3PluginRuntime.hasCanaryProviderRegistrationEvidence(0, 0));
    }

    /** Static-only inspection against the pinned catalog asset; this never loads plugin code. */
    @Test
    public void reportInstalledStreamPlayV694WithoutLoadingPlugin() throws Exception {
        Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        Context targetContext = instrumentation.getTargetContext().getApplicationContext();
        assertTrue("The canary build did not start OrbitApplication.", targetContext instanceof OrbitApplication);
        OrbitApplication app = (OrbitApplication) targetContext;
        String pluginId = STREAMPLAY_V694_PLUGIN_ID;
        CloudStreamExtensionManager.InstalledPlugin plugin =
                app.getCloudStreamExtensionManager().getInstalledPlugin(pluginId);
        assumeTrue("The read-only installed-package report requires the authorized exact-install fixture.",
                plugin != null);
        assertEquals("StreamPlay", plugin.name);
        assertEquals(694, plugin.version);
        assertEquals("com.phisher98.StreamPlayPlugin", plugin.pluginClassName);
        assertTrue("StreamPlay v694 must retain its resource-bearing Android plugin base.", plugin.requiresResources);
        File archive = new File(plugin.filePath);
        String actualSha256 = sha256(archive);
        assertEquals("Installed bytes differ from the pinned metadata.", plugin.sha256, actualSha256);
        CloudStreamExtensionManager.PluginInfo pinnedRecord =
                PinnedStreamPlayV694Fixture.readPinnedPluginInfo(
                        instrumentation.getContext(), plugin.repositoryUrl, plugin.downloadUrl);
        assertEquals("Installed metadata differs from the pinned v694 catalog record.",
                STREAMPLAY_V694_PLUGIN_ID, pinnedRecord.id);
        assertEquals("Installed metadata differs from the pinned v694 catalog hash.",
                actualSha256, pinnedRecord.expectedSha256);
        CloudStreamExtensionManager.PluginInfo catalogRecord = null;
        int catalogMatches = 0;
        for (CloudStreamExtensionManager.PluginInfo candidate
                : app.getCloudStreamExtensionManager().getCatalog(plugin.repositoryUrl)) {
            if (pluginId.equals(candidate.id)) {
                catalogRecord = candidate;
                catalogMatches++;
            }
        }
        assertEquals("Saved repository catalog must contain one exact pinned StreamPlay record.",
                1, catalogMatches);
        assertNotNull("Saved pinned StreamPlay catalog record is unavailable.", catalogRecord);
        assertEquals("Saved catalog differs from the pinned repository and package metadata.",
                pinnedRecord.id, catalogRecord.id);
        assertEquals(pinnedRecord.repositoryUrl, catalogRecord.repositoryUrl);
        assertEquals(pinnedRecord.name, catalogRecord.name);
        assertEquals(pinnedRecord.internalName, catalogRecord.internalName);
        assertEquals(pinnedRecord.url, catalogRecord.url);
        assertEquals(pinnedRecord.expectedSha256, catalogRecord.expectedSha256);
        assertEquals(pinnedRecord.expectedSize, catalogRecord.expectedSize);
        assertEquals(pinnedRecord.version, catalogRecord.version);
        assertEquals("Installed SHA does not match the pinned catalog hash.",
                actualSha256, catalogRecord.expectedSha256);
        assertEquals("The pinned catalog size does not match the installed archive.",
                archive.length(), catalogRecord.expectedSize);

        LocalProfileStore.Profile activeProfile = app.getLocalProfileStore().getActiveProfile();
        assertNotNull("Orbit has no saved active profile for the canary.", activeProfile);
        assertTrue("CS3 package-storage recovery is not ready.",
                app.getCloudStreamExtensionManager().isStorageRecoveryReady());
        assertTrue("The serialized extension worker is busy.",
                app.getExtensionOperationController() != null
                        && !app.getExtensionOperationController().getSnapshot().isRunning());
        assertTrue("StreamPlay already has a profile-specific enable grant.",
                !app.getCloudStreamExtensionManager().isEnabledForProfile(activeProfile.id, pluginId));
        assertTrue("StreamPlay is already loaded.", !app.getCS3PluginRuntime().isLoaded(pluginId));
        assertTrue("The plugin registration registry must be empty before canary.",
                OrbitCs3HostServices.get().getOutstandingPluginIds().isEmpty());
        List<VideoClickAction> actionRegistry = snapshotPinnedBuiltinActionRegistry();
        Log.i(TAG, "canary_preconditions profile=resolved storage_recovery=ready profile_grant=absent"
                + " plugin_loaded=false plugin_registrations=empty action_registry_size="
                + actionRegistry.size());

        Cs3DexCapabilityPreflight.Result result = app.getCS3PluginRuntime().preflightCompatibility(plugin);
        Log.i(TAG, "installed_static_preflight version=694 sha256=" + actualSha256
                + " bytes=" + archive.length() + " dexCount=" + result.dexCount
                + " dexBytes=" + result.dexBytes + " missingHostTypes=" + result.missingHostTypes.size()
                + " missingHostMethods=" + result.missingHostMethods.size()
                + " missingHostFields=" + result.missingHostFields.size()
                + " missingExternalTypes=" + result.missingExternalTypes.size()
                + " missingExternalMethods=" + result.missingExternalMethods.size()
                + " missingExternalFields=" + result.missingExternalFields.size()
                + " externalResolutionComplete=" + result.externalResolutionComplete
                + " errorKinds=" + safeErrorKinds(result.errors));
        assertTrue("Installed StreamPlay v694 static preflight failed without loading plugin code: "
                        + "missingHostTypes=" + result.missingHostTypes.size()
                        + " missingHostMethods=" + result.missingHostMethods.size()
                        + " missingHostFields=" + result.missingHostFields.size()
                        + " missingExternalTypes=" + result.missingExternalTypes.size()
                        + " missingExternalMethods=" + result.missingExternalMethods.size()
                        + " missingExternalFields=" + result.missingExternalFields.size()
                        + " errorKinds=" + safeErrorKinds(result.errors),
                result.isExactHostSurfacePresent());
    }

    /** Static archive/API diagnostics only; this method never resolves or executes plugin code. */
    @Test
    public void reportStaticHostSurfaceWithoutLoadingPlugin() {
        final long startElapsed = SystemClock.elapsedRealtime();
        Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        Context targetContext = instrumentation.getTargetContext().getApplicationContext();
        assertTrue("Static host preflight requires the Orbit canary application.",
                targetContext instanceof OrbitApplication);
        OrbitApplication app = (OrbitApplication) targetContext;
        assumeTrue("Static host preflight requires the installed exact StreamPlay v694 fixture.",
                app.getCloudStreamExtensionManager().getInstalledPlugin(
                        CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID) != null);
        AtomicReference<Context> journalContext = new AtomicReference<>();
        Log.i(TAG, "stage=static_preflight.start elapsed_ms=0");
        FutureTask<CS3PluginRuntime.CanaryPreflightResult> diagnostic = new FutureTask<>(() -> {
            journalContext.set(targetContext);
            mark(targetContext, "static_preflight.context_ready", startElapsed);
            mark(targetContext, "static_preflight.inspect.before", startElapsed);
            CS3PluginRuntime.CanaryPreflightResult result =
                    app.getCS3PluginRuntime().inspectStreamPlayV694CanaryCompatibility();
            mark(targetContext, "static_preflight.inspect.after", startElapsed);
            return result;
        });
        Thread worker = new Thread(diagnostic, "orbit-streamplay-static-preflight");
        worker.setDaemon(true);
        worker.start();
        final CS3PluginRuntime.CanaryPreflightResult result;
        try {
            result = diagnostic.get(STATIC_PREFLIGHT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            markAvailable(journalContext, "static_preflight.timeout", startElapsed);
            diagnostic.cancel(true);
            throw new AssertionError("Static preflight exceeded its two-minute bound; no plugin code was executed.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            markAvailable(journalContext, "static_preflight.interrupted", startElapsed);
            diagnostic.cancel(true);
            throw new AssertionError("Static preflight was interrupted; no plugin code was executed.");
        } catch (ExecutionException error) {
            markAvailable(journalContext, "static_preflight.failed", startElapsed);
            throw new AssertionError("Static preflight failed; inspect its fixed stage markers.");
        }
        assertNotNull("Static preflight returned no result.", result);
        String report = "static_preflight exact_identity=" + result.exactAuthorizedPackage;
        if (result.hostSurface == null) {
            report += " host_surface=not_scanned";
        } else {
            report += " exact_host_surface=" + result.hostSurface.isExactHostSurfacePresent()
                    + " errors=" + result.hostSurface.errors.size()
                    + " errorKinds=" + safeErrorKinds(result.hostSurface.errors)
                    + " missingTypeCount=" + result.hostSurface.missingHostTypes.size()
                    + " missingMethodCount=" + result.hostSurface.missingHostMethods.size()
                    + " missingFieldCount=" + result.hostSurface.missingHostFields.size();
        }
        Log.i(TAG, report);
        assertTrue("The catalog record does not match the exact authorized StreamPlay v694 package.",
                result.exactAuthorizedPackage);
        assertNotNull("The exact v694 host surface was not scanned.", result.hostSurface);
        assertTrue("Exact StreamPlay v694 static preflight failed: "
                        + safeErrorKinds(result.hostSurface.errors)
                        + " missingHostTypes=" + result.hostSurface.missingHostTypes.size()
                        + " missingHostMethods=" + result.hostSurface.missingHostMethods.size()
                        + " missingHostFields=" + result.hostSurface.missingHostFields.size()
                        + " missingExternalTypes=" + result.hostSurface.missingExternalTypes.size()
                        + " missingExternalMethods=" + result.hostSurface.missingExternalMethods.size()
                        + " missingExternalFields=" + result.hostSurface.missingExternalFields.size(),
                result.hostSurface.isExactHostSurfacePresent());
        markAvailable(journalContext, "static_preflight.complete", startElapsed);
    }

    /**
     * One-shot positive canary. It skips in generic instrumentation runs unless the runner
     * receives the exact authorization argument. All package and profile state stays inside
     * the isolated .cs3canary app.
     */
    @Test
    public void authorizedRunOneExplicitlyStreamPlayV694CanaryAttempt() throws Exception {
        android.os.Bundle runnerArguments =
                androidx.test.platform.app.InstrumentationRegistry.getArguments();
        assumeTrue("The one-shot canary requires its exact explicit runner argument.",
                AUTHORIZED_CANARY_ARGUMENT_VALUE.equals(
                        runnerArguments.getString(AUTHORIZED_CANARY_ARGUMENT)));

        Instrumentation instrumentation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        Context targetContext = instrumentation.getTargetContext().getApplicationContext();
        long oneShotStartedElapsed = SystemClock.elapsedRealtime();
        mark(targetContext, "one_shot.entry", oneShotStartedElapsed);
        String processName = "api_lt_28";
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            String currentProcessName = android.app.Application.getProcessName();
            if (currentProcessName != null) processName = currentProcessName;
        }
        Log.i(TAG, "one_shot_process pid=" + android.os.Process.myPid()
                + " process_name=" + processName
                + " target_package=" + targetContext.getPackageName()
                + " instrumentation_package=" + instrumentation.getContext().getPackageName());
        assertTrue("The canary target did not start OrbitApplication.",
                targetContext instanceof OrbitApplication);
        assertTrue("The one-shot runner is not targeting the dedicated canary build.",
                BuildConfig.CS3_CANARY_BUILD);
        assertEquals("Unexpected canary target package ID.",
                "com.auras.orbit.cs3canary", targetContext.getPackageName());
        assertEquals("Unexpected canary instrumentation package ID.",
                "com.auras.orbit.cs3canary.test", instrumentation.getContext().getPackageName());

        OrbitApplication app = (OrbitApplication) targetContext;
        CloudStreamExtensionManager extensionManager = app.getCloudStreamExtensionManager();
        CS3PluginRuntime runtime = app.getCS3PluginRuntime();
        OrbitCs3HostServices hostServices = OrbitCs3HostServices.get();
        assertNotNull("CS3 package storage is unavailable.", extensionManager);
        assertNotNull("The CS3 runtime is unavailable.", runtime);
        assertTrue("The accepted product crosswalk remains enabled before canary setup.",
                runtime.isTargetPluginCrosswalkValidated());
        assertTrue("Null plugin metadata is never ready for activation.",
                !runtime.isReadyForPlugin(null));
        assertTrue("CS3 package-storage recovery is not ready.",
                extensionManager.isStorageRecoveryReady());
        assertTrue("The canary app must start with no installed plugin packages.",
                extensionManager.getInstalledPlugins().isEmpty());
        assertTrue("The canary app must start with no saved repositories.",
                extensionManager.getRepositories().isEmpty());
        assertTrue("The canary app must start with no outstanding plugin registrations.",
                hostServices.getOutstandingPluginIds().isEmpty());
        assertTrue("StreamPlay is already loaded in the fresh canary app.",
                !runtime.isLoaded(STREAMPLAY_V694_PLUGIN_ID));
        assertTrue("The consumed marker from the prior authorization must not exist in this app.",
                !runtime.isStreamPlayV694CanaryConsumed());
        assertTrue("A stale fresh-authorization marker already exists in this app.",
                !runtime.hasFreshStreamPlayV694CanaryAuthorization());
        File noBackupDirectory = targetContext.getNoBackupFilesDir();
        File[] priorAuthorizationMarkers = noBackupDirectory.listFiles(file -> file.isFile()
                && file.getName().startsWith(
                CS3PluginRuntime.STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_PREFIX));
        assertNotNull("Could not inspect the isolated authorization-marker directory.",
                priorAuthorizationMarkers);
        assertEquals("The isolated canary app has prior authorization markers.",
                0, priorAuthorizationMarkers.length);
        File[] priorConsumedAuthorizationMarkers = noBackupDirectory.listFiles(file -> file.isFile()
                && file.getName().startsWith(
                "orbit-streamplay-v694-canary-consumed-authorization-"));
        assertNotNull("Could not inspect the isolated consumed-marker directory.",
                priorConsumedAuthorizationMarkers);
        assertEquals("The isolated canary app has prior consumed authorization markers.",
                0, priorConsumedAuthorizationMarkers.length);

        LocalProfileStore profileStore = app.getLocalProfileStore();
        LocalProfileStore.Profile activeProfile = profileStore.getActiveProfile();
        if (activeProfile == null) {
            String profileName = "StreamPlay v694 Canary "
                    + UUID.randomUUID().toString().substring(0, 8);
            LocalProfileStore.Profile canaryProfile = profileStore.createProfile(profileName);
            assertNotNull("Could not create the isolated canary-only profile.", canaryProfile);
            profileStore.selectProfile(canaryProfile.id);
            activeProfile = profileStore.getActiveProfile();
        }
        assertNotNull("No active canary-only profile is available.", activeProfile);
        assertTrue("The active profile already has a StreamPlay v694 grant.",
                !extensionManager.isEnabledForProfile(activeProfile.id, STREAMPLAY_V694_PLUGIN_ID));
        assertTrue("The extension worker is busy before the canary setup.",
                app.getExtensionOperationController() != null
                        && !app.getExtensionOperationController().getSnapshot().isRunning());

        CloudStreamExtensionManager.InstalledPlugin installed;
        try (PinnedStreamPlayV694Fixture fixture =
                     PinnedStreamPlayV694Fixture.open(instrumentation.getContext())) {
            CloudStreamExtensionManager.RepositoryInfo addedRepository =
                    extensionManager.addRepositoryMetadata("", "Phisher Cloudstream Plugin Server",
                            STREAMPLAY_V694_REPOSITORY_URL,
                            "Test-only pinned StreamPlay v694 catalog fixture.");
            assertNotNull("The pinned Phisher repository metadata was not saved.", addedRepository);
            assertEquals("A different repository was saved for the canary app.",
                    STREAMPLAY_V694_REPOSITORY_URL, addedRepository.url);

            CloudStreamExtensionManager.PluginInfo pinnedRecord = fixture.pluginInfo(
                    STREAMPLAY_V694_REPOSITORY_URL);
            PinnedStreamPlayV694Fixture.seedCatalog(extensionManager, pinnedRecord);
            List<CloudStreamExtensionManager.PluginInfo> savedCatalog =
                    extensionManager.getCatalog(STREAMPLAY_V694_REPOSITORY_URL);
            assertEquals("The canary cache must contain only the pinned StreamPlay v694 record.",
                    1, savedCatalog.size());
            CloudStreamExtensionManager.PluginInfo matchingPlugin = savedCatalog.get(0);
            assertEquals("The Phisher catalog must contain the exact StreamPlay ID.",
                    STREAMPLAY_V694_PLUGIN_ID, matchingPlugin.id);
            assertEquals(STREAMPLAY_V694_REPOSITORY_URL, matchingPlugin.repositoryUrl);
            assertEquals("StreamPlay", matchingPlugin.name);
            assertEquals("StreamPlay", matchingPlugin.internalName);
            assertEquals(STREAMPLAY_V694_VERSION, matchingPlugin.version);
            assertEquals("The pinned catalog size does not match the archive.",
                    STREAMPLAY_V694_SIZE, matchingPlugin.expectedSize);
            assertEquals("The pinned catalog hash does not match the archive.",
                    STREAMPLAY_V694_SHA256, matchingPlugin.expectedSha256);
            assertEquals("The package URL must use the deterministic loopback server.",
                    fixture.packageUrl(), matchingPlugin.url);
            assertEquals("The canary app gained an unexpected repository.",
                    1, extensionManager.getRepositories().size());

            // Use the ordinary installer with only the pinned v694 record.
            installed = extensionManager.installPlugin(matchingPlugin);
            assertNotNull("The exact pinned StreamPlay catalog package was not installed.", installed);
            assertEquals(STREAMPLAY_V694_PLUGIN_ID, installed.id);
            assertEquals(STREAMPLAY_V694_REPOSITORY_URL, installed.repositoryUrl);
            assertEquals("StreamPlay", installed.name);
            assertEquals("StreamPlay", installed.internalName);
            assertEquals(STREAMPLAY_V694_VERSION, installed.version);
            assertEquals("com.phisher98.StreamPlayPlugin", installed.pluginClassName);
            assertTrue("The resource-bearing Android plugin flag was not retained.", installed.requiresResources);
            assertTrue("The installed hash was not published by the pinned catalog.", installed.hashWasPublished);
            assertEquals("The installed record did not retain its loopback package URL.",
                    fixture.packageUrl(), installed.downloadUrl);
            fixture.assertServedExactlyOnce();
        }
        File archive = new File(installed.filePath);
        assertTrue("The installed StreamPlay package is missing or has the wrong size.",
                archive.isFile() && archive.length() == STREAMPLAY_V694_SIZE);
        assertEquals("The installed StreamPlay bytes do not match the pinned v694 hash.",
                STREAMPLAY_V694_SHA256, sha256(archive));
        if (archive.canWrite()) {
            assertTrue("Could not make the StreamPlay package read-only before canary load.",
                    archive.setReadOnly());
        }
        assertTrue("The StreamPlay package remains writable before canary load.", !archive.canWrite());
        assertEquals("The canary app installed a package other than the exact StreamPlay candidate.",
                1, extensionManager.getInstalledPlugins().size());
        assertTrue("StreamPlay already has a profile-specific grant.",
                !extensionManager.isEnabledForProfile(activeProfile.id, STREAMPLAY_V694_PLUGIN_ID));
        assertTrue("The exact StreamPlay package was loaded before its one-shot queue call.",
                !runtime.isLoaded(STREAMPLAY_V694_PLUGIN_ID));
        assertTrue("A prior plugin registration appeared before the canary load.",
                hostServices.getOutstandingPluginIds().isEmpty());

        String authorizationId = "canary-" + UUID.randomUUID().toString().replace("-", "");
        String authorizationContents = "authorization=" + authorizationId + "\nsha256="
                + STREAMPLAY_V694_SHA256 + "\n";
        File authorizationMarker = new File(noBackupDirectory,
                CS3PluginRuntime.STREAMPLAY_V694_CANARY_AUTHORIZATION_MARKER_PREFIX
                        + authorizationId + ".sha256");
        assertTrue("Could not create the fresh unique private authorization marker.",
                authorizationMarker.createNewFile());
        try (FileOutputStream output = new FileOutputStream(authorizationMarker, false)) {
            output.write(authorizationContents.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        assertTrue("The runtime did not recognize the fresh unique authorization marker.",
                runtime.hasFreshStreamPlayV694CanaryAuthorization());

        // Retain strong references to every built-in action so rollback must restore
        // the exact 20 identities at the same IDs, not merely an equal-sized list.
        List<VideoClickAction> actionRegistryBefore = snapshotPinnedBuiltinActionRegistry();
        Log.i(TAG, "one_shot_canary_registry_before plugin_registrations=empty action_registry_size="
                + actionRegistryBefore.size());
        List<?> providerRegistryBefore = hostServices.getProviderSnapshot();
        List<ExtractorApi> extractorRegistryBefore = hostServices.getExtractorSnapshot();
        List<String> providerOrderBefore = app.getProviderSelectionStore()
                .getOrderedProviderIds(activeProfile.id);
        String runtimeProfileBefore = runtime.getActiveProfileId();
        APIHolder apiHolder = APIHolder.INSTANCE;
        if (apiHolder.getApiMap() == null) {
            apiHolder.getApiFromNameNull("__orbit_cs3_canary_snapshot_materialize__");
        }
        List<MainAPI> apiListBefore = new ArrayList<>(apiHolder.getApis());
        Map<String, Integer> apiMapBefore = apiHolder.getApiMap() == null
                ? null : new LinkedHashMap<>(apiHolder.getApiMap());
        assertNotNull("APIHolder API map could not be materialized for rollback comparison.", apiMapBefore);
        mark(targetContext, "one_shot.queue.before", oneShotStartedElapsed);
        Future<CS3PluginRuntime.CanaryResult> queuedCanary =
                app.enqueueDebugStreamPlayV694Canary();
        assertNotNull("Orbit did not queue the explicitly authorized one-shot canary.", queuedCanary);
        mark(targetContext, "one_shot.queue.after", oneShotStartedElapsed);
        final CS3PluginRuntime.CanaryResult result;
        try {
            mark(targetContext, "one_shot.wait.before", oneShotStartedElapsed);
            result = queuedCanary.get(AUTHORIZED_CANARY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            mark(targetContext, "one_shot.wait.completed", oneShotStartedElapsed);
            mark(targetContext, "one_shot.wait.returned", oneShotStartedElapsed);
            markCanaryResult(targetContext, oneShotStartedElapsed, result);
        } catch (TimeoutException timeout) {
            boolean futureCancelled = queuedCanary.cancel(true);
            mark(targetContext, "one_shot.wait.timeout", oneShotStartedElapsed);
            Log.e(TAG, "one_shot_canary_timeout future_cancelled=" + futureCancelled
                    + " authorization_retry=false external_cleanup_required=true");
            throw new AssertionError("The one-shot canary exceeded its four-minute bound; the future was "
                    + "cancelled and no in-process force-stop was attempted. The external wrapper must stop "
                    + "and verify the target before any later action. Do not retry this authorization.");
        } catch (InterruptedException interrupted) {
            mark(targetContext, "one_shot.wait.interrupted", oneShotStartedElapsed);
            Thread.currentThread().interrupt();
            throw new AssertionError("The one-shot canary wait was interrupted; do not retry this authorization.");
        } catch (ExecutionException error) {
            mark(targetContext, "one_shot.wait.failed", oneShotStartedElapsed);
            throw new AssertionError("The one-shot canary task failed before returning cleanup evidence.");
        }

        assertNotNull("The one-shot canary returned no result.", result);
        assertTrue("The exact StreamPlay v694 load did not complete successfully.", result.success);
        assertTrue("The canary did not complete cleanup.", result.cleanupComplete);
        assertTrue("The canary result lacks provider registration evidence.",
                CS3PluginRuntime.hasCanaryProviderRegistrationEvidence(
                        result.stagedProviderCount, result.stagedExtractorCount));
        assertTrue("The canary exceeded its transaction-local extractor registration ceiling.",
                result.stagedExtractorCount <= OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS);
        assertTrue("The bounded provider callback was not attempted.", result.providerSearchAttempted);
        assertTrue("The one-provider search batch did not reach a terminal result.",
                result.providerSearchBatchCompleted);
        assertEquals("The canary did not select provider registration index zero.",
                0, result.providerSearchProviderIndex);
        assertEquals("The exact provider callback did not complete successfully.",
                "SUCCESS", result.providerSearchStatus);
        assertEquals("The canary did not prove exactly one successful provider callback.",
                1, result.providerSearchCallbackCount);
        assertTrue("The canary callback exceeded its strict outer wait bound.",
                result.providerSearchElapsedMs >= 0L
                        && result.providerSearchElapsedMs <= CS3PluginRuntime.CANARY_PROVIDER_SEARCH_WAIT_MS);
        assertTrue("The canary reported more than its capped result count.",
                result.providerSearchResultCount > 0
                        && result.providerSearchResultCount <= CS3PluginRuntime.MAX_CANARY_PROVIDER_SEARCH_RESULTS);
        assertTrue("StreamPlay remains loaded after the canary rollback.",
                !runtime.isLoaded(STREAMPLAY_V694_PLUGIN_ID));
        assertTrue("StreamPlay received a profile grant during the canary.",
                !extensionManager.isEnabledForProfile(activeProfile.id, STREAMPLAY_V694_PLUGIN_ID));
        assertTrue("The plugin registration registry is not empty after rollback.",
                hostServices.getOutstandingPluginIds().isEmpty());
        assertPinnedBuiltinActionRegistryRestored(actionRegistryBefore);
        assertTrue("The accepted product crosswalk remains enabled during the canary.",
                runtime.isTargetPluginCrosswalkValidated());
        assertTrue("Null plugin metadata remains not ready during the canary.",
                !runtime.isReadyForPlugin(null));
        assertEquals("Provider registrations were not restored after rollback.",
                providerRegistryBefore, hostServices.getProviderSnapshot());
        assertEquals("The canary changed the saved provider order for its profile.",
                providerOrderBefore, app.getProviderSelectionStore().getOrderedProviderIds(activeProfile.id));
        assertEquals("Extractor registrations were not restored after rollback.",
                extractorRegistryBefore, hostServices.getExtractorSnapshot());
        assertEquals("The canary did not restore the runtime's active profile snapshot.",
                runtimeProfileBefore, runtime.getActiveProfileId());
        assertEquals("APIHolder.apis was not restored after rollback.",
                apiListBefore, apiHolder.getApis());
        assertEquals("APIHolder.apiMap was not restored after rollback.",
                apiMapBefore, new LinkedHashMap<>(apiHolder.getApiMap()));
        LocalProfileStore.Profile activeProfileAfter = profileStore.getActiveProfile();
        assertNotNull("The active canary profile disappeared during the one-shot run.", activeProfileAfter);
        assertEquals("The active canary profile changed during the one-shot run.",
                activeProfile.id, activeProfileAfter.id);
        File consumedMarker = new File(noBackupDirectory,
                "orbit-streamplay-v694-canary-consumed-authorization-" + authorizationId + ".sha256");
        assertTrue("The one-shot authorization marker was not durably consumed.",
                consumedMarker.isFile() && consumedMarker.length() == authorizationContents.length());
        assertEquals("The consumed marker does not preserve the exact authorization and pinned hash.",
                authorizationContents, readCanaryMarker(consumedMarker));
        Log.i(TAG, "one_shot_canary_result success=true cleanup_complete=true providers="
                + result.stagedProviderCount + " extractors=" + result.stagedExtractorCount
                + " search_status=" + result.providerSearchStatus
                + " search_callbacks=" + result.providerSearchCallbackCount
                + " search_results=" + result.providerSearchResultCount
                + " search_elapsed_ms=" + result.providerSearchElapsedMs
                + " plugin_registrations=empty action_registry_size="
                + actionRegistryBefore.size() + " package_id=com.auras.orbit.cs3canary");
    }

    /** The isolated canary path stays locked without a new, unique authorization marker. */
    @Test
    public void canaryLoadPathRequiresFreshUniqueAuthorizationAndProductionGateStaysClosed() {
        Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        Context targetContext = instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("The isolated test runner did not start OrbitApplication.",
                targetContext instanceof OrbitApplication);
        assertTrue("The dedicated AndroidTest APK did not target the canary build.", BuildConfig.CS3_CANARY_BUILD);
        assertEquals("The canary target package is not isolated from active app IDs.",
                "com.auras.orbit.cs3canary", targetContext.getPackageName());
        assertEquals("The canary instrumentation package is not isolated from active test IDs.",
                "com.auras.orbit.cs3canary.test", instrumentation.getContext().getPackageName());
        OrbitApplication app = (OrbitApplication) targetContext;
        CS3PluginRuntime runtime = app.getCS3PluginRuntime();
        String pluginId = CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID;
        assertTrue("StreamPlay was already loaded before the retirement check.", !runtime.isLoaded(pluginId));
        assertTrue("A fresh authorization marker must not be present in a newly prepared candidate.",
                !runtime.hasFreshStreamPlayV694CanaryAuthorization());
        assertTrue("The accepted product crosswalk remains enabled.",
                runtime.isTargetPluginCrosswalkValidated());
        assertTrue("Null plugin metadata remains not ready after the crosswalk passes.",
                !runtime.isReadyForPlugin(null));
        Set<String> registrationsBefore =
                new HashSet<>(OrbitCs3HostServices.get().getOutstandingPluginIds());

        assertTrue("The canary armed without a fresh unique authorization marker.",
                !runtime.armStreamPlayV694DebugCanaryOnce());
        CS3PluginRuntime.CanaryResult directResult = runtime.runStreamPlayV694DebugCanary(null, null);
        assertTrue("The no-marker runtime path unexpectedly succeeded.", !directResult.success);
        assertTrue("Missing authorization must be a clean no-load rejection.", directResult.cleanupComplete);
        assertEquals("canary_authorization_required", directResult.failureCategory);
        assertEquals("A new unique StreamPlay v694 authorization marker is required.", directResult.message);
        assertEquals(0, directResult.stagedProviderCount);
        assertEquals(0, directResult.stagedExtractorCount);
        assertTrue("The no-marker runtime path loaded StreamPlay.", !runtime.isLoaded(pluginId));
        assertEquals("The no-marker runtime path changed host registrations.", registrationsBefore,
                new HashSet<>(OrbitCs3HostServices.get().getOutstandingPluginIds()));

        try {
            app.enqueueDebugStreamPlayV694Canary();
            throw new AssertionError("The canary queue accepted work without a new authorization marker.");
        } catch (IllegalStateException expected) {
            assertTrue("The queue rejection did not name its missing authorization marker.",
                    expected.getMessage().contains("unique StreamPlay v694 authorization marker"));
        }
        assertTrue("The no-marker queue path loaded StreamPlay.", !runtime.isLoaded(pluginId));
        assertEquals("The no-marker queue path changed host registrations.", registrationsBefore,
                new HashSet<>(OrbitCs3HostServices.get().getOutstandingPluginIds()));
    }

    /** Exercises the transaction-local ceiling and rollback using synthetic objects only. */
    @Test
    public void canaryExtractorStageCeilingRollsBackSyntheticRegistrations() throws Exception {
        Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        Context targetContext = instrumentation.getTargetContext().getApplicationContext();
        assertTrue("The dedicated AndroidTest APK did not target the canary build.", BuildConfig.CS3_CANARY_BUILD);
        assertEquals(OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS,
                OrbitCs3HostServices.getExtractorStageLimitForCurrentBuild());
        OrbitCs3HostServices services = OrbitCs3HostServices.get();
        Set<String> outstandingBefore = new HashSet<>(services.getOutstandingPluginIds());
        List<ExtractorApi> registryBefore = services.getExtractorSnapshot();
        String pluginId = "synthetic-canary-stage-limit-" + Long.toHexString(System.nanoTime());
        File packagePlaceholder = File.createTempFile("orbit-canary-stage-limit-", ".tmp",
                targetContext.getCacheDir());
        BasePlugin syntheticOwner = new BasePlugin() { };
        ArrayList<ExtractorApi> staged = new ArrayList<>();
        OrbitCs3HostServices.LoadTransaction transaction = null;
        OrbitCs3HostServices.LoadTransaction retry = null;
        try {
            transaction = services.beginPluginLoad(pluginId, packagePlaceholder.getAbsolutePath(), syntheticOwner);
            assertEquals(OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS,
                    transaction.getMaxStagedExtractors());
            for (int index = 0; index < OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS; index++) {
                ExtractorApi extractor = new ExtractorApi() { };
                staged.add(extractor);
                OrbitCs3HostServices.registerExtractorAPI(syntheticOwner, extractor);
            }
            assertEquals(OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS, transaction.getExtractorCount());
            assertEquals("Staging an additional unique extractor did not hit the hard transaction ceiling.",
                    OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS, expectStageLimitFailure(
                            syntheticOwner, new ExtractorApi() { }));
            assertTrue("The transaction did not retain the exceeded-limit state.",
                    transaction.hasExceededExtractorStageLimit());
            assertEquals("A failed stage changed the staged count.",
                    OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS, transaction.getExtractorCount());
            assertEquals("Staged synthetic extractors leaked into the public registry before commit.",
                    registryBefore, services.getExtractorSnapshot());
            try {
                transaction.commit();
                throw new AssertionError("A transaction that exceeded its extractor ceiling committed.");
            } catch (OrbitCs3HostServices.ExtractorStageLimitExceededException expected) {
                assertEquals(OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS, expected.limit);
            }
            transaction.rollback();
            assertEquals("Rollback left synthetic ownership or registry state behind.", outstandingBefore,
                    new HashSet<>(services.getOutstandingPluginIds()));
            assertEquals("Rollback changed the public extractor registry.", registryBefore,
                    services.getExtractorSnapshot());

            BasePlugin retryOwner = new BasePlugin() { };
            retry = services.beginPluginLoad(pluginId, packagePlaceholder.getAbsolutePath(), retryOwner);
            OrbitCs3HostServices.registerExtractorAPI(retryOwner, staged.get(0));
            assertEquals("Rollback did not release the staged extractor identity.", 1, retry.getExtractorCount());
            retry.rollback();
            assertEquals(outstandingBefore, new HashSet<>(services.getOutstandingPluginIds()));
            assertEquals(registryBefore, services.getExtractorSnapshot());
        } finally {
            if (retry != null) {
                try { retry.rollback(); } catch (RuntimeException ignored) { }
            }
            if (transaction != null) {
                try { transaction.rollback(); } catch (RuntimeException ignored) { }
            }
            if (!packagePlaceholder.delete() && packagePlaceholder.exists()) {
                throw new IOException("Synthetic transaction placeholder cleanup failed.");
            }
        }
    }

    private static List<VideoClickAction> snapshotPinnedBuiltinActionRegistry() {
        List<VideoClickAction> actions = new ArrayList<>(
                VideoClickActionHolder.INSTANCE.getAllVideoClickActions());
        assertEquals("The built-in action registry must retain all 20 pinned slots.",
                PINNED_BUILTIN_VIDEO_ACTION_TYPES.length, actions.size());
        for (int index = 0; index < PINNED_BUILTIN_VIDEO_ACTION_TYPES.length; index++) {
            assertEquals("The pinned built-in action type/order changed at slot " + index + ".",
                    PINNED_BUILTIN_VIDEO_ACTION_TYPES[index], actions.get(index).getClass().getName());
        }
        return actions;
    }

    private static void assertPinnedBuiltinActionRegistryRestored(
            List<VideoClickAction> actionRegistryBefore) {
        assertNotNull("The pre-load action registry snapshot is missing.", actionRegistryBefore);
        assertEquals("The pre-load action registry snapshot must strongly retain 20 built-ins.",
                PINNED_BUILTIN_VIDEO_ACTION_TYPES.length, actionRegistryBefore.size());
        List<VideoClickAction> actionRegistryAfter = new ArrayList<>(
                VideoClickActionHolder.INSTANCE.getAllVideoClickActions());
        assertEquals("Rollback changed the built-in action registry size.",
                PINNED_BUILTIN_VIDEO_ACTION_TYPES.length, actionRegistryAfter.size());
        for (int index = 0; index < PINNED_BUILTIN_VIDEO_ACTION_TYPES.length; index++) {
            assertSame("Rollback did not restore the exact action identity at slot " + index + ".",
                    actionRegistryBefore.get(index), actionRegistryAfter.get(index));
        }
    }

    private static int expectStageLimitFailure(BasePlugin owner, ExtractorApi extractor) {
        try {
            OrbitCs3HostServices.registerExtractorAPI(owner, extractor);
            throw new AssertionError("The transaction accepted extractor registration 161.");
        } catch (OrbitCs3HostServices.ExtractorStageLimitExceededException expected) {
            assertEquals(OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS, expected.limit);
            return OrbitCs3HostServices.MAX_CANARY_STAGED_EXTRACTORS;
        }
    }

    /** Reads the authorization-specific marker; it never creates, removes, or rewrites it. */
    @Test
    public void reportsPersistedStreamPlayCanaryMarkerWithoutLoadingPlugin() throws Exception {
        Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        Context targetContext = instrumentation.getTargetContext().getApplicationContext();
        assertTrue("The test target is not OrbitApplication.", targetContext instanceof OrbitApplication);
        OrbitApplication app = (OrbitApplication) targetContext;
        CS3PluginRuntime runtime = app.getCS3PluginRuntime();
        File marker = new File(targetContext.getNoBackupFilesDir(),
                CS3PluginRuntime.STREAMPLAY_V694_CANARY_CONSUMED_MARKER);
        boolean markerExists = marker.exists();
        assertEquals("Runtime marker read disagrees with the persisted marker file.",
                markerExists, runtime.isStreamPlayV694CanaryConsumed());
        if (markerExists) {
            assertTrue("The persisted canary marker is unexpectedly large.",
                    marker.isFile() && marker.length() > 0L && marker.length() <= 128L);
            assertEquals("Persisted marker does not name this authorization and pinned archive.",
                    CS3PluginRuntime.STREAMPLAY_V694_CANARY_MARKER_CONTENT,
                    readCanaryMarker(marker));
        }
        assertTrue("Marker inspection loaded StreamPlay.",
                !runtime.isLoaded(CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID));
        assertTrue("Marker inspection left a StreamPlay registration.",
                !OrbitCs3HostServices.get().getOutstandingPluginIds()
                        .contains(CS3PluginRuntime.STREAMPLAY_V694_CANARY_PLUGIN_ID));
        Log.i(TAG, "persisted_canary_marker present=" + markerExists
                + (markerExists ? " authorization="
                + CS3PluginRuntime.STREAMPLAY_V694_CANARY_AUTHORIZATION_ID : ""));
    }

    private static String readCanaryMarker(File marker) throws IOException {
        long length = marker.length();
        if (length <= 0L || length > 128L) throw new IOException("Canary marker length is invalid.");
        byte[] buffer = new byte[(int) length];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(marker)) {
            while (offset < buffer.length) {
                int count = input.read(buffer, offset, buffer.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != buffer.length || input.read() != -1) {
                throw new IOException("Canary marker changed while it was read.");
            }
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }
    private static synchronized void mark(Context context, String stage, long startElapsed) {
        if (stage == null || !stage.matches("[a-z0-9_.-]{1,64}")) return;
        String row = "stage=" + stage + " elapsed_ms="
                + (SystemClock.elapsedRealtime() - startElapsed) + "\n";
        Log.i(TAG, row.trim());
        File filesDirectory = context.getFilesDir();
        if (!filesDirectory.isDirectory() && !filesDirectory.mkdirs() && !filesDirectory.isDirectory()) {
            Log.e(TAG, "stage_journal_directory_unavailable");
            return;
        }
        try (FileOutputStream output = new FileOutputStream(new File(filesDirectory, JOURNAL_FILE), true)) {
            output.write(row.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "stage_journal_write_failed");
        }
    }

    /** Records only fixed booleans and nonnegative counts from the bounded canary result. */
    private static synchronized void markCanaryResult(
            Context context, long startElapsed, CS3PluginRuntime.CanaryResult result) {
        String row = "stage=one_shot.result.received elapsed_ms="
                + (SystemClock.elapsedRealtime() - startElapsed)
                + " result_present=" + (result == null ? 0 : 1)
                + " success=" + (result != null && result.success ? 1 : 0)
                + " cleanup_complete=" + (result != null && result.cleanupComplete ? 1 : 0)
                + " providers=" + (result == null ? 0 : Math.max(0, result.stagedProviderCount))
                + " extractors=" + (result == null ? 0 : Math.max(0, result.stagedExtractorCount))
                + " search_results=" + (result == null ? 0 : Math.max(0, result.providerSearchResultCount))
                + "\n";
        Log.i(TAG, row.trim());
        File filesDirectory = context.getFilesDir();
        if (!filesDirectory.isDirectory() && !filesDirectory.mkdirs() && !filesDirectory.isDirectory()) {
            Log.e(TAG, "stage_journal_directory_unavailable");
            return;
        }
        try (FileOutputStream output = new FileOutputStream(new File(filesDirectory, JOURNAL_FILE), true)) {
            output.write(row.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            Log.e(TAG, "stage_journal_write_failed");
        }
    }

    private static void markAvailable(AtomicReference<Context> contextReference,
                                      String stage, long startElapsed) {
        Context context = contextReference.get();
        if (context != null) mark(context, stage, startElapsed);
        else Log.i(TAG, "stage=" + stage + " elapsed_ms="
                + (SystemClock.elapsedRealtime() - startElapsed));
    }

    private static String safeErrorKinds(List<String> errors) {
        StringBuilder result = new StringBuilder("[");
        int count = Math.min(errors.size(), 8);
        for (int index = 0; index < count; index++) {
            if (index > 0) result.append(',');
            String error = errors.get(index);
            String lower = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
            String kind;
            if (lower.contains("invalid dex header")) kind = "invalid_dex_header";
            else if (lower.contains("invalid dex size or byte order")) kind = "invalid_dex_layout";
            else if (lower.contains("preflight limit") || lower.contains("excessive declared size")) {
                kind = "dex_preflight_limit";
            } else if (lower.contains("resource-bearing manifest requires the android plugin base class")) {
                kind = "android_plugin_base_missing";
            } else if (lower.contains("manifest plugin class does not extend orbit baseplugin")) {
                kind = "base_plugin_hierarchy_mismatch";
            } else if (lower.contains("manifest plugin class is absent from dex")) {
                kind = "manifest_class_missing";
            } else if (lower.contains("archive contains no classes")) {
                kind = "dex_entry_missing";
            } else if (lower.startsWith("ioexception:")) kind = "dex_archive_io_error";
            else if (lower.startsWith("securityexception:")) kind = "archive_access_error";
            else if (lower.startsWith("outofmemoryerror:")) kind = "dex_memory_limit";
            else if (lower.startsWith("illegalargumentexception:")) kind = "invalid_dex_reference";
            else kind = "other_static_preflight_error";
            result.append(kind);
        }
        if (errors.size() > count) result.append(",…+").append(errors.size() - count);
        return result.append(']').toString();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

}
