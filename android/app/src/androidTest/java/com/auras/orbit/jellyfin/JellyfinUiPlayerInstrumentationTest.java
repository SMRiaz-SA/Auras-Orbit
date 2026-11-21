package com.auras.orbit.jellyfin;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.jellyfin.JellyfinFixtureServer.CapturedRequest;
import com.auras.orbit.jellyfin.JellyfinModels.Connection;
import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.profile.LocalProfileStore.Profile;
import com.auras.orbit.ui.OrbitShellActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Drives real Orbit Jellyfin UI and ExoPlayer against an isolated HTTPS test server. */
@RunWith(AndroidJUnit4.class)
public final class JellyfinUiPlayerInstrumentationTest {
    private static final String TARGET_PACKAGE = "com.auras.orbit.queuerender";
    private static final String PROFILE_PREFERENCES = "orbit_shell_local_profiles_v1";
    private static final String PROFILES_JSON_KEY = "profiles_json";
    private static final String ACTIVE_PROFILE_KEY = "active_profile_id";
    private static final String[] PROFILE_LIBRARY_CLEANUP_ORDER = {
            "collection_memberships", "collections", "saved_titles", "playback_progress"
    };
    private static final String SCREENSHOT_DIR = "jellyfin-ui-player-fixture";
    private static final int PLAYER_TITLE_TEXT_COLOR = Color.rgb(243, 244, 241);
    private static final long UI_WAIT_MS = 15_000L;
    private static final long REQUEST_WAIT_MS = 20_000L;

    private Context target;
    private Context testContext;
    private SharedPreferences profilePreferences;
    private LocalLibraryStore libraryStore;
    private OrbitShellActivity shellActivity;
    private JellyfinFixtureServer fixture;
    private JellyfinCredentialStore credentials;
    private Connection previousSyntheticConnection;
    private String fixtureProfileId;
    private boolean fixtureLibraryScopeValidated;
    private String previousProfilesJson;
    private String previousActiveProfileId;
    private boolean hadProfilesJson;
    private boolean hadActiveProfileId;
    private boolean profileStateCaptured;
    private boolean television;
    private UiAutomation ui;
    private final ArrayList<String> layoutObservations = new ArrayList<>();

