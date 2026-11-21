package com.auras.orbit.intent;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.auras.orbit.BuildConfig;
import com.auras.orbit.OrbitApplication;
import com.auras.orbit.extensions.ExtensionOperationController;
import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;
import com.auras.orbit.player.OrbitPlayerActivity;
import com.auras.orbit.ui.OrbitShellActivity;
import com.auras.orbit.testing.OrbitIsolatedAndroidJUnitRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verifies incoming requests stay provisional until a user confirms their suggested action. */
@RunWith(AndroidJUnit4.class)
public final class IncomingRequestConfirmationInstrumentationTest {
    private static final long UI_TIMEOUT_MS = 10_000L;
    private static OrbitShellActivity retainedShell;

    private Instrumentation instrumentation;
    private UiAutomation uiAutomation;
    private Context instrumentationContext;
    private Context target;
    private OrbitApplication application;
    private OrbitShellActivity shell;
    private Instrumentation.ActivityMonitor playerMonitor;
    private Instrumentation.ActivityMonitor contentViewMonitor;
    private Instrumentation.ActivityMonitor linkViewMonitor;
    private Uri grantedReadUri;
    private boolean shellSetupComplete;

    @Before
    public void attachToNormalOrbitShell() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        uiAutomation = instrumentation.getUiAutomation();
        instrumentationContext = instrumentation.getContext();
        target = instrumentation.getTargetContext();
        assertEquals("incoming request instrumentation requires the matching Debug test APK",
                target.getPackageName() + ".test", instrumentationContext.getPackageName());
        assertTrue("OrbitIsolatedAndroidJUnitRunner uses plain Application unless "
                        + "files/" + OrbitIsolatedAndroidJUnitRunner.PRODUCTION_APPLICATION_GATE_FILE_NAME
                        + " is present; opt in to OrbitApplication before this instrumentation test",
                target.getApplicationContext() instanceof OrbitApplication);
        application = (OrbitApplication) target.getApplicationContext();
        assertFalse("incoming request coverage must use the normal non-canary Debug host",
                BuildConfig.CS3_CANARY_BUILD);
        assertTrue("incoming request coverage uses the accepted plugin crosswalk without auto-activating plugins",
                application.getCS3PluginRuntime().isTargetPluginCrosswalkValidated());
        assertNotNull("incoming request coverage requires an existing active Orbit profile; "
                        + "create/select one outside this test (the test never changes profile data)",
                application.getLocalProfileStore().getActiveProfile());
        playerMonitor = instrumentation.addMonitor(
                OrbitPlayerActivity.class.getName(), null, false);
        shell = launchPlainShellAndSelectHome();
        shellSetupComplete = true;
    }

    @After
    public void clearRequestAndLeaveOrbitOnHome() {
        if (playerMonitor != null) instrumentation.removeMonitor(playerMonitor);
        if (contentViewMonitor != null) instrumentation.removeMonitor(contentViewMonitor);
        if (linkViewMonitor != null) instrumentation.removeMonitor(linkViewMonitor);
        if (!shellSetupComplete || instrumentation == null || target == null || application == null) return;
        if (shell != null && findAccessibilityText("Cancel") != null) {
            try { clickVisibleText("Cancel"); }
            catch (AssertionError ignored) { }
        }
        if (grantedReadUri != null) {
            instrumentationContext.revokeUriPermission(grantedReadUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantedReadUri = null;
        }
        shell = launchPlainShellAndSelectHome();
        assertNotNull("cleanup must leave OrbitShellActivity running", shell);
        assertEquals("cleanup must leave Orbit on Home", ShellRoute.HOME,
                shellRoute(shell));
    }

    @Test
    public void repositoryReviewCardAndCancelLeaveOperationSnapshotUnchanged() {
        ExtensionOperationController controller = application.getExtensionOperationController();
        ExtensionOperationController.Snapshot before = controller.getSnapshot();
        assertFalse("the repository operation fixture must start idle", before.isRunning());

        String hiddenPath = "repo-private-path-2417";
        String hiddenToken = "repo-private-token-8fd1";
        Intent request = viewRequest("cloudstreamrepo://repo.invalid/" + hiddenPath
                + "?api_key=" + hiddenToken);
        sendRequest(request, "incoming_repository_0");

        assertCardIsSanitized("incoming_repository_0",
                "Repository link to repo.invalid (path, query, and fragment hidden)",
                hiddenPath, hiddenToken);
        assertSame("receiving a repository request must leave the operation snapshot unchanged",
                before, controller.getSnapshot());

        review("incoming_repository_0", "Add this repository?");
        assertEquals("Review must keep the request on its current route", ShellRoute.HOME,
                shellRoute(shell));
        assertSame("opening Review must leave the operation snapshot unchanged",
                before, controller.getSnapshot());
        assertDialogHides(hiddenPath, hiddenToken);
        cancelAndAssertRequestRemains("Add this repository?", "incoming_repository_0");
        assertSame("Cancel must leave the operation snapshot unchanged",
                before, controller.getSnapshot());
        assertEquals("no media PlayerActivity may launch during repository review", 0,
                playerMonitor.getHits());
    }

    @Test
    public void playerReviewCardAndCancelDoNotLaunchPlayer() {
        String hiddenPath = "player-private-path-6204";
        String hiddenToken = "player-private-token-19bc";
        String hiddenTitle = "private-player-title-77";
        String encodedTarget = Uri.encode("https://media.invalid/" + hiddenPath
                + ".m3u8?token=" + hiddenToken);
        Intent request = viewRequest("cloudstreamplayer://" + encodedTarget
                + "?name=" + Uri.encode(hiddenTitle));
        sendRequest(request, "incoming_player_link_0");

        assertCardIsSanitized("incoming_player_link_0",
                "Player link to media.invalid (path, query, and fragment hidden)",
                hiddenPath, hiddenToken, hiddenTitle);
        assertEquals("receiving a player request must not launch OrbitPlayerActivity", 0,
                playerMonitor.getHits());

        review("incoming_player_link_0", "Play this shared link?");
        assertEquals("Review must keep the request on Home", ShellRoute.HOME,
                shellRoute(shell));
        assertDialogHides(hiddenPath, hiddenToken, hiddenTitle);
        assertEquals("opening the confirmation dialog must not launch OrbitPlayerActivity", 0,
                playerMonitor.getHits());
        cancelAndAssertRequestRemains("Play this shared link?", "incoming_player_link_0");
        assertEquals("cancel must not launch OrbitPlayerActivity", 0, playerMonitor.getHits());
    }

    @Test
    public void searchReviewCardAndCancelLeaveSearchStateUnchanged() {
        SearchState before = SearchState.capture(shell);
        String privateQuery = "private-search-query-9351";
        Intent request = viewRequest("cloudstreamsearch://" + Uri.encode(privateQuery));
        sendRequest(request, "incoming_search_query_0");

        assertCardIsSanitized("incoming_search_query_0",
                "Search query received (query text hidden)", privateQuery);
        assertTrue("receiving a search request must leave query and submission state unchanged",
                before.matches(shell));

        review("incoming_search_query_0", "Use this search request?");
        assertEquals("Review must keep the request on Home", ShellRoute.HOME,
                shellRoute(shell));
        assertTrue("opening Review must leave query and submission state unchanged",
                before.matches(shell));
        assertTrue("the search value should be shown only inside the review dialog",
                activeWindowText().contains(privateQuery));
        cancelAndAssertRequestRemains("Use this search request?", "incoming_search_query_0");
        assertTrue("Cancel must leave query and submission state unchanged",
                before.matches(shell));
        assertFalse("cancel must hide the private query from the shell again",
                activityText().contains(privateQuery));
        assertEquals("search review must not launch a media player", 0, playerMonitor.getHits());
    }

    @Test
    public void sharedTextStaysHiddenUntilReviewedAndCancelDoesNotCopyIt() {
        ClipboardSnapshot clipboardBefore = ClipboardSnapshot.capture(target);
        String privateText = "private shared note 51c7 https://notes.invalid/private?token=share-secret";
        Intent request = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, privateText);
        sendRequest(request, "incoming_shared_text_0");

        assertCardIsSanitized("incoming_shared_text_0",
                "Shared text (content hidden); confirmation is required before using it.",
                "private shared note", "share-secret", "notes.invalid");
        assertTrue("receiving shared text must not change the clipboard",
                clipboardBefore.matches(ClipboardSnapshot.capture(target)));

        review("incoming_shared_text_0", "Review shared text");
        assertEquals("Review must keep the request on Home", ShellRoute.HOME,
                shellRoute(shell));
        assertTrue("the text is disclosed only after the user opens its review dialog",
                activeWindowText().contains(privateText));
        assertTrue("opening Review must not copy shared text",
                clipboardBefore.matches(ClipboardSnapshot.capture(target)));
        cancelAndAssertRequestRemains("Review shared text", "incoming_shared_text_0");
        assertFalse("cancel must hide shared text from the shell again",
                activityText().contains("private shared note"));
        assertTrue("cancel must not copy shared text", clipboardBefore.matches(
                ClipboardSnapshot.capture(target)));
        assertEquals("shared text review must not launch a media player", 0,
                playerMonitor.getHits());
    }

    @Test
    public void webLinkReviewAndCancelLeaveClipboardUnchangedAndDoNotLaunchActionView() {
        ClipboardSnapshot clipboardBefore = ClipboardSnapshot.capture(target);
        String hiddenPath = "web-link-private-path-3301";
        String hiddenToken = "web-link-private-token-2d19";
        sendRequest(viewRequest("https://web.invalid/" + hiddenPath + "?token=" + hiddenToken),
                "incoming_web_link_0");
        linkViewMonitor = monitorHttpsViewLaunches();

        assertCardIsSanitized("incoming_web_link_0",
                "Web link to web.invalid (path, query, and fragment hidden)", hiddenPath, hiddenToken);
        assertClipboardUnchanged(clipboardBefore, "receiving a web link");
        assertNoActionViewLaunch("receiving a web link");

        review("incoming_web_link_0", "Copy this link?");
        assertDialogHides(hiddenPath, hiddenToken);
        assertClipboardUnchanged(clipboardBefore, "reviewing a web link");
        assertNoActionViewLaunch("reviewing a web link");
        cancelAndAssertRequestRemains("Copy this link?", "incoming_web_link_0");
        assertClipboardUnchanged(clipboardBefore, "canceling a web link review");
        assertNoActionViewLaunch("canceling a web link review");
    }

    @Test
    public void sharedProviderLinkReviewAndCancelLeaveClipboardUnchangedAndDoNotLaunchActionView() {
        ClipboardSnapshot clipboardBefore = ClipboardSnapshot.capture(target);
        String hiddenPath = "provider-link-private-path-1148";
        String hiddenToken = "provider-link-private-token-68f2";
        String privateProviderName = "private provider label 042";
        String privateUrl = "https://provider.invalid/" + hiddenPath + "?token=" + hiddenToken;
        String payload = "csshare:" + android.util.Base64.encodeToString(
                privateProviderName.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
                + "?" + android.util.Base64.encodeToString(
                privateUrl.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        sendRequest(viewRequest(payload), "incoming_provider_link_0");
        linkViewMonitor = monitorHttpsViewLaunches();

        assertCardIsSanitized("incoming_provider_link_0",
                "Shared provider link to provider.invalid (path, query, and fragment hidden)",
                hiddenPath, hiddenToken, privateProviderName);
        assertClipboardUnchanged(clipboardBefore, "receiving a shared provider link");
        assertNoActionViewLaunch("receiving a shared provider link");

        review("incoming_provider_link_0", "Copy this provider link?");
        assertDialogHides(hiddenPath, hiddenToken, privateProviderName);
        assertClipboardUnchanged(clipboardBefore, "reviewing a shared provider link");
        assertNoActionViewLaunch("reviewing a shared provider link");
        cancelAndAssertRequestRemains("Copy this provider link?", "incoming_provider_link_0");
        assertClipboardUnchanged(clipboardBefore, "canceling a shared provider link review");
        assertNoActionViewLaunch("canceling a shared provider link review");
    }

    @Test
    public void sharedContentUriStaysHiddenAndCancelDoesNotOpenOrReadIt() {
        ClipboardSnapshot clipboardBefore = ClipboardSnapshot.capture(target);
        String privatePath = "content-private-backup-0429";
        IncomingRequestReadTrackingProvider.resetReadCounts(instrumentationContext);
        Uri privateUri = IncomingRequestReadTrackingProvider.contentUri(privatePath);
        grantedReadUri = privateUri;
        instrumentationContext.grantUriPermission(target.getPackageName(), privateUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent request = new Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, privateUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        IntentFilter contentView = new IntentFilter(Intent.ACTION_VIEW);
        contentView.addDataScheme("content");
        contentViewMonitor = instrumentation.addMonitor(contentView, null, true);
        sendRequest(request, "incoming_shared_file_0");

        assertCardIsSanitized("incoming_shared_file_0",
                "Shared content URI (details hidden); confirmation and an available permission are required.",
                "content-private-backup", IncomingRequestReadTrackingProvider.AUTHORITY);
        assertTrue("receiving a content URI must not change the clipboard",
                clipboardBefore.matches(ClipboardSnapshot.capture(target)));
        assertNoContentProviderAccess("receipt");
        assertEquals("receiving a content URI must not launch an ACTION_VIEW activity", 0,
                contentViewMonitor.getHits());

        review("incoming_shared_file_0", "Review shared backup file?");
        assertEquals("Review must keep the request on Home", ShellRoute.HOME,
                shellRoute(shell));
        assertDialogHides(privatePath, IncomingRequestReadTrackingProvider.AUTHORITY);
        assertTrue("opening Review must not copy content", clipboardBefore.matches(
                ClipboardSnapshot.capture(target)));
        assertNoContentProviderAccess("Review");
        assertEquals("Review must not launch an ACTION_VIEW activity for the shared content", 0,
                contentViewMonitor.getHits());
        cancelAndAssertRequestRemains("Review shared backup file?", "incoming_shared_file_0");
        assertFalse("cancel must keep private content URI details hidden",
                activityText().contains(privatePath));
        assertTrue("cancel must not copy shared content", clipboardBefore.matches(
                ClipboardSnapshot.capture(target)));
        assertNoContentProviderAccess("Cancel");
        assertEquals("Cancel must not launch an ACTION_VIEW activity for the shared content", 0,
                contentViewMonitor.getHits());
        assertEquals("shared content review must not launch a media player", 0,
                playerMonitor.getHits());
    }

    private Instrumentation.ActivityMonitor monitorHttpsViewLaunches() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
        filter.addDataScheme("https");
        return instrumentation.addMonitor(filter, null, true);
    }

    private void assertNoActionViewLaunch(String stage) {
        assertEquals(stage + " must not launch an ACTION_VIEW activity", 0, linkViewMonitor.getHits());
    }

    private void assertClipboardUnchanged(ClipboardSnapshot before, String stage) {
        assertTrue(stage + " must not change the clipboard",
                before.matches(ClipboardSnapshot.capture(target)));
    }

    private void assertNoContentProviderAccess(String stage) {
        Bundle counts = IncomingRequestReadTrackingProvider.readCounts(instrumentationContext);
        assertEquals(stage + " must not query shared content", 0,
                counts.getInt(IncomingRequestReadTrackingProvider.COUNT_QUERY));
        assertEquals(stage + " must not inspect shared content type", 0,
                counts.getInt(IncomingRequestReadTrackingProvider.COUNT_TYPE));
        assertEquals(stage + " must leave the content-provider open counter at zero", 0,
                counts.getInt(IncomingRequestReadTrackingProvider.COUNT_OPEN));
    }

    private OrbitShellActivity launchPlainShellAndSelectHome() {
        Intent plainLaunch = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        OrbitShellActivity result = liveShell(retainedShell) ? retainedShell : null;
        if (result == null) {
            assertNoShellActivityBeforeInitialLaunch();
            Activity launched = instrumentation.startActivitySync(plainLaunch);
            assertTrue("the explicit target must be OrbitShellActivity",
                    launched instanceof OrbitShellActivity);
            result = (OrbitShellActivity) launched;
        } else {
            startFromShell(result, plainLaunch);
        }
        retainedShell = result;
        shell = result;
        OrbitShellActivity active = result;
        awaitUi("Orbit shell or profile picker", () -> findTag(active, "nav_SEARCH") != null
                || findText(active, "Continue") != null);
        View continueButton = findText(active, "Continue");
        if (continueButton != null) click(continueButton);
        awaitUi("confirmed Orbit shell", () -> findTag(active, "nav_SEARCH") != null);
        View home = findTag(active, "nav_HOME");
        assertNotNull("Orbit must expose Home navigation", home);
        click(home);
        awaitUi("Orbit Home", () -> shellRoute(active) == ShellRoute.HOME);
        return result;
    }

    private void sendRequest(Intent request, String expectedTag) {
        request.setClass(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        OrbitShellActivity active = shell;
        assertNotNull("the retained OrbitShellActivity must be available for incoming requests", active);
        startFromShell(active, request);
        retainedShell = active;
        awaitUi("sanitized incoming request review card", () -> findTag(active, expectedTag) != null);
    }

    private void startFromShell(OrbitShellActivity active, Intent intent) {
        instrumentation.runOnMainSync(() -> active.startActivity(intent));
    }

    private static boolean liveShell(OrbitShellActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void assertNoShellActivityBeforeInitialLaunch() {
        AtomicReference<Boolean> existingShell = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            for (Stage stage : Stage.values()) {
                if ("DESTROYED".equals(stage.name())) continue;
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(stage)) {
                    if (activity instanceof OrbitShellActivity
                            && !activity.isFinishing() && !activity.isDestroyed()) {
                        existingShell.set(true);
                    }
                }
            }
        });
        assertFalse("the first shell launch requires a fresh target process because OrbitShellActivity "
                        + "is singleTask; force-stop Orbit before the suite (app data is preserved)",
                existingShell.get());
    }

    private static Intent viewRequest(String data) {
        return new Intent(Intent.ACTION_VIEW).setData(Uri.parse(data));
    }

    private void assertCardIsSanitized(String buttonTag, String safeText, String... hiddenValues) {
        awaitUi("sanitized review card", () -> findTag(shell, buttonTag) != null
                && activityText().contains(safeText));
        assertTrue("the review card must display its safe summary", activityText().contains(safeText));
        String visible = activityText();
        for (String hidden : hiddenValues) {
            assertFalse("raw incoming value must stay hidden before Review", visible.contains(hidden));
        }
        assertEquals("incoming request must remain on Home before Review", ShellRoute.HOME,
                shellRoute(shell));
    }

    private void review(String buttonTag, String dialogTitle) {
        click(findTag(shell, buttonTag));
        awaitUi("confirmation dialog " + dialogTitle,
                () -> activeWindowText().contains(dialogTitle));
        assertTrue("the request's Review button must open the expected confirmation dialog",
                activeWindowText().contains(dialogTitle));
        assertNotNull("Review must leave the pending request card visible beneath the dialog",
                findTag(shell, buttonTag));
    }

    private void cancelAndAssertRequestRemains(String dialogTitle, String requestTag) {
        clickVisibleText("Cancel");
        awaitUi("confirmation dialog dismissed", () -> !activeWindowText().contains(dialogTitle));
        assertNotNull("Cancel must leave the incoming request available for review again",
                findTag(shell, requestTag));
        assertEquals("Cancel must keep the request on Home", ShellRoute.HOME,
                shellRoute(shell));
    }

    private static ShellRoute shellRoute(OrbitShellActivity activity) {
        return ((ShellState) SearchState.field(activity, "shellState")).getRoute();
    }

    private void assertDialogHides(String... hiddenValues) {
        String visible = activeWindowText();
        for (String hidden : hiddenValues) {
            assertFalse("confirmation details must not reveal private values before confirmation",
                    visible.contains(hidden));
        }
    }

    private void click(View view) {
        assertNotNull("expected Orbit control must be visible", view);
        AtomicReference<Boolean> clicked = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> clicked.set(view.performClick()));
        assertTrue("Orbit control must accept a click", clicked.get());
    }

    private void clickVisibleText(String text) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        do {
            AccessibilityNodeInfo node = findAccessibilityText(text);
            if (node != null) {
                AccessibilityNodeInfo clickable = node;
                while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return;
                }
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Could not click visible Orbit text: " + text);
    }

    private AccessibilityNodeInfo findAccessibilityText(String expected) {
        AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
        if (root == null) return null;
        return findAccessibilityText(root, expected);
    }

    private static AccessibilityNodeInfo findAccessibilityText(
            AccessibilityNodeInfo node, String expected) {
        if (sameTrimmedText(expected, node.getText())
                || sameTrimmedText(expected, node.getContentDescription())) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            AccessibilityNodeInfo match = findAccessibilityText(child, expected);
            if (match != null) return match;
        }
        return null;
    }

    private static boolean sameTrimmedText(String expected, CharSequence actual) {
        return actual != null && expected.trim().equalsIgnoreCase(actual.toString().trim());
    }

    private void awaitUi(String description, UiCondition condition) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        do {
            if (condition.isMet()) return;
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("Timed out waiting for " + description, condition.isMet());
    }

    private String activityText() {
        AtomicReference<String> text = new AtomicReference<>("");
        instrumentation.runOnMainSync(() -> {
            if (shell != null) {
                StringBuilder collected = new StringBuilder();
                collectViewText(shell.getWindow().getDecorView(), collected);
                text.set(collected.toString());
            }
        });
        return text.get();
    }

    private static void collectViewText(View view, StringBuilder output) {
        if (view instanceof TextView) output.append(((TextView) view).getText()).append('\n');
        CharSequence description = view.getContentDescription();
        if (description != null) output.append(description).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectViewText(group.getChildAt(index), output);
            }
        }
    }

    private String activeWindowText() {
        AccessibilityNodeInfo root = uiAutomation.getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder output = new StringBuilder();
        collectAccessibilityText(root, output);
        return output.toString();
    }

    private static void collectAccessibilityText(AccessibilityNodeInfo node, StringBuilder output) {
        CharSequence text = node.getText();
        if (text != null) output.append(text).append('\n');
        CharSequence description = node.getContentDescription();
        if (description != null) output.append(description).append('\n');
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) collectAccessibilityText(child, output);
        }
    }

    private static View findTag(OrbitShellActivity activity, String expected) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
                findTag(activity.getWindow().getDecorView(), expected)));
        return result.get();
    }

    private static View findTag(View view, String expected) {
        if (expected.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findTag(group.getChildAt(index), expected);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View findText(OrbitShellActivity activity, String expected) {
        AtomicReference<View> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
                findText(activity.getWindow().getDecorView(), expected)));
        return result.get();
    }

    private static View findText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findText(group.getChildAt(index), expected);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static final class SearchState {
        final String query;
        final String submittedQuery;
        final Object batch;
        final Object batchKey;

        private SearchState(String query, String submittedQuery, Object batch, Object batchKey) {
            this.query = query;
            this.submittedQuery = submittedQuery;
            this.batch = batch;
            this.batchKey = batchKey;
        }

        static SearchState capture(OrbitShellActivity activity) {
            return new SearchState(stringField(activity, "searchQuery"),
                    stringField(activity, "titleSearchSubmittedQuery"),
                    field(activity, "titleSearchBatch"), field(activity, "titleSearchBatchKey"));
        }

        boolean matches(OrbitShellActivity activity) {
            return equal(query, stringField(activity, "searchQuery"))
                    && equal(submittedQuery, stringField(activity, "titleSearchSubmittedQuery"))
                    && batch == field(activity, "titleSearchBatch")
                    && equal(batchKey, field(activity, "titleSearchBatchKey"));
        }

        private static String stringField(Object receiver, String name) {
            Object value = field(receiver, name);
            return value == null ? null : value.toString();
        }

        private static boolean equal(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }

        private static Object field(Object receiver, String name) {
            try {
                Field field = OrbitShellActivity.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not inspect search state " + name, error);
            }
        }
    }

    private static final class ClipboardSnapshot {
        final boolean hasPrimaryClip;
        final CharSequence label;
        final List<String> mimeTypes;
        final List<String> itemValues;

        private ClipboardSnapshot(boolean hasPrimaryClip, CharSequence label,
                List<String> mimeTypes, List<String> itemValues) {
            this.hasPrimaryClip = hasPrimaryClip;
            this.label = label;
            this.mimeTypes = mimeTypes;
            this.itemValues = itemValues;
        }

        static ClipboardSnapshot capture(Context context) {
            ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return new ClipboardSnapshot(false, null, new ArrayList<>(), new ArrayList<>());
            }
            ClipData clip = clipboard.getPrimaryClip();
            ClipDescription description = clip == null ? null : clip.getDescription();
            List<String> mimeTypes = new ArrayList<>();
            List<String> values = new ArrayList<>();
            if (description != null) {
                for (int index = 0; index < description.getMimeTypeCount(); index++) {
                    mimeTypes.add(description.getMimeType(index));
                }
            }
            if (clip != null) {
                for (int index = 0; index < clip.getItemCount(); index++) {
                    ClipData.Item item = clip.getItemAt(index);
                    values.add("text=" + item.getText() + "|html=" + item.getHtmlText()
                            + "|uri=" + item.getUri() + "|intent="
                            + (item.getIntent() == null ? null : item.getIntent().toUri(0)));
                }
            }
            return new ClipboardSnapshot(true, description == null ? null : description.getLabel(),
                    mimeTypes, values);
        }

        boolean matches(ClipboardSnapshot other) {
            return other != null && hasPrimaryClip == other.hasPrimaryClip
                    && equal(label, other.label) && mimeTypes.equals(other.mimeTypes)
                    && itemValues.equals(other.itemValues);
        }

        private static boolean equal(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }
    }

    private interface UiCondition {
        boolean isMet();
    }
}
