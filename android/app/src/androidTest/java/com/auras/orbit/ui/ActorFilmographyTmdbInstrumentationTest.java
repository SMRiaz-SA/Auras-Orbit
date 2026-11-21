package com.auras.orbit.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Offline coverage for the Auras-owned TMDB actor page and its stale-result guard. */
@RunWith(AndroidJUnit4.class)
public final class ActorFilmographyTmdbInstrumentationTest {
    private static final long UI_TIMEOUT_MS = 8_000L;
    private static final String REPOSITORY_CLASS = "com.auras.orbit.metadata.ActorMetadataRepository";
    private static final String METADATA_CLASS = "com.auras.orbit.metadata.ActorMetadata";

    private Instrumentation instrumentation;
    private Context target;
    private OrbitShellActivity activity;

    @Before public void launchOrbitHome() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        assertEquals("actor metadata UI tests must use the isolated queueRender application",
                "com.auras.orbit.queuerender", target.getPackageName());
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        if (application.getLocalProfileStore().getActiveProfile() == null) {
            com.auras.orbit.profile.LocalProfileStore.Profile fixture =
                    application.getLocalProfileStore().createProfile("TMDB actor fixture");
            assertNotNull("the isolated actor UI fixture must create a profile", fixture);
            application.getLocalProfileStore().selectProfile(fixture.id);
        }
        Intent launch = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (OrbitShellActivity) instrumentation.startActivitySync(launch);
        assertNotNull("OrbitShellActivity must launch", activity);
        awaitUi("Orbit navigation or profile picker", () -> findTagged("nav_HOME") != null
                || findText("Continue") != null);
        View continueButton = findText("Continue");
        if (continueButton != null) click(continueButton);
        awaitUi("Orbit Home route", () -> findTagged("nav_HOME") != null);
    }

    @After public void leaveOrbitRunning() {
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            instrumentation.runOnMainSync(() -> invokeNoArgs(activity, "closeProviderTitleDetails", false));
        }
    }

    @Test public void rendersFakeTmdbProfileAndMovieAndTvCreditsWithoutProviderData() throws Exception {
        AtomicReference<String> requestedActor = new AtomicReference<>();
        AtomicReference<String> requestedTitle = new AtomicReference<>();
        Object fakeRepository = fakeRepository((actor, title) -> {
            requestedActor.set(actor);
            requestedTitle.set(title);
            return metadataWithProfile("Jodie Foster", "/fixture-profile.jpg",
                    "A test profile biography.", "19 November 1962",
                    "Los Angeles, California", credit("Toy Story", "1995", "Movie", "as a voice actor"),
                    credit("Orange Is the New Black", "2013", "TV", "as a guest director"));
        });
        injectRepository(fakeRepository);

        MediaItem title = titleWithActor("offline-film", "Offline Fixture Movie", "Jodie Foster");
        openActorPage(title);

        awaitUi("TMDB actor profile and combined credits", () ->
                findTagged("provider_actor_profile_name") != null
                        && findTagged("provider_actor_credit_0") != null
                        && findTagged("provider_actor_credit_1") != null);

        assertEquals("Jodie Foster", textOf(findTagged("provider_actor_profile_name")));
        assertTrue("the TMDB profile biography must be shown",
                textOf(findTagged("provider_actor_biography")).contains("A test profile biography."));
        assertTrue("the movie combined credit must be shown",
                textOf(findTagged("provider_actor_credit_0")).contains("Toy Story"));
        assertTrue("the TV combined credit must be shown",
                textOf(findTagged("provider_actor_credit_1")).contains("Orange Is the New Black"));
        assertNotNull("the page must include TMDB attribution",
                findText("This product uses the TMDB API but is not endorsed or certified by TMDB."));
        assertNull("a missing provider links/content state must not replace TMDB filmography",
                findText("Filmography details are not available from this provider."));
        assertEquals("Jodie Foster", requestedActor.get());
        assertEquals("Offline Fixture Movie", requestedTitle.get());
        assertTrue("fixture title intentionally has no provider URL", title.detailUrl.isEmpty());
        assertTrue("fixture title intentionally has no provider payload", title.providerData.isEmpty());

        View back = findTagged("provider_actor_back");
        View portrait = findTagged("provider_actor_portrait");
        assertNotNull("the actor page keeps its own Back control", back);
        assertNotNull("the actor profile shows its portrait frame", portrait);
        assertTrue("a TMDB profile image view is layered above the initials fallback",
                portrait instanceof ViewGroup && ((ViewGroup) portrait).getChildCount() >= 2);
        int[] backLocation = new int[2];
        int[] portraitLocation = new int[2];
        instrumentation.runOnMainSync(() -> {
            back.getLocationOnScreen(backLocation);
            portrait.getLocationOnScreen(portraitLocation);
        });
        int minimumGap = Math.round(12 * target.getResources().getDisplayMetrics().density);
        assertTrue("the cast portrait begins below the Back control with visible breathing room",
                portraitLocation[1] - (backLocation[1] + back.getHeight()) >= minimumGap);
        captureScreenshot("actor-tmdb-page.png");
    }

    @Test public void actorCreditCardsUseTelevisionFocusAndDpadCenterActivation() throws Exception {
        injectRepository(fakeRepository((actor, title) -> metadata("Fixture Actor", "", "", "",
                credit("Fixture TV Series", "2025", "TV", "fixture role"))));
        openActorPage(titleWithActor("offline-tv-focus", "TV Focus Fixture", "Fixture Actor"));
        awaitUi("actor credit card for focus verification", () ->
                findTagged("provider_actor_credit_0") != null);

        View card = findTagged("provider_actor_credit_0");
        boolean television = (Boolean) field(activity, "isTelevision");
        assertTrue("actor credits must remain focusable on every device", card.isFocusable());
        assertEquals("touch-mode focus is enabled only for TV remote navigation",
                television, card.isFocusableInTouchMode());
        if (television) {
            AtomicReference<Boolean> focused = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> focused.set(card.requestFocus()));
            assertTrue("the TV remote can focus the actor credit card", focused.get());
            assertTrue("the actor credit card owns remote focus", card.isFocused());

            int searchGeneration = intField(activity, "providerActorCreditSearchGeneration");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            awaitUi("D-pad activation starts actor-credit title search", () ->
                    intField(activity, "providerActorCreditSearchGeneration") > searchGeneration);
        }
    }

    @Test public void retainsAndRendersFilmographyCreditsBeyondTwoHundred() throws Exception {
        List<Object> credits = new ArrayList<>();
        for (int index = 0; index <= 200; index++) {
            credits.add(credit("Fixture Credit " + index, "2025", "Movie", "as fixture role"));
        }
        injectRepository(fakeRepository((actor, title) -> metadata("Fixture Actor", "", "", "",
                credits.toArray(new Object[credits.size()]))));

        openActorPage(titleWithActor("offline-long-filmography", "Long Filmography Fixture",
                "Fixture Actor"));

        awaitUi("filmography credit beyond the old limit", () ->
                findTagged("provider_actor_credit_200") != null);
        assertTrue("the 201st returned credit is rendered on the actor page",
                textOf(findTagged("provider_actor_credit_200")).contains("Fixture Credit 200"));
    }

    @Test public void navigatesEntireLongFilmographyWithoutRenderingAllCredits() throws Exception {
        List<Object> credits = new ArrayList<>();
        for (int index = 0; index < 521; index++) {
            credits.add(credit("Paged Fixture Credit " + index, "2025", "TV", "fixture"));
        }
        injectRepository(fakeRepository((actor, title) -> metadata("Paged Actor", "", "", "",
                credits.toArray(new Object[credits.size()]))));
        openActorPage(titleWithActor("offline-actor-521", "Paged Filmography", "Paged Actor"));
        awaitUi("first page loaded", () -> findTagged("provider_actor_credit_200") != null
                && findTagged("provider_actor_credits_next") != null);
        assertNull("page one must not create all 521 views", findTagged("provider_actor_credit_520"));
        click(findTagged("provider_actor_credits_next"));
        awaitUi("second actor filmography page", () ->
                findTagged("provider_actor_credit_256") != null
                        && findTagged("provider_actor_credits_next") != null);
        click(findTagged("provider_actor_credits_next"));
        awaitUi("last actor filmography page", () ->
                findTagged("provider_actor_credit_520") != null);
        assertNull("last page should not show an impossible next control",
                findTagged("provider_actor_credits_next"));
        click(findTagged("provider_actor_credits_previous"));
        awaitUi("previous filmography page remains reachable", () ->
                findTagged("provider_actor_credit_256") != null);
    }

    @Test public void ignoresPendingLookupAfterActorAndTitleChange() throws Exception {
        CountDownLatch oldLookupStarted = new CountDownLatch(1);
        CountDownLatch releaseOldLookup = new CountDownLatch(1);
        CountDownLatch oldLookupReturned = new CountDownLatch(1);
        Object fakeRepository = fakeRepository((actor, title) -> {
            if ("First Actor".equals(actor)) {
                oldLookupStarted.countDown();
                try {
                    if (!releaseOldLookup.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        throw new AssertionError("test did not release the stale actor lookup");
                    }
                    return metadata("Stale First Actor", "This response must be ignored.", "", "",
                            credit("Stale Movie", "2001", "Movie", "as old role"));
                } finally {
                    oldLookupReturned.countDown();
                }
            }
            return metadata("Current Second Actor", "Current profile.", "", "",
                    credit("Current TV Series", "2024", "TV", "as current role"));
        });
        injectRepository(fakeRepository);

        MediaItem firstTitle = titleWithActor("offline-title-one", "First Fixture Title", "First Actor");
        openActorPage(firstTitle);
        assertTrue("the fake repository must receive the first actor lookup",
                oldLookupStarted.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        MediaItem secondTitle = titleWithActor("offline-title-two", "Second Fixture Title", "Second Actor");
        instrumentation.runOnMainSync(() -> {
            setField(activity, "providerDetailsItem", secondTitle);
            setField(activity, "providerDetailsSeedItem", secondTitle);
            int generation = intField(activity, "providerDetailsGeneration");
            setField(activity, "providerDetailsGeneration", generation + 1);
            invokeNoArgs(activity, "openProviderActorFilmography", 0);
        });

        awaitUi("current actor page", () -> findTagged("provider_actor_profile_name") != null
                && "Current Second Actor".equals(textOf(findTagged("provider_actor_profile_name"))));
        releaseOldLookup.countDown();
        assertTrue("the stale fake lookup must finish", oldLookupReturned.await(UI_TIMEOUT_MS,
                TimeUnit.MILLISECONDS));
        instrumentation.waitForIdleSync();
        SystemClock.sleep(150L);

        assertEquals("the changed actor profile remains visible", "Current Second Actor",
                textOf(findTagged("provider_actor_profile_name")));
        assertTrue("the changed title's credit remains visible",
                textOf(findTagged("provider_actor_credit_0")).contains("Current TV Series"));
        assertNull("the stale profile must not replace the current actor page",
                findText("Stale First Actor"));
        assertNull("the stale title credit must not appear",
                findText("Stale Movie"));
    }

    @Test public void exactNameContextTieKeepsTheFirstTmdbSearchResult() throws Exception {
        Class<?> personType = Class.forName("com.uwetrottmann.tmdb2.entities.BasePerson");
        Object first = personType.getConstructor().newInstance();
        Object second = personType.getConstructor().newInstance();
        personType.getField("name").set(first, "Alex Morgan");
        personType.getField("name").set(second, "Alex Morgan");
        personType.getField("id").set(first, 1001);
        personType.getField("id").set(second, 1002);
        personType.getField("known_for").set(first, Collections.emptyList());
        personType.getField("known_for").set(second, Collections.emptyList());

        Class<?> repositoryType = Class.forName(
                "com.auras.orbit.metadata.TmdbActorMetadataRepository");
        Object repository = repositoryType.getConstructor().newInstance();
        Method selectPerson = repositoryType.getDeclaredMethod("selectPerson", List.class,
                String.class, String.class);
        selectPerson.setAccessible(true);
        Object selected = selectPerson.invoke(repository, Arrays.asList(first, second),
                "Alex Morgan", "Unrelated Fixture Title");

        assertTrue("a tied exact-name search uses TMDB's first-ranked result deterministically",
                selected == first);
    }

    @Test public void settingsAboutCreditsShowsApprovedTmdbLogoAndRequiredNotice() throws Exception {
        click(findTagged("nav_SETTINGS"));
        awaitUi("Settings categories", () -> findTagged("settings_category_About & credits") != null);
        click(findTagged("settings_category_About & credits"));

        awaitUi("TMDB attribution settings", () -> findTagged("tmdb_approved_logo") != null
                && findTagged("tmdb_attribution_notice") != null);
        View logo = findTagged("tmdb_approved_logo");
        assertTrue("the approved TMDB logo must be shown from the bundled resource",
                logo instanceof ImageView && ((ImageView) logo).getDrawable() != null);
        assertEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                textOf(findTagged("tmdb_attribution_notice")));
        float density = target.getResources().getDisplayMetrics().density;
        assertTrue("the TMDB logo height remains below Orbit's 28dp primary mark",
                logo.getHeight() < Math.round(28 * density));
        assertTrue("the phone TMDB logo keeps the reduced 112dp maximum width",
                logo.getWidth() <= Math.round(112 * density));
        captureScreenshot("settings-tmdb-attribution.png");
    }

    private void openActorPage(MediaItem title) {
        instrumentation.runOnMainSync(() -> {
            setField(activity, "providerTitleDetailsOpen", true);
            setField(activity, "providerDetailsItem", title);
            setField(activity, "providerDetailsSeedItem", title);
            setField(activity, "providerDetailsProvider", null);
            setField(activity, "providerDetailsProfileId", activeProfileId());
            setField(activity, "providerDetailsRoute", ShellRoute.HOME);
            invokeNoArgs(activity, "openProviderActorFilmography", 0);
        });
    }

    private String activeProfileId() {
        try {
            Object store = field(activity, "profileStore");
            Object profile = store.getClass().getMethod("getActiveProfile").invoke(store);
            return (String) profile.getClass().getField("id").get(profile);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not read the isolated Orbit profile", error);
        }
    }

    private void injectRepository(Object repository) {
        instrumentation.runOnMainSync(() -> setField(activity, "actorMetadataRepository", repository));
    }

    private MediaItem titleWithActor(String stableId, String title, String actor) {
        return new MediaItem(stableId, title, MediaKind.MOVIE, "", "", "", "", "",
                Collections.emptyList(), 0, "", "", "", Collections.emptyMap(), "",
                Collections.singletonList(new MediaItem.CastMember(actor, "Fixture role", "")));
    }

    private Object fakeRepository(RepositoryCall callback) throws Exception {
        Class<?> repositoryType = Class.forName(REPOSITORY_CLASS);
        return Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType},
                (proxy, method, args) -> {
                    if ("load".equals(method.getName())) {
                        return callback.load((String) args[0], (String) args[1]);
                    }
                    if ("toString".equals(method.getName())) return "OfflineFakeActorMetadataRepository";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private Object metadata(String name, String biography, String birthday, String place,
            Object... credits) throws Exception {
        Class<?> metadataType = Class.forName(METADATA_CLASS);
        Class<?> creditType = Class.forName(METADATA_CLASS + "$Credit");
        Object[] typedCredits = credits;
        Constructor<?> metadataConstructor = metadataType.getConstructor(String.class, String.class,
                String.class, String.class, String.class, String.class, List.class);
        return metadataConstructor.newInstance(name, "", biography, birthday, "", place,
                Arrays.asList(typedCredits));
    }

    private Object metadataWithProfile(String name, String profilePath, String biography,
            String birthday, String place, Object... credits) throws Exception {
        Class<?> metadataType = Class.forName(METADATA_CLASS);
        Constructor<?> metadataConstructor = metadataType.getConstructor(String.class, String.class,
                String.class, String.class, String.class, String.class, List.class);
        return metadataConstructor.newInstance(name, profilePath, biography, birthday, "", place,
                Arrays.asList(credits));
    }

    private Object credit(String title, String year, String type, String role) throws Exception {
        Class<?> creditType = Class.forName(METADATA_CLASS + "$Credit");
        return creditType.getConstructor(String.class, String.class, String.class, String.class)
                .newInstance(title, year, type, role);
    }

    private void awaitUi(String description, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (condition.isMet()) return;
            SystemClock.sleep(40L);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private View findTagged(String tag) {
        AtomicReference<View> found = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> found.set(findTagged(activity.getWindow().getDecorView(), tag)));
        return found.get();
    }

    private View findTagged(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findTagged(group.getChildAt(index), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findText(String text) {
        AtomicReference<View> found = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> found.set(findText(activity.getWindow().getDecorView(), text)));
        return found.get();
    }

    private View findText(View root, String text) {
        if (root == null) return null;
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String textOf(View view) {
        if (view == null) return "";
        StringBuilder text = new StringBuilder();
        instrumentation.runOnMainSync(() -> appendText(view, text));
        return text.toString();
    }

    private void appendText(View view, StringBuilder text) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null && value.length() > 0) {
                if (text.length() > 0) text.append(' ');
                text.append(value);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                appendText(group.getChildAt(index), text);
            }
        }
    }

    private void click(View view) {
        instrumentation.runOnMainSync(view::performClick);
    }

    private void captureScreenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UI Automation must capture " + name, bitmap);
        File file = new File(target.getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue("the captured screen must be written", bitmap.compress(
                    Bitmap.CompressFormat.PNG, 100, output));
            output.flush();
        } finally {
            bitmap.recycle();
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int intField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not read Orbit generation " + name, error);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not seed Orbit state " + name, error);
        }
    }

    private static void invokeNoArgs(Object target, String methodName, Object... args) {
        try {
            Method found = null;
            for (Method candidate : target.getClass().getDeclaredMethods()) {
                if (methodName.equals(candidate.getName())
                        && candidate.getParameterTypes().length == args.length) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) throw new NoSuchMethodException(methodName);
            found.setAccessible(true);
            found.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not invoke Orbit action " + methodName, error);
        }
    }

    private interface RepositoryCall {
        Object load(String actor, String title) throws Exception;
    }

    private interface Condition { boolean isMet(); }
}