    @Before public void prepareDisposableOrbitTarget() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testContext = InstrumentationRegistry.getInstrumentation().getContext();
        assertEquals("Jellyfin UI fixture must use the disposable queueRender variant",
                TARGET_PACKAGE, target.getPackageName());
        profilePreferences = target.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE);
        hadProfilesJson = profilePreferences.contains(PROFILES_JSON_KEY);
        previousProfilesJson = profilePreferences.getString(PROFILES_JSON_KEY, null);
        hadActiveProfileId = profilePreferences.contains(ACTIVE_PROFILE_KEY);
        previousActiveProfileId = profilePreferences.getString(ACTIVE_PROFILE_KEY, null);
        profileStateCaptured = true;
        television = target.getPackageManager().hasSystemFeature("android.software.leanback");
        ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo serviceInfo = ui.getServiceInfo();
        assertNotNull("UI automation service information is available", serviceInfo);
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(serviceInfo);

        LocalProfileStore profiles = new LocalProfileStore(target);
        Set<String> previousProfileIds = new HashSet<>();
        for (Profile profile : profiles.getProfiles()) previousProfileIds.add(profile.id);
        Profile active = profiles.createProfile("Jellyfin UI Fixture " + UUID.randomUUID());
        assertNotNull("the disposable package can create a local test profile", active);
        assertFalse("the fixture profile ID must not belong to an existing profile",
                previousProfileIds.contains(active.id));
        fixtureProfileId = active.id;
        profiles.selectProfile(fixtureProfileId);
        assertEquals("the test package selects its unique disposable playback profile",
                fixtureProfileId, profiles.getActiveProfileId());
        System.out.println("JELLYFIN_PROFILE_SETUP active=" + (active == null ? "<none>" : active.name)
                + " profiles=" + profiles.getProfiles().size()
                + " activeId=" + profiles.getActiveProfileId());

        libraryStore = ((OrbitApplication) target.getApplicationContext()).getLocalLibraryStore();
        assertNoLibraryRowsForProfile(fixtureProfileId);
        fixtureLibraryScopeValidated = true;

        credentials = new JellyfinCredentialStore(target);
        com.auras.orbit.jellyfin.JellyfinModels.Connection previous = credentials.load();
        if (previous != null) {
            assertEquals("existing target-package connection must be this synthetic fixture",
                    JellyfinFixtureServer.ACCESS_TOKEN, previous.accessToken);
            previousSyntheticConnection = previous;
            credentials.disconnect();
        }
        fixture = new JellyfinFixtureServer(testContext, television);
    }

    @After public void cleanFixtureConnectionAndTransport() throws Exception {
        try {
            if (shellActivity != null && !shellActivity.isFinishing()) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            }
        } finally {
            try {
                restoreFixtureConnectionState();
            } finally {
                try {
                    if (fixture != null) fixture.close();
                } finally {
                    try {
                        if (shellActivity != null && !shellActivity.isFinishing()) {
                            shellActivity.finish();
                        }
                    } finally {
                        try {
                            cleanFixtureLibraryRows();
                        } finally {
                            restoreProfileState();
                        }
                    }
                }
            }
        }
    }

    private void restoreFixtureConnectionState() throws Exception {
        if (credentials == null) return;
        Connection connection = credentials.load();
        if (connection != null && JellyfinFixtureServer.ACCESS_TOKEN.equals(connection.accessToken)) {
            credentials.disconnect();
        }
        if (previousSyntheticConnection != null) credentials.save(previousSyntheticConnection);
    }

    private void restoreProfileState() {
        if (!profileStateCaptured || profilePreferences == null) return;
        SharedPreferences.Editor editor = profilePreferences.edit();
        if (hadProfilesJson) editor.putString(PROFILES_JSON_KEY, previousProfilesJson);
        else editor.remove(PROFILES_JSON_KEY);
        if (hadActiveProfileId) editor.putString(ACTIVE_PROFILE_KEY, previousActiveProfileId);
        else editor.remove(ACTIVE_PROFILE_KEY);
        assertTrue("Jellyfin fixture restores the original profile list and active selection",
                editor.commit());
    }

    private void assertNoLibraryRowsForProfile(String profileId) {
        SQLiteDatabase database = libraryStore.getReadableDatabase();
        for (String tableName : new String[]{
                "playback_progress", "saved_titles", "collections", "collection_memberships"}) {
            try (android.database.Cursor cursor = database.rawQuery(
                    "SELECT COUNT(*) FROM \"" + tableName + "\" WHERE profile_id=?",
                    new String[]{profileId})) {
                assertTrue("library row count is available for " + tableName, cursor.moveToFirst());
                assertEquals("new fixture profile has no preexisting " + tableName + " rows",
                        0L, cursor.getLong(0));
            }
        }
    }

    private void cleanFixtureLibraryRows() {
        if (!fixtureLibraryScopeValidated || fixtureProfileId == null || libraryStore == null) return;
        SQLiteDatabase database = libraryStore.getWritableDatabase();
        database.beginTransaction();
        try {
            for (String tableName : PROFILE_LIBRARY_CLEANUP_ORDER) {
                database.delete(tableName, "profile_id=?", new String[]{fixtureProfileId});
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    @Test public void settingsMyMediaLibraryAndPlayerUseRealJellyfinPlayback() throws Exception {
        layoutObservations.clear();
        shellActivity = startNormalOrbitShell();
        if (findExact("Continue") != null) clickExact("Continue");

        clickExact("Settings");
        clickExact("Connections");
        awaitExact("Connect server", UI_WAIT_MS);
        clickExact("Connect server");
        setTextByHint("Server URL", fixture.baseUrl());
        setTextByHint("Username", JellyfinFixtureServer.USERNAME);
        setTextByHint("Password", JellyfinFixtureServer.PASSWORD);
        clickExact("CONNECT");

        CapturedRequest authRequest = fixture.awaitRequest("POST",
                "/jellyfin/Users/AuthenticateByName", REQUEST_WAIT_MS);
        assertNotNull("Orbit UI must authenticate against the fixture", authRequest);
        CapturedRequest serverInfo = fixture.awaitRequest("GET",
                "/jellyfin/System/Info/Public", REQUEST_WAIT_MS);
        assertNotNull("Orbit UI must read the Jellyfin server's public display name", serverInfo);
        assertEquals("public Jellyfin server info returns successfully", 200, serverInfo.responseStatus);
        CapturedRequest validateRequest = fixture.awaitRequest("GET",
                "/jellyfin/Users/Me", REQUEST_WAIT_MS);
        assertNotNull("Orbit UI must validate the saved fixture account", validateRequest);
        assertEquals("fixture authentication returns success", 200, authRequest.responseStatus);
        assertEquals("fixture identity validation returns success", 200, validateRequest.responseStatus);
        awaitExact("Connected to Jellyfin", UI_WAIT_MS);
        SystemClock.sleep(2_500L);
        capture("settings-connections");
        assertNoCleartextConnectionSecret();

        CapturedRequest libraries = fixture.awaitRequest("GET", "/jellyfin/UserViews", REQUEST_WAIT_MS);
        assertNotNull("Orbit UI must query Jellyfin libraries", libraries);
        assertEquals("Jellyfin library response succeeds", 200, libraries.responseStatus);
        assertEquals("Jellyfin library requests include ChildCount", "ChildCount",
                libraries.queryParameter("Fields"));
        CapturedRequest continueWatching = fixture.awaitRequest("GET",
                "/jellyfin/UserItems/Resume", REQUEST_WAIT_MS);
        assertNotNull("Orbit UI must query Continue Watching", continueWatching);
        assertEquals("Continue Watching response succeeds", 200, continueWatching.responseStatus);
        CapturedRequest recentlyAdded = fixture.awaitRequest("GET", "/jellyfin/Items/Latest", REQUEST_WAIT_MS);
        assertNotNull("Orbit UI must query Recently Added", recentlyAdded);
        assertEquals("Recently Added response succeeds", 200, recentlyAdded.responseStatus);
        int expectedContinueWatchingVisibleCount = television ? 4 : 3;
        int expectedRecentlyAddedVisibleCount = television ? 6 : 4;
        assertEquals("the loopback response matches the device Continue Watching target",
                expectedContinueWatchingVisibleCount, fixture.continueWatchingItemCount());
        assertEquals("the loopback response matches the device Recently Added target",
                expectedRecentlyAddedVisibleCount, fixture.recentlyAddedItemCount());
        assertEquals("the My Media inventory stays at the two approved libraries",
                2, fixture.libraryCount());
        System.out.println("JELLYFIN_MY_MEDIA_MOCKUP_COUNTS device=" + (television ? "tv" : "phone")
                + " continueWatchingVisibleTarget=" + expectedContinueWatchingVisibleCount
                + " recentlyAddedVisibleTarget=" + expectedRecentlyAddedVisibleCount
                + " libraryCount=2");

        clickFirstAvailable("Library", "My Library");
        clickExact("My Media");
        awaitExact("My Media", UI_WAIT_MS);
        awaitExact("Living Room Server, Jellyfin · Alex, Connected", UI_WAIT_MS);
        awaitExact("Continue watching Silent Meridian, Series · S1 E4, 32% watched", UI_WAIT_MS);
        awaitExact("Continue watching Northbound, 2025, 78% watched", UI_WAIT_MS);
        awaitExact("Continue watching The Glass Sea, Series · S2 E2, 14% watched", UI_WAIT_MS);
        awaitExact("Play Amber Sky, 2026", UI_WAIT_MS);
        awaitExact("Play After the Tide, 2026", UI_WAIT_MS);
        awaitExact("Play Winter Line, 2025", UI_WAIT_MS);
        awaitExact("Play Harbor Lights, 2025", UI_WAIT_MS);
        if (television) {
            awaitExact("Continue watching Harbor Lights, Series · S1 E5, 61% watched", UI_WAIT_MS);
            awaitExact("Play Northbound, 2025", UI_WAIT_MS);
            awaitExact("Play The Glass Sea, 2024", UI_WAIT_MS);
        }
        SystemClock.sleep(2_500L);
        capture("my-media-top");
        scrollUntilVisible("Browse Libraries");
        scrollUntilVisible("Movies, 126 titles, open library");
        awaitExact("Movies, 126 titles, open library", UI_WAIT_MS);
        awaitExact("TV Shows, 48 series, open library", UI_WAIT_MS);
        clickExact("Movies, 126 titles, open library");
        awaitExact("Movies", UI_WAIT_MS);
        if (!television) {
            awaitExact("Browse and play videos from this server library", UI_WAIT_MS);
        }
        CapturedRequest libraryRequest = fixture.awaitRequest("GET", "/jellyfin/Items", REQUEST_WAIT_MS);
        assertNotNull("opening a server library must request its items", libraryRequest);
        assertEquals("the selected Jellyfin library request succeeds", 200, libraryRequest.responseStatus);
        assertEquals("opening Movies sends its identifier through Jellyfin ParentId",
                JellyfinFixtureServer.MOVIES_LIBRARY_ID, libraryRequest.queryParameter("ParentId"));
        JSONArray selectedMovieItems = new JSONObject(libraryRequest.responseBody).getJSONArray("Items");
        assertEquals("Movies returns the device's expected movie count", television ? 3 : 2,
                selectedMovieItems.length());
        for (int index = 0; index < selectedMovieItems.length(); index++) {
            assertEquals("the Movies parent returns Movie items",
                    "Movie", selectedMovieItems.getJSONObject(index).getString("Type"));
        }
        scrollToTop();
        scrollUntilVisible("Amber Sky");
        awaitExact("Amber Sky", UI_WAIT_MS);
        AccessibilityNodeInfo movieLibraryTile = awaitExact("Play Amber Sky, 2026", UI_WAIT_MS);
        android.graphics.Rect movieLibraryTileBounds = new android.graphics.Rect();
        movieLibraryTile.getBoundsInScreen(movieLibraryTileBounds);
        float density = target.getResources().getDisplayMetrics().density;
        float tileWidthDp = movieLibraryTileBounds.width() / density;
        float tileHeightDp = movieLibraryTileBounds.height() / density;
        capture("selected-library");
        System.out.println("JELLYFIN_MOVIE_LIBRARY_TILE_NODE class="
                + movieLibraryTile.getClassName() + " label=" + movieLibraryTile.getContentDescription()
                + " text=" + movieLibraryTile.getText() + " density=" + density
                + " bounds=" + movieLibraryTileBounds.toShortString()
                + " widthDp=" + tileWidthDp + " heightDp=" + tileHeightDp);
        dumpAccessibilityNode(movieLibraryTile, "JELLYFIN_MOVIE_LIBRARY_TILE_CHILD", 0, 3);
        assertEquals("the compact poster keeps its complete title and year in the accessibility label",
                "Play Amber Sky, 2026", String.valueOf(movieLibraryTile.getContentDescription()));
        assertTrue("a Jellyfin Movie remains a compact poster tile (width <= 120dp; actual "
                        + tileWidthDp + "dp)", tileWidthDp <= 120f);
        assertTrue("a Jellyfin Movie remains a compact poster tile (height <= 190dp; actual "
                        + tileHeightDp + "dp)", tileHeightDp <= 190f);
        System.out.println("JELLYFIN_MOVIE_LIBRARY_TILE_BOUNDS widthDp=" + tileWidthDp
                + " heightDp=" + tileHeightDp);

        clickExact("Amber Sky");
        awaitExact("Play", UI_WAIT_MS);
        awaitExact("Overview", UI_WAIT_MS);
        assertSelectedTitleRuntime(2026, "Runtime <1 min");
        CapturedRequest poster = fixture.awaitRequest("GET", "/jellyfin/Items/"
                + JellyfinFixtureServer.ITEM_ID + "/Images/Primary", REQUEST_WAIT_MS);
        assertNotNull("title detail artwork must load from Jellyfin", poster);
        assertEquals("Jellyfin poster response succeeds", 200, poster.responseStatus);
        assertEquals("Jellyfin sends the complete synthetic poster", fixture.posterBytes(), poster.responseBytes);
        capture("title-details");

        System.out.println("JELLYFIN_UI_PHASE play-action");
        clickExact("Play");
        CapturedRequest stream = fixture.awaitRequest("GET", "/jellyfin/Videos/"
                + JellyfinFixtureServer.ITEM_ID + "/stream", REQUEST_WAIT_MS);
        assertNotNull("the normal title Play action must request the selected Jellyfin stream", stream);
        assertEquals("the fixture returns a playable video response", 200, stream.responseStatus);
        assertEquals("the fixture serves the complete test-only MP4", fixture.videoBytes(), stream.responseBytes);
        assertFixtureAuthorization(stream);
        assertFalse("the stream URL must not carry a credential query parameter",
                stream.target.toLowerCase(java.util.Locale.ROOT).contains("api_key"));

        CapturedRequest started = fixture.awaitRequest("POST", "/jellyfin/Sessions/Playing",
                REQUEST_WAIT_MS);
        assertNotNull("Media3 playback start must reach the Jellyfin observer", started);
        assertEquals("the fixture accepts the playback start event", 200, started.responseStatus);
        assertSessionEvent(started, JellyfinFixtureServer.ITEM_ID, false);
        JSONObject startedBody = started.jsonBody();
        long startedPositionTicks = startedBody.getLong("PositionTicks");
        dismissFullscreenPromptIfVisible();

        // Let the transient "Preparing Jellyfin playback…" toast expire before the TV focus capture.
        // This stays inside the player's 5.5s control-visibility window.
        if (television) SystemClock.sleep(2_500L);

        // Check TV directional focus before the progress wait can outlast the controls timeout.
        assertTvFocusedTransportMatchesMockup();

        long replayMinimumPositionTicks = Math.max(startedPositionTicks, 10_000_000L);
        CapturedRequest firstProgress = awaitProgressAdvancingBeyond(
                replayMinimumPositionTicks, REQUEST_WAIT_MS + 5_000L);
        assertNotNull("real player playback must produce an observer progress event", firstProgress);
        assertEquals("the fixture accepts the first playback progress event", 200,
                firstProgress.responseStatus);
        assertSessionEvent(firstProgress, JellyfinFixtureServer.ITEM_ID, false);
        long firstProgressPositionTicks = firstProgress.jsonBody().getLong("PositionTicks");
        assertTrue("first playback progress follows the start position",
                firstProgressPositionTicks > startedPositionTicks);
        awaitExact("≋  Sources · 1", UI_WAIT_MS);
        SystemClock.sleep(350L);
        capture("player-playing");
        assertTrue("Replay is exercised after playback has advanced beyond one second",
                firstProgressPositionTicks > 10_000_000L);
        int requestCountBeforeReplay = fixture.requests().size();
        tapControlCenter("Replay from beginning");
        assertPlaybackAccessibilityState(true);
        CapturedRequest replayProgress = awaitProgressAtOrBefore(
                requestCountBeforeReplay, 0L, REQUEST_WAIT_MS);
        assertNotNull("tapping Replay must report a playback position of zero",
                replayProgress);
        long replayPositionTicks = replayProgress.jsonBody().getLong("PositionTicks");
        assertEquals("Replay resets the playback observer position exactly to zero",
                0L, replayPositionTicks);
        System.out.println("JELLYFIN_PLAYER_REPLAY_POSITION device="
                + (television ? "tv" : "phone") + " before=" + firstProgressPositionTicks
                + " after=" + replayPositionTicks);
        awaitExact("Stop playback and return to title details", UI_WAIT_MS);
        assertPlayerControlsMatchMockup();
        assertPlaybackAccessibilityState(true);
        if (!television) assertPrimaryTransportVisualState(false, "playing");
        clickExact("Pause playback");
        awaitExact("Resume playback", UI_WAIT_MS);
        assertPlaybackAccessibilityState(false);
        assertPrimaryTransportVisualState(television, "paused");
        clickExact("Resume playback");
        awaitExact("Pause playback", UI_WAIT_MS);
        assertPlaybackAccessibilityState(true);
        assertPrimaryTransportVisualState(television, "playing");
        clickExact(television ? "Options" : "Player settings");
        awaitExact("Playback settings", UI_WAIT_MS);
        awaitExact("Playback speed", UI_WAIT_MS);
        awaitExact("Screen fit", UI_WAIT_MS);
        SystemClock.sleep(400L);
        System.out.println("JELLYFIN_PLAYER_SETTINGS_WINDOWS device=" + (television ? "tv" : "phone")
                + " " + describeSettingsWindowRoots());
        capture("player-settings");
        assertPlaybackSettingsGeometry();
        clickExact("1×");
        clickExact("1.5×");
        awaitExact("1.5×", UI_WAIT_MS);
        clickExact("Fit");
        clickExact("Zoom");
        awaitExact("Zoom", UI_WAIT_MS);
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);

        CapturedRequest progress = awaitProgressAdvancingBeyond(
                firstProgressPositionTicks, REQUEST_WAIT_MS);
        assertNotNull("real playback progress must advance across successive observer events", progress);
        assertEquals("the fixture accepts the playback progress event", 200, progress.responseStatus);
        assertSessionEvent(progress, JellyfinFixtureServer.ITEM_ID, false);
        JSONObject progressBody = progress.jsonBody();
        long progressPositionTicks = progressBody.getLong("PositionTicks");
        assertTrue("playback position advances after the start event",
                firstProgressPositionTicks > startedPositionTicks);
        assertTrue("successive progress events report strictly advancing playback position",
                progressPositionTicks > firstProgressPositionTicks);

        tapBackControlCenterWhenVisible();
        CapturedRequest stopped = fixture.awaitRequest("POST",
                "/jellyfin/Sessions/Playing/Stopped", REQUEST_WAIT_MS);
        assertNotNull("leaving the real player must stop the Jellyfin session", stopped);
        assertEquals("the fixture accepts the playback stop event", 200, stopped.responseStatus);
        assertSessionEvent(stopped, JellyfinFixtureServer.ITEM_ID, true);
        JSONObject stoppedBody = stopped.jsonBody();
        assertFalse("user exit is reported as a successful stop", stoppedBody.getBoolean("Failed"));
        long stoppedPositionTicks = stoppedBody.getLong("PositionTicks");
        assertTrue("stopped position advances beyond the last progress event",
                stoppedPositionTicks > progressPositionTicks);

        System.out.println("JELLYFIN_PLAYER_OBSERVER_POSITIONS device=" + (television ? "tv" : "phone")
                + " start=" + startedPositionTicks + " firstProgress=" + firstProgressPositionTicks
                + " progress=" + progressPositionTicks + " stop=" + stoppedPositionTicks);
        logLayoutObservations();
        assertTrue("fixture requests contain authenticated API and media calls",
                fixture.requests().size() >= 9);
        System.out.println("JELLYFIN_UI_PLAYER_ACCEPTED_TEST_DEVICE="
                + (television ? "tv" : "phone")
                + " requests=" + fixture.requests().size()
                + " videoBytes=" + fixture.videoBytes()
                + " posterBytes=" + fixture.posterBytes()
                + " rejectedTls=" + fixture.rejectedTlsHandshakes()
                + " ioFailures=" + fixture.ioFailures()
                + " streamStatus=" + stream.responseStatus
                + " startPositionTicks=" + startedPositionTicks
                + " firstProgressPositionTicks=" + firstProgressPositionTicks
                + " progressPositionTicks=" + progressPositionTicks
                + " stopPositionTicks=" + stoppedPositionTicks
                + " events=started,progress,stopped screenshots=" + screenshotNames());
    }

    private OrbitShellActivity startNormalOrbitShell() {
        Intent intent = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity launched = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertNotNull("normal Orbit shell launch must succeed", launched);
        assertTrue("normal OrbitShellActivity must render the screen", launched instanceof OrbitShellActivity);
        return (OrbitShellActivity) launched;
    }

    private void clickFirstAvailable(String... labels) {
        for (String label : labels) {
            if (findExact(label) != null) {
                clickExact(label);
                return;
            }
        }
        throw new AssertionError("None of the expected main navigation labels is visible.");
    }

    private void clickExact(String label) {
        AccessibilityNodeInfo node = awaitExact(label, UI_WAIT_MS);
        AccessibilityNodeInfo clickable = node;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        assertNotNull("visible Orbit control is actionable: " + label, clickable);
        assertTrue("Orbit control click succeeds: " + label,
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        SystemClock.sleep(250L);
    }

    private void dismissFullscreenPromptIfVisible() {
        if (findExact("Got it") != null) clickExact("Got it");
    }


    private AccessibilityNodeInfo awaitExact(String label, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        AccessibilityNodeInfo node;
        do {
            node = findExact(label);
            if (node != null) return node;
            SystemClock.sleep(80L);
        } while (SystemClock.elapsedRealtime() < deadline);
        dumpAccessibilityFailureState("missing visible text: " + label);
        throw new AssertionError("Expected visible text is missing from the Orbit UI: " + label);
    }

    private void dumpAccessibilityFailureState(String message) {
        System.out.println("JELLYFIN_UI_FAILURE=" + message);
        AccessibilityNodeInfo activeRoot = ui.getRootInActiveWindow();
        if (activeRoot == null) {
            System.out.println("JELLYFIN_UI_FAILURE_ACTIVE_ROOT=<none>");
        } else {
            System.out.println("JELLYFIN_UI_FAILURE_ACTIVE_PACKAGE=" + activeRoot.getPackageName());
            dumpAccessibilityNode(activeRoot, "JELLYFIN_UI_FAILURE_ACTIVE", 0, 5);
        }
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo windowRoot = window.getRoot();
            if (windowRoot == null) continue;
            System.out.println("JELLYFIN_UI_FAILURE_WINDOW type=" + window.getType()
                    + " layer=" + window.getLayer() + " package=" + windowRoot.getPackageName());
            dumpAccessibilityNode(windowRoot, "JELLYFIN_UI_FAILURE_WINDOW", 0, 3);
        }
    }

    private void assertPlayerControlsMatchMockup() {
        Rect screen = new Rect();
        AccessibilityNodeInfo rootNode = ui.getRootInActiveWindow();
        assertNotNull("player accessibility root is available", rootNode);
        rootNode.getBoundsInScreen(screen);

        AccessibilityNodeInfo backNode = awaitExact(
                "Stop playback and return to title details", UI_WAIT_MS);
        AccessibilityNodeInfo replayNode = awaitExact("Replay from beginning", UI_WAIT_MS);
        Rect back = visibleBounds("Stop playback and return to title details");
        Rect replay = visibleBounds("Replay from beginning");
        Rect rewind = visibleBounds("↶\n10");
        Rect playPause = visibleBounds("Ⅱ");
        Rect forward = visibleBounds("↷\n10");
        AccessibilityNodeInfo rewindNode = awaitExact("Rewind 10 seconds", UI_WAIT_MS);
        AccessibilityNodeInfo forwardNode = awaitExact("Forward 10 seconds", UI_WAIT_MS);
        System.out.println("JELLYFIN_PLAYER_NAV_BOUNDS device=" + (television ? "tv" : "phone")
                + " screen=" + screen.toShortString() + " back=" + back.toShortString()
                + " replay=" + replay.toShortString());
        int backSize = dpPixels(56);
        int replaySize = dpPixels(30);
        recordMockup("Back and Replay hit areas are compact, centered 56dp and 30dp controls",
                Math.abs(back.width() - backSize) <= 1
                        && Math.abs(back.height() - backSize) <= 1
                        && Math.abs(replay.width() - replaySize) <= 1
                        && Math.abs(replay.height() - replaySize) <= 1,
                "back=" + back.toShortString() + " replay=" + replay.toShortString());
        assertTrue("Back remains clickable and focusable",
                backNode.isClickable() && backNode.isFocusable());
        assertTrue("Replay remains clickable and focusable",
                replayNode.isClickable() && replayNode.isFocusable());
        recordMockup("Back and Replay icon surfaces are clickable and focusable",
                backNode.isClickable() && backNode.isFocusable()
                        && replayNode.isClickable() && replayNode.isFocusable(),
                "backClickable=" + backNode.isClickable() + " backFocusable=" + backNode.isFocusable()
                        + " replayClickable=" + replayNode.isClickable()
                        + " replayFocusable=" + replayNode.isFocusable());
        int navTargetGap = replay.left - back.right;
        int expectedNavTargetGap = dpPixels(-8);
        int effectiveBackWidth = Math.max(0, replay.left - back.left);
        int expectedEffectiveBackWidth = dpPixels(48);
        System.out.println("JELLYFIN_PLAYER_NAV_OVERLAP device=" + (television ? "tv" : "phone")
                + " back=" + back.toShortString() + " replay=" + replay.toShortString()
                + " gapPx=" + navTargetGap + " effectiveBackWidthPx=" + effectiveBackWidth);
        recordMockup("Back and Replay overlap by 8dp while Back retains a 48dp non-overlap hit region",
                Math.abs(navTargetGap - expectedNavTargetGap) <= 1
                        && Math.abs(effectiveBackWidth - expectedEffectiveBackWidth) <= 1,
                "back=" + back.toShortString() + " replay=" + replay.toShortString()
                        + " gapPx=" + navTargetGap + " expectedGapPx=" + expectedNavTargetGap
                        + " effectiveBackWidthPx=" + effectiveBackWidth
                        + " expectedEffectiveBackWidthPx=" + expectedEffectiveBackWidth);
        recordMockup("Back and Replay are grouped together at the top-left of the player",
                back.left < replay.left
                        && replay.right <= screen.left + Math.round(screen.width() * 0.30f)
                        && Math.abs(back.centerY() - replay.centerY()) <= dpPixels(24)
                        && back.top <= screen.top + Math.round(screen.height() * 0.20f),
                "back=" + back.toShortString() + " replay=" + replay.toShortString());

        int sideTransport = dpPixels(television ? 48 : 76);
        int mainTransport = dpPixels(television ? 65 : 104);
        System.out.println("JELLYFIN_PLAYER_CENTER_BOUNDS device=" + (television ? "tv" : "phone")
                + " rewind=" + rewind.toShortString() + " playPause=" + playPause.toShortString()
                + " forward=" + forward.toShortString());
        recordControlSize("rewind", rewind, sideTransport);
        if (!television) recordControlSize("play/pause", playPause, mainTransport);
        recordControlSize("forward", forward, sideTransport);
        int expectedCenterOffset = dpPixels(television ? 10.5f : 16f);
        int observedCenterOffset = playPause.centerX() - screen.centerX();
        recordMockup("center transport group follows the mockup's +10 CSS pixel offset",
                Math.abs(observedCenterOffset - expectedCenterOffset) <= dpPixels(2),
                "device=" + (television ? "tv" : "phone")
                        + " playPauseCenterX=" + playPause.centerX()
                        + " screenCenterX=" + screen.centerX()
                        + " observedOffsetPx=" + observedCenterOffset
                        + " expectedOffsetPx=" + expectedCenterOffset);
        assertEquals("Rewind retains its visible 10-second label", "↶\n10",
                rewindNode.getText().toString());
        assertEquals("Forward retains its visible 10-second label", "↷\n10",
                forwardNode.getText().toString());
        assertTrue("Rewind remains clickable and focusable",
                rewindNode.isClickable() && rewindNode.isFocusable());
        assertTrue("Forward remains clickable and focusable",
                forwardNode.isClickable() && forwardNode.isFocusable());
        recordMockup("10-second transport controls keep their visible labels and accessible focus",
                "↶\n10".contentEquals(rewindNode.getText())
                        && "↷\n10".contentEquals(forwardNode.getText())
                        && rewindNode.isClickable() && rewindNode.isFocusable()
                        && forwardNode.isClickable() && forwardNode.isFocusable(),
                "rewindText=" + rewindNode.getText() + " rewindFocusable=" + rewindNode.isFocusable()
                        + " rewindClickable=" + rewindNode.isClickable()
                        + " forwardText=" + forwardNode.getText()
                        + " forwardFocusable=" + forwardNode.isFocusable()
                        + " forwardClickable=" + forwardNode.isClickable());
        recordMockup("seek controls flank Play/Pause on one horizontal line",
                rewind.right < playPause.left && playPause.right < forward.left
                        && Math.abs(rewind.centerY() - playPause.centerY()) <= dpPixels(2)
                        && Math.abs(playPause.centerY() - forward.centerY()) <= dpPixels(2),
                "rewind=" + rewind.toShortString() + " play=" + playPause.toShortString()
                        + " forward=" + forward.toShortString());
        if (television) {
            // The base CSS gap is 18px, but the focused center button grows by 1.13x.
            // Its visible edge expands toward each side by half that growth, so compare
            // the focused screenshot against the reduced visible gap.
            float focusedExpansionDp = 65f * (1.13f - 1f) / 2f;
            int expectedGap = dpPixels(18f - focusedExpansionDp);
            int rewindToPlayGap = playPause.left - rewind.right;
            int playToForwardGap = forward.left - playPause.right;
            recordMockup("TV seek controls match the mockup spacing with the focused center scale",
                    Math.abs(rewindToPlayGap - expectedGap) <= dpPixels(1)
                            && Math.abs(playToForwardGap - expectedGap) <= dpPixels(1),
                    "rewindToPlayGapPx=" + rewindToPlayGap
                            + " playToForwardGapPx=" + playToForwardGap
                            + " expectedGapPx=" + expectedGap);
        }

        Rect title = visibleBounds("Amber Sky");
        Rect sources = visibleBounds("≋  Sources · 1");
        Rect tracks = visibleBounds("♫  Tracks");
        Rect timeline = visibleBounds("Playback timeline");
        Rect options = visibleBounds(television ? "Options" : "Player settings");
        int actionHeight = dpPixels(television ? 33 : 54);
        recordMockup("Source and Tracks action heights match the scaled mockup",
                Math.abs(sources.height() - actionHeight) <= 1
                        && Math.abs(tracks.height() - actionHeight) <= 1,
                "sources=" + sources.toShortString() + " tracks=" + tracks.toShortString());
        recordMockup("Options action height matches the scaled mockup",
                Math.abs(options.height() - actionHeight) <= 1,
                "options=" + options.toShortString());
        System.out.println("JELLYFIN_PLAYER_ACTION_BOUNDS device=" + (television ? "tv" : "phone")
                + " title=" + title.toShortString() + " sources=" + sources.toShortString()
                + " tracks=" + tracks.toShortString() + " options=" + options.toShortString()
                + " timeline=" + timeline.toShortString());
        recordMockup("movie title is vertically centered with Back and Replay when no episode caption exists",
                Math.abs(title.centerY() - back.centerY()) <= dpPixels(2),
                "title=" + title.toShortString() + " back=" + back.toShortString()
                        + " verticalDeltaPx=" + (title.centerY() - back.centerY()));
        if (television) {
            AccessibilityNodeInfo orbitMarkNode = findExact("Auras Orbit");
            Rect orbitMark = new Rect();
            if (orbitMarkNode != null) orbitMarkNode.getBoundsInScreen(orbitMark);
            recordMockup("TV topbar contains the visible Orbit mark at the scaled mockup size",
                    !orbitMark.isEmpty()
                            && Math.abs(orbitMark.width() - dpPixels(32)) <= dpPixels(2)
                            && Math.abs(orbitMark.height() - dpPixels(32)) <= dpPixels(2),
                    "mark=" + orbitMark.toShortString());
            recordMockup("TV title block follows Back/Replay and the Orbit mark at the top",
                    replay.right <= title.left
                            && (!orbitMark.isEmpty() && replay.right <= orbitMark.left
                                && orbitMark.right <= title.left)
                            && title.top <= screen.top + Math.round(screen.height() * 0.20f),
                    "replay=" + replay.toShortString() + " mark=" + orbitMark.toShortString()
                            + " title=" + title.toShortString());
            recordMockup("TV source, tracks, and options controls are ordered on the bottom row",
                    sources.left < tracks.left && tracks.left < options.left
                            && timeline.bottom <= sources.top
                            && options.bottom >= screen.bottom - Math.round(screen.height() * 0.05f),
                    "timeline=" + timeline.toShortString() + " sources=" + sources.toShortString()
                            + " tracks=" + tracks.toShortString() + " options=" + options.toShortString());
            Rect actionGroup = new Rect(sources);
            actionGroup.union(tracks);
            actionGroup.union(options);
            int centerTolerance = dpPixels(24);
            recordMockup("TV bottom actions are centered in the timeline area",
                    Math.abs(actionGroup.centerX() - screen.centerX()) <= centerTolerance,
                    "actions=" + actionGroup.toShortString() + " screen=" + screen.toShortString()
                            + " centerDeltaPx=" + (actionGroup.centerX() - screen.centerX()));
            Rect timeBounds = visibleBoundsStartingWith("0:");
            float density = target.getResources().getDisplayMetrics().density;
            float titleHeightDp = title.height() / density;
            float timeHeightDp = timeBounds.height() / density;
            recordMockup("TV title typography follows the scaled 13sp target",
                    titleHeightDp <= 22f, "heightDp=" + titleHeightDp);
            recordMockup("TV timeline typography follows the scaled 8sp target",
                    timeHeightDp <= 15f, "time=" + timeBounds.toShortString() + " heightDp=" + timeHeightDp);
        } else {
            Rect rightActionGroup = new Rect(sources);
            rightActionGroup.union(tracks);
            rightActionGroup.union(options);
            int freeLaneLeft = replay.right + dpPixels(10);
            int freeLaneRight = rightActionGroup.left - dpPixels(3);
            int freeLaneCenter = (freeLaneLeft + freeLaneRight) / 2;
            Rect freeLane = new Rect(freeLaneLeft, title.top, freeLaneRight, title.bottom);
            Bitmap titleScreenshot = ui.takeScreenshot();
            assertNotNull("player screenshot is available to measure rendered title ink", titleScreenshot);
            Rect titleInk;
            try {
                titleInk = renderedTextInkBounds(titleScreenshot, freeLane,
                        PLAYER_TITLE_TEXT_COLOR, 24);
            } finally {
                titleScreenshot.recycle();
            }
            float titleInkCenterX = titleInk.isEmpty()
                    ? Float.NaN : (titleInk.left + titleInk.right) / 2f;
            int titleInkCenterTolerance = dpPixels(3);
            System.out.println("JELLYFIN_PLAYER_PHONE_TITLE_INK lane=" + freeLane.toShortString()
                    + " renderedInk=" + titleInk.toShortString()
                    + " inkCenterX=" + titleInkCenterX
                    + " laneCenterX=" + freeLaneCenter);
            recordMockup("phone rendered title ink is centered in the free lane between navigation and actions",
                    !titleInk.isEmpty()
                            && titleInk.left >= freeLane.left
                            && titleInk.right <= freeLane.right
                            && Math.abs(titleInkCenterX - freeLaneCenter) <= titleInkCenterTolerance,
                    "renderedInk=" + titleInk.toShortString()
                            + " lane=" + freeLane.toShortString()
                            + " inkCenterX=" + titleInkCenterX
                            + " laneCenterX=" + freeLaneCenter
                            + " replay=" + replay.toShortString()
                            + " rightActions=" + rightActionGroup.toShortString());
            recordMockup("phone rendered title ink follows Back/Replay before top actions",
                    !titleInk.isEmpty() && titleInk.left >= freeLane.left
                            && titleInk.right <= freeLane.right,
                    "renderedInk=" + titleInk.toShortString()
                            + " lane=" + freeLane.toShortString());
            recordMockup("phone Sources, Tracks, and Player settings stay ordered across the top-right",
                    sources.left < tracks.left && tracks.left < options.left
                            && Math.abs(sources.top - tracks.top) <= dpPixels(12)
                            && Math.abs(tracks.top - options.top) <= dpPixels(12),
                    "sources=" + sources.toShortString() + " tracks=" + tracks.toShortString()
                            + " options=" + options.toShortString());
            recordMockup("phone timeline remains near the bottom edge",
                    timeline.bottom >= screen.bottom - Math.round(screen.height() * 0.20f),
                    "timeline=" + timeline.toShortString() + " screen=" + screen.toShortString());
            float density = target.getResources().getDisplayMetrics().density;
            float titleHeightDp = title.height() / density;
            recordMockup("phone title typography follows the scaled 16sp target",
                    titleHeightDp <= 27f, "heightDp=" + titleHeightDp);
        }
    }

    private void assertPlaybackAccessibilityState(boolean isPlaying) throws Exception {
        String description = isPlaying ? "Pause playback" : "Resume playback";
        String visibleGlyph = isPlaying ? "Ⅱ" : "▶";
        AccessibilityNodeInfo control = awaitExact(description, UI_WAIT_MS);
        assertTrue(description + " remains clickable", control.isClickable());
        assertTrue(description + " remains focusable", control.isFocusable());
        assertEquals(description + " exposes the current playing state", isPlaying, control.isSelected());
        assertEquals(description + " keeps the matching visible glyph", visibleGlyph,
                control.getText().toString().trim());
        System.out.println("JELLYFIN_PLAYER_ACCESSIBLE_STATE description=" + description
                + " selected=" + control.isSelected() + " clickable=" + control.isClickable()
                + " focusable=" + control.isFocusable() + " glyph=" + visibleGlyph);
    }

    private void assertPrimaryTransportVisualState(boolean focused, String playbackState)
            throws Exception {
        String description = "paused".equals(playbackState) ? "Resume playback" : "Pause playback";
        AccessibilityNodeInfo control = awaitExact(description, UI_WAIT_MS);
        if (television) {
            assertEquals("TV primary transport has the expected natural focus state",
                    focused, control.isFocused());
        }
        Rect bounds = new Rect();
        control.getBoundsInScreen(bounds);
        int expectedSurface = focused ? Color.WHITE : 0xFFF2F3F0;
        int actualSurface = sampleTransportSurfaceColor(bounds, dpPixels(24), 0);
        recordMockup("primary transport uses the mockup " + (focused ? "focused" : "base")
                        + " light surface while " + playbackState,
                isNearColor(actualSurface, expectedSurface, focused ? 1 : 18),
                "focused=" + focused + " expected=" + colorHex(expectedSurface)
                        + " actual=" + colorHex(actualSurface) + " bounds=" + bounds.toShortString());
        boolean darkGlyph = hasDarkPrimaryGlyph(bounds);
        recordMockup("primary transport keeps its dark glyph visible while " + playbackState,
                darkGlyph, "focused=" + focused + " bounds=" + bounds.toShortString());
        System.out.println("JELLYFIN_PRIMARY_VISUAL_STATE device=" + (television ? "tv" : "phone")
                + " state=" + playbackState + " focused=" + focused
                + " surface=" + colorHex(actualSurface) + " darkGlyph=" + darkGlyph
                + " bounds=" + bounds.toShortString());
    }

    private void assertTvFocusedTransportMatchesMockup() throws Exception {
        if (!television) return;
        AccessibilityNodeInfo mainControl = awaitExact("Pause playback", UI_WAIT_MS);
        System.out.println("JELLYFIN_TV_DPAD_INITIAL mainFocused=" + mainControl.isFocused()
                + " focusable=" + mainControl.isFocusable() + " current=" + describeTransportFocus());
        assertTrue("TV player opens with the main transport focused", mainControl.isFocused());
        Rect mainBounds = new Rect();
        mainControl.getBoundsInScreen(mainBounds);
        int expectedMain = Math.round(dpPixels(65) * 1.13f);
        Rect mainSurface = focusedTransportSurfaceBounds(mainBounds, Color.WHITE, 1);
        recordMockup("TV focused main transport scales to the 1.13x mockup size",
                Math.abs(mainSurface.width() - expectedMain) <= dpPixels(3)
                        && Math.abs(mainSurface.height() - expectedMain) <= dpPixels(3)
                        && Math.abs(mainSurface.centerX() - mainBounds.centerX()) <= dpPixels(2)
                        && Math.abs(mainSurface.centerY() - mainBounds.centerY()) <= dpPixels(2),
                "focusedPixels=" + mainSurface.toShortString() + " node=" + mainBounds.toShortString()
                        + " expectedSizePx=" + expectedMain);
        assertPrimaryTransportVisualState(true, "playing");
        System.out.println("JELLYFIN_TV_MAIN_TRANSPORT_FOCUS bounds=" + mainBounds.toShortString()
                + " renderedPixels=" + mainSurface.toShortString()
                + " expectedSizePx=" + expectedMain);
        capture("player-main-focused");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        SystemClock.sleep(150L);
        AccessibilityNodeInfo sideControl = awaitExact("Rewind 10 seconds", UI_WAIT_MS);
        String focusAfterLeft = describeTransportFocus();
        System.out.println("JELLYFIN_TV_DPAD_LEFT current=" + focusAfterLeft
                + " rewindFocused=" + sideControl.isFocused());
        assertTrue("TV D-pad left moves focus to the rewind transport; current=" + focusAfterLeft,
                sideControl.isFocused());
        Rect sideBounds = new Rect();
        sideControl.getBoundsInScreen(sideBounds);
        int expectedSide = Math.round(dpPixels(48) * 1.13f);
        Rect sideSurface = focusedTransportSurfaceBounds(sideBounds, 0xFF343B40, 12);
        recordMockup("TV focused side transport scales to the 1.13x mockup size",
                Math.abs(sideSurface.width() - expectedSide) <= dpPixels(3)
                        && Math.abs(sideSurface.height() - expectedSide) <= dpPixels(3)
                        && Math.abs(sideSurface.centerX() - sideBounds.centerX()) <= dpPixels(2)
                        && Math.abs(sideSurface.centerY() - sideBounds.centerY()) <= dpPixels(2),
                "focusedPixels=" + sideSurface.toShortString() + " node=" + sideBounds.toShortString()
                        + " expectedSizePx=" + expectedSide);
        int sideColor = sampleTransportSurfaceColor(sideBounds, dpPixels(20), 0);
        recordMockup("TV focused side transport uses the dark-gray surface and white glyph",
                isNearColor(sideColor, 0xFF343B40, 18),
                "sampleRgb=" + colorHex(sideColor) + " bounds=" + sideSurface.toShortString());
        System.out.println("JELLYFIN_TV_SIDE_TRANSPORT_FOCUS bounds=" + sideBounds.toShortString()
                + " renderedPixels=" + sideSurface.toShortString()
                + " expectedSizePx=" + expectedSide + " sampleRgb=" + colorHex(sideColor));
        capture("player-side-focused");

        AccessibilityNodeInfo unfocusedMain = awaitExact("Pause playback", UI_WAIT_MS);
        assertFalse("TV main transport loses focus when D-pad left focuses Rewind",
                unfocusedMain.isFocused());
        assertPrimaryTransportVisualState(false, "playing");
        capture("player-main-unfocused");

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        SystemClock.sleep(150L);
        AccessibilityNodeInfo returnedMainControl = awaitExact("Pause playback", UI_WAIT_MS);
        assertTrue("TV D-pad right returns focus to the playing transport",
                returnedMainControl.isFocused());
    }

    private String describeTransportFocus() {
        String[] labels = {"Stop playback and return to title details", "Replay from beginning",
                "Rewind 10 seconds", "Pause playback", "Forward 10 seconds", "Playback timeline", "Options"};
        for (String label : labels) {
            AccessibilityNodeInfo node = findExact(label);
            if (node != null && node.isFocused()) return label;
        }
        return "none";
    }

    private int sampleTransportSurfaceColor(Rect bounds, int horizontalOffset, int verticalOffset) {
        Bitmap screenshot = ui.takeScreenshot();
        assertNotNull("TV focus screenshot is available", screenshot);
        try {
            int x = Math.max(0, Math.min(screenshot.getWidth() - 1,
                    bounds.centerX() + horizontalOffset));
            int y = Math.max(0, Math.min(screenshot.getHeight() - 1,
                    bounds.centerY() + verticalOffset));
            return screenshot.getPixel(x, y);
        } finally {
            screenshot.recycle();
        }
    }

    private boolean hasDarkPrimaryGlyph(Rect bounds) {
        Bitmap screenshot = ui.takeScreenshot();
        assertNotNull("primary transport glyph screenshot is available", screenshot);
        try {
            int radius = dpPixels(18);
            int left = Math.max(bounds.left, bounds.centerX() - radius);
            int right = Math.min(bounds.right - 1, bounds.centerX() + radius);
            int top = Math.max(bounds.top, bounds.centerY() - radius);
            int bottom = Math.min(bounds.bottom - 1, bounds.centerY() + radius);
            for (int y = top; y <= bottom; y++) {
                for (int x = left; x <= right; x++) {
                    if (isNearColor(screenshot.getPixel(x, y), 0xFF101316, 28)) return true;
                }
            }
            return false;
        } finally {
            screenshot.recycle();
        }
    }

    private Rect focusedTransportSurfaceBounds(Rect semanticBounds, int expectedColor,
                                               int colorTolerance) {
        Bitmap screenshot = ui.takeScreenshot();
        assertNotNull("TV focused transport screenshot is available", screenshot);
        try {
            int radius = dpPixels(45);
            int left = Math.max(0, semanticBounds.centerX() - radius);
            int right = Math.min(screenshot.getWidth() - 1, semanticBounds.centerX() + radius);
            int top = Math.max(0, semanticBounds.centerY() - radius);
            int bottom = Math.min(screenshot.getHeight() - 1, semanticBounds.centerY() + radius);
            int width = right - left + 1;
            int height = bottom - top + 1;
            boolean[] visited = new boolean[width * height];
            int[] queue = new int[width * height];
            int seedX = Math.min(right, semanticBounds.centerX() + dpPixels(15));
            int seedY = semanticBounds.centerY();
            int seed = (seedY - top) * width + seedX - left;
            if (!isNearColor(screenshot.getPixel(seedX, seedY), expectedColor, colorTolerance)) {
                return new Rect();
            }
            queue[0] = seed;
            visited[seed] = true;
            int read = 0;
            int write = 1;
            int minX = seedX;
            int minY = seedY;
            int maxX = seedX;
            int maxY = seedY;
            while (read < write) {
                int index = queue[read++];
                int x = left + index % width;
                int y = top + index / width;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                int[] neighbors = {index - 1, index + 1, index - width, index + width};
                for (int neighbor : neighbors) {
                    if (neighbor < 0 || neighbor >= visited.length || visited[neighbor]) continue;
                    int neighborX = left + neighbor % width;
                    int neighborY = top + neighbor / width;
                    if (Math.abs(neighborX - x) + Math.abs(neighborY - y) != 1) continue;
                    visited[neighbor] = true;
                    if (isNearColor(screenshot.getPixel(neighborX, neighborY),
                            expectedColor, colorTolerance)) {
                        queue[write++] = neighbor;
                    }
                }
            }
            Rect bounds = new Rect(minX, minY, maxX + 1, maxY + 1);
            System.out.println("JELLYFIN_TV_FOCUSED_TRANSPORT_PIXELS expected=" + colorHex(expectedColor)
                    + " bounds=" + bounds.toShortString());
            return bounds;
        } finally {
            screenshot.recycle();
        }
    }

    private boolean isNearColor(int actual, int expected, int tolerance) {
        return Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance
                && Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance
                && Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance;
    }

    private String colorHex(int color) {
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private void assertSelectedTitleRuntime(int expectedYear, String expectedRuntime) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        String prefix = expectedYear + "  ·  Movie  ·  Runtime ";
        AccessibilityNodeInfo metadata = findTextStartingWith(root,
                prefix);
        assertNotNull("selected title details show runtime metadata in the active window", metadata);
        String actual = metadata.getText().toString().trim();
        String expected = expectedYear + "  ·  Movie  ·  " + expectedRuntime;
        assertEquals("selected title runtime must match its positive sub-minute duration",
                expected, actual);
    }

    private void assertPlaybackSettingsGeometry() {
        android.util.DisplayMetrics display = target.getResources().getDisplayMetrics();
        Rect screen = new Rect(0, 0, display.widthPixels, display.heightPixels);
        Rect reportedPanel = settingsPanelBoundsFromAccessibility();
        Rect panel = visibleSettingsPanelBoundsFromScreenshot();
        // The screenshot crosswalk maps CSS 14px/13px to 42px/39px on the 3px/dp phone.
        int tolerance = 3;
        System.out.println("JELLYFIN_PLAYER_SETTINGS_BOUNDS device=" + (television ? "tv" : "phone")
                + " screen=" + screen.toShortString() + " visiblePanel=" + panel.toShortString()
                + " accessibilityPanel=" + reportedPanel.toShortString());
        if (panel.isEmpty()) {
            recordMockup("Playback settings panel bounds are measurable from its visible controls", false,
                    describeSettingsWindowRoots());
            return;
        }
        if (television) {
            int expectedWidth = Math.min(dpPixels(410), Math.round(screen.width() * 0.42f));
            recordMockup("TV playback settings is a scaled right drawer aligned to the screen edge",
                    Math.abs(screen.right - panel.right) <= tolerance
                            && Math.abs(panel.width() - expectedWidth) <= tolerance
                            && Math.abs(panel.top - screen.top) <= tolerance
                            && Math.abs(panel.bottom - screen.bottom) <= tolerance,
                    "panel=" + panel.toShortString() + " expectedWidthPx=" + expectedWidth);
        } else {
            int expectedMargin = dpPixels(14);
            int expectedBottom = dpPixels(13);
            recordMockup("phone playback settings is a scaled bottom sheet with 14dp sides and 13dp bottom",
                    Math.abs(panel.left - (screen.left + expectedMargin)) <= tolerance
                            && Math.abs((screen.right - panel.right) - expectedMargin) <= tolerance
                            && Math.abs((screen.bottom - panel.bottom) - expectedBottom) <= tolerance,
                    "panel=" + panel.toShortString() + " expectedSidePx=" + expectedMargin
                            + " expectedBottomPx=" + expectedBottom);
            recordMockup("phone playback settings stays within the 78% height cap",
                    panel.height() <= Math.round(screen.height() * 0.78f) + tolerance,
                    "panelHeightPx=" + panel.height() + " screenHeightPx=" + screen.height());
        }
    }

    private Rect visibleSettingsPanelBoundsFromScreenshot() {
        Bitmap screenshot = ui.takeScreenshot();
        assertNotNull("settings screenshot is available for visible bounds", screenshot);
        try {
            int centerX = television ? screenshot.getWidth() * 3 / 4 : screenshot.getWidth() / 2;
            int minimumRunWidth = television ? Math.round(screenshot.getWidth() * 0.35f)
                    : Math.round(screenshot.getWidth() * 0.60f);
            int left = screenshot.getWidth();
            int right = -1;
            int top = screenshot.getHeight();
            int bottom = -1;
            for (int y = 0; y < screenshot.getHeight(); y++) {
                if (!isSettingsSurfacePixel(screenshot.getPixel(centerX, y))) continue;
                int rowLeft = centerX;
                int rowRight = centerX;
                while (rowLeft > 0
                        && isSettingsSurfacePixel(screenshot.getPixel(rowLeft - 1, y))) rowLeft--;
                while (rowRight < screenshot.getWidth() - 1
                        && isSettingsSurfacePixel(screenshot.getPixel(rowRight + 1, y))) rowRight++;
                if (rowRight - rowLeft + 1 < minimumRunWidth) continue;
                left = Math.min(left, rowLeft);
                right = Math.max(right, rowRight);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            assertTrue("visible settings panel has a wide surface run through its center",
                    right >= left && bottom >= top);

            Rect bounds = new Rect(left, top, right + 1, bottom + 1);
            System.out.println("JELLYFIN_PLAYER_SETTINGS_SCREEN_PIXELS bounds=" + bounds.toShortString()
                    + " device=" + (television ? "tv" : "phone")
                    + " dimensions=" + screenshot.getWidth() + "x" + screenshot.getHeight());
            return bounds;
        } finally {
            screenshot.recycle();
        }
    }

    private boolean isSettingsSurfacePixel(int color) {
        return Math.abs(Color.red(color) - 23) <= 3
                && Math.abs(Color.green(color) - 27) <= 3
                && Math.abs(Color.blue(color) - 31) <= 3;
    }

    private Rect settingsPanelBoundsFromAccessibility() {
        AccessibilityNodeInfo heading = awaitExact("Playback settings", UI_WAIT_MS);
        AccessibilityNodeInfo close = awaitExact("Close playback settings", UI_WAIT_MS);
        AccessibilityNodeInfo speed = awaitExact("Playback speed", UI_WAIT_MS);
        AccessibilityNodeInfo fit = awaitExact("Screen fit", UI_WAIT_MS);
        ArrayList<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo ancestor = heading; ancestor != null; ancestor = ancestor.getParent()) {
            if (isAncestorOrSelf(ancestor, close)
                    && isAncestorOrSelf(ancestor, speed)
                    && isAncestorOrSelf(ancestor, fit)) {
                candidates.add(ancestor);
            }
        }
        Rect smallest = new Rect();
        long smallestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo candidate : candidates) {
            Rect bounds = new Rect();
            candidate.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (!bounds.isEmpty() && area < smallestArea) {
                smallest.set(bounds);
                smallestArea = area;
            }
        }
        return smallest;
    }

    private boolean isAncestorOrSelf(AccessibilityNodeInfo candidate, AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.equals(candidate)) return true;
        }
        return false;
    }

    private String describeSettingsWindowRoots() {
        StringBuilder out = new StringBuilder("windows=");
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            out.append("{type=").append(window.getType())
                    .append(",focused=").append(window.isFocused())
                    .append(",active=").append(window.isActive())
                    .append(",bounds=").append(bounds.toShortString())
                    .append(",root=").append(root == null ? "null" : root.getClassName())
                    .append('}');
        }
        return out.toString();
    }

    private AccessibilityNodeInfo findTextStartingWith(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (node.isVisibleToUser() && text != null && text.toString().trim().startsWith(prefix)) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findTextStartingWith(node.getChild(index), prefix);
            if (found != null) return found;
        }
        return null;
    }

    private Rect visibleBounds(String label) {
        AccessibilityNodeInfo node = awaitExact(label, UI_WAIT_MS);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        assertTrue("player control bounds are non-empty for " + label, !bounds.isEmpty());
        return bounds;
    }

    private void tapControlCenter(String label) {
        Rect bounds = visibleBounds(label);
        injectTap(bounds.centerX(), bounds.centerY(), label);
    }

    private void tapBackControlCenterWhenVisible() throws Exception {
        String label = "Stop playback and return to title details";
        if (findExact(label) == null) {
            AccessibilityNodeInfo rootNode = ui.getRootInActiveWindow();
            assertNotNull("player accessibility root is available to reveal controls", rootNode);
            Rect playerBounds = new Rect();
            rootNode.getBoundsInScreen(playerBounds);
            assertTrue("player surface bounds are available to reveal controls", !playerBounds.isEmpty());
            // Stay below the top bar and above the centered transport controls.
            int revealControlsY = playerBounds.top + Math.round(playerBounds.height() * 0.25f);
            injectTap(playerBounds.centerX(), revealControlsY, "empty upper-middle video area");
            awaitExact(label, UI_WAIT_MS);
            assertPlaybackAccessibilityState(true);
        }
        tapControlCenter(label);
    }

    private void injectTap(float x, float y, String targetName) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent up = null;
        try {
            assertTrue("touch down is injected for " + targetName, ui.injectInputEvent(down, true));
            long upTime = Math.max(downTime, SystemClock.uptimeMillis());
            up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue("touch up is injected for " + targetName, ui.injectInputEvent(up, true));
        } finally {
            if (up != null) up.recycle();
            down.recycle();
        }
    }

    private Rect renderedTextInkBounds(Bitmap screenshot, Rect region, int textColor, int tolerance) {
        int left = Math.max(0, region.left);
        int top = Math.max(0, region.top);
        int right = Math.min(screenshot.getWidth(), region.right);
        int bottom = Math.min(screenshot.getHeight(), region.bottom);
        int minX = right;
        int minY = bottom;
        int maxX = -1;
        int maxY = -1;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (!isNearColor(screenshot.getPixel(x, y), textColor, tolerance)) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return maxX < minX || maxY < minY
                ? new Rect() : new Rect(minX, minY, maxX + 1, maxY + 1);
    }

    private Rect visibleBoundsStartingWith(String prefix) {
        long deadline = SystemClock.elapsedRealtime() + UI_WAIT_MS;
        do {
            AccessibilityNodeInfo node = findTextStartingWith(ui.getRootInActiveWindow(), prefix);
            if (node != null) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) return bounds;
            }
            SystemClock.sleep(80L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Expected visible text beginning with " + prefix + " is missing");
    }

    private int dpPixels(float value) {
        return Math.round(value * target.getResources().getDisplayMetrics().density);
    }

    private void recordControlSize(String name, Rect bounds, int expectedPx) {
        recordMockup(name + " control matches the scaled mockup " + expectedPx + "px square",
                Math.abs(bounds.width() - expectedPx) <= dpPixels(1)
                        && Math.abs(bounds.height() - expectedPx) <= dpPixels(1),
                "actual=" + bounds.toShortString());
    }

    private void recordMockup(String requirement, boolean passed, String actual) {
        layoutObservations.add(requirement + " referenceStatus="
                + (passed ? "WITHIN_REFERENCE" : "OUTSIDE_REFERENCE") + " [" + actual + "]");
    }

    private void recordMockup(String requirement, boolean passed) {
        recordMockup(requirement, passed, "observed bounds/position did not match");
    }

    private void logLayoutObservations() {
        for (String observation : layoutObservations) {
            System.out.println("JELLYFIN_LAYOUT_OBSERVATION=" + observation);
        }
    }

    private CapturedRequest awaitProgressAdvancingBeyond(long previousPositionTicks, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            for (CapturedRequest request : fixture.requests()) {
                if (!"POST".equals(request.method)
                        || !"/jellyfin/Sessions/Playing/Progress".equals(request.path)) continue;
                if (request.jsonBody().optLong("PositionTicks", -1L) > previousPositionTicks) {
                    return request;
                }
            }
            SystemClock.sleep(80L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    private CapturedRequest awaitProgressAtOrBefore(int firstRequestIndex, long maximumPositionTicks,
                                                     long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            List<CapturedRequest> requests = fixture.requests();
            for (int index = Math.max(0, firstRequestIndex); index < requests.size(); index++) {
                CapturedRequest request = requests.get(index);
                if (!"POST".equals(request.method)
                        || !"/jellyfin/Sessions/Playing/Progress".equals(request.path)) continue;
                if (request.jsonBody().optLong("PositionTicks", Long.MAX_VALUE) <= maximumPositionTicks) {
                    return request;
                }
            }
            SystemClock.sleep(80L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    private AccessibilityNodeInfo findExact(String label) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        AccessibilityNodeInfo found = root == null ? null : findExact(root, label);
        if (found != null) return found;
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo windowRoot = window.getRoot();
            if (windowRoot == null) continue;
            found = findExact(windowRoot, label);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        boolean matchesText = text != null && label.contentEquals(text.toString().trim());
        boolean matchesDescription = description != null
                && label.contentEquals(description.toString().trim());
        if (node.isVisibleToUser() && (matchesText || matchesDescription)) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findExact(node.getChild(index), label);
            if (found != null) return found;
        }
        return null;
    }

    private void setTextByHint(String hintFragment, String value) {
        long deadline = SystemClock.elapsedRealtime() + UI_WAIT_MS;
        do {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            AccessibilityNodeInfo field = findHint(root, hintFragment);
            if (field != null) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                assertTrue("visible input accepts test text for " + hintFragment,
                        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
                return;
            }
            SystemClock.sleep(80L);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The Jellyfin sign-in field is not visible: " + hintFragment);
    }

    private AccessibilityNodeInfo findHint(AccessibilityNodeInfo node, String hintFragment) {
        if (node == null) return null;
        CharSequence hint = node.getHintText();
        if (hint != null && hint.toString().contains(hintFragment)) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findHint(node.getChild(index), hintFragment);
            if (found != null) return found;
        }
        return null;
    }

    private void scrollUntilVisible(String label) {
        long deadline = SystemClock.elapsedRealtime() + UI_WAIT_MS;
        do {
            if (findExact(label) != null) return;
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable == null || !scrollable.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId())) break;
            SystemClock.sleep(180L);
        } while (SystemClock.elapsedRealtime() < deadline);
        awaitExact(label, 2_000L);
    }

    private void scrollToTop() {
        for (int page = 0; page < 12; page++) {
            AccessibilityNodeInfo scrollable = findScrollable(ui.getRootInActiveWindow());
            if (scrollable == null || !scrollable.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId())) return;
            SystemClock.sleep(100L);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(index));
            if (found != null) return found;
        }
        return null;
    }


    private void capture(String name) throws Exception {
        Bitmap bitmap = ui.takeScreenshot();
        assertNotNull("UI screenshot capture succeeds for " + name, bitmap);
        File directory = new File(target.getCacheDir(), SCREENSHOT_DIR);
        assertTrue("test screenshot directory is app-private and available",
                directory.isDirectory() || directory.mkdirs());
        File destination = new File(directory, "jellyfin-" + (television ? "tv" : "phone")
                + "-" + name + ".png").getAbsoluteFile();
        assertEquals("screenshot path stays directly in the fixture cache directory",
                directory.getCanonicalFile(), destination.getCanonicalFile().getParentFile());
        try (FileOutputStream output = new FileOutputStream(destination)) {
            assertTrue("captured screen is written as PNG", bitmap.compress(Bitmap.CompressFormat.PNG,
                    100, output));
            output.flush();
        } finally {
            bitmap.recycle();
        }
        System.out.println("JELLYFIN_UI_SCREENSHOT name=" + destination.getName()
                + " bytes=" + destination.length() + " sha256=" + sha256(destination));
    }

    private static void dumpAccessibilityNode(AccessibilityNodeInfo node, String label,
            int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        System.out.println(label + " depth=" + depth + " class=" + node.getClassName()
                + " description=" + node.getContentDescription() + " text=" + node.getText()
                + " clickable=" + node.isClickable() + " focusable=" + node.isFocusable()
                + " bounds=" + bounds.toShortString());
        if (depth == maxDepth) return;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            dumpAccessibilityNode(child, label, depth + 1, maxDepth);
            if (child != null) child.recycle();
        }
    }

    private void assertNoCleartextConnectionSecret() {
        android.content.SharedPreferences prefs = target.getSharedPreferences(
                "orbit_jellyfin_connection_v1", Context.MODE_PRIVATE);
        assertTrue("Jellyfin connection storage contains only encrypted fields",
                prefs.contains("iv") && prefs.contains("ciphertext") && prefs.getAll().size() == 2);
        for (Object value : prefs.getAll().values()) {
            String serialized = String.valueOf(value);
            assertFalse("credentials are not present in cleartext preferences",
                    serialized.contains(JellyfinFixtureServer.PASSWORD)
                            || serialized.contains(JellyfinFixtureServer.ACCESS_TOKEN));
        }
    }

    private void assertFixtureAuthorization(CapturedRequest request) {
        assertTrue("fixture API and media requests use the authenticated Jellyfin header",
                request.header("authorization").contains(JellyfinFixtureServer.ACCESS_TOKEN));
        assertFalse("the fixture token is not copied into the request URL",
                request.target.toLowerCase(java.util.Locale.ROOT).contains("api_key"));
    }

    private void assertSessionEvent(CapturedRequest request, String itemId, boolean stopped)
            throws Exception {
        assertFixtureAuthorization(request);
        JSONObject body = request.jsonBody();
        assertEquals(itemId, body.getString("ItemId"));
        assertEquals(JellyfinFixtureServer.MEDIA_SOURCE_ID, body.getString("MediaSourceId"));
        assertEquals(JellyfinFixtureServer.PLAY_SESSION_ID, body.getString("PlaySessionId"));
        assertEquals("DirectPlay", body.getString("PlayMethod"));
        assertTrue("Orbit reports a seekable fixture video", body.getBoolean("CanSeek"));
        if (stopped) assertTrue("stopped event has a terminal failure state", body.has("Failed"));
        else assertFalse("start and progress have no stop-only Failed field", body.has("Failed"));
    }

    private String screenshotNames() {
        File directory = new File(target.getCacheDir(), SCREENSHOT_DIR);
        StringBuilder names = new StringBuilder();
        File[] screenshots = directory.listFiles((parent, filename) -> filename.endsWith(".png"));
        if (screenshots != null) {
            for (File screenshot : screenshots) {
                if (names.length() > 0) names.append(',');
                names.append(screenshot.getName());
            }
        }
        return names.toString();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
