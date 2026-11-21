package com.auras.orbit.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.navigation.ShellRoute;
import com.auras.orbit.navigation.ShellState;
import com.auras.orbit.profile.LocalProfileStore;
import com.auras.orbit.providers.MediaItem;
import com.auras.orbit.providers.MediaKind;
import com.auras.orbit.providers.MediaProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Covers phone Home card sizes at the requested mockup viewport. */
@RunWith(AndroidJUnit4.class)
public final class HomeCardSizingInstrumentationTest {
    private static final long UI_TIMEOUT_MS = 8_000L;

    private Instrumentation instrumentation;
    private Context target;
    private OrbitShellActivity activity;
    private LocalLibraryStore library;
    private String profileId;

    @Before public void launchOrbitHome() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        target = instrumentation.getTargetContext();
        assertEquals("Home sizing tests must use the isolated QueueRender app",
                "com.auras.orbit.queuerender", target.getPackageName());
        OrbitApplication application = (OrbitApplication) target.getApplicationContext();
        if (application.getLocalProfileStore().getActiveProfile() == null) {
            LocalProfileStore.Profile fixture =
                    application.getLocalProfileStore().createProfile("Home sizing fixture");
            assertNotNull("the fixture must create a local profile", fixture);
            application.getLocalProfileStore().selectProfile(fixture.id);
        }
        LocalProfileStore.Profile activeProfile = application.getLocalProfileStore().getActiveProfile();
        assertNotNull("the test profile must remain available", activeProfile);
        profileId = activeProfile.id;
        library = application.getLocalLibraryStore();
        Intent launch = new Intent(target, OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (OrbitShellActivity) instrumentation.startActivitySync(launch);
        assertNotNull("OrbitShellActivity must launch", activity);
        awaitUi("Orbit Home", () -> findTagged("nav_HOME") != null
                || findText("Continue") != null);
        View continueButton = findText("Continue");
        if (continueButton != null) click(continueButton);
        awaitUi("Home navigation", () -> findTagged("nav_HOME") != null);
    }

    @Test public void continueWatchingCardUsesEpisodeCardWidthAnd16To9ArtworkAtPhoneWidths()
            throws Exception {
        int[] cardWidthsPx = new int[2];
        int[] artworkHeightsPx = new int[2];
        AtomicReference<Throwable> failure = new AtomicReference<>();

        instrumentation.runOnMainSync(() -> {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int originalWidthPx = metrics.widthPixels;
            float density = metrics.density;
            try {
                int[] viewportWidthsDp = {360, 411};
                for (int index = 0; index < viewportWidthsDp.length; index++) {
                    metrics.widthPixels = Math.round(viewportWidthsDp[index] * density);
                    LinearLayout page = new LinearLayout(activity);
                    page.setOrientation(LinearLayout.VERTICAL);
                    LocalLibraryStore.SavedTitle title = new LocalLibraryStore.SavedTitle(
                            "home-sizing-fixture-" + viewportWidthsDp[index], "fixture",
                            "Sizing Fixture", "MOVIE", "", "", "", "");
                    Method addRail = OrbitShellActivity.class.getDeclaredMethod(
                            "addHomeContinueWatchingRail", LinearLayout.class, List.class,
                            boolean.class, String.class);
                    addRail.setAccessible(true);
                    addRail.invoke(activity, page, Collections.singletonList(title), false,
                            "fixture");

                    HorizontalScrollView rail = (HorizontalScrollView) page.getChildAt(0);
                    LinearLayout cards = (LinearLayout) rail.getChildAt(0);
                    View card = cards.getChildAt(0);
                    int actualWidthPx = card.getLayoutParams().width;
                    cardWidthsPx[index] = actualWidthPx;
                    View artwork = ((ViewGroup) card).getChildAt(0);
                    artwork.measure(View.MeasureSpec.makeMeasureSpec(actualWidthPx -
                                    Math.round(8 * density),
                                    View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0,
                                    View.MeasureSpec.UNSPECIFIED));
                    artworkHeightsPx[index] = artwork.getMeasuredHeight();
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                metrics.widthPixels = originalWidthPx;
            }
        });

        if (failure.get() != null) throw new AssertionError("Could not inspect Home card layout", failure.get());
        float density = activity.getResources().getDisplayMetrics().density;
        int expectedWidthPx = Math.round(176 * density);
        int[] viewportWidthsDp = {360, 411};
        for (int index = 0; index < viewportWidthsDp.length; index++) {
            assertEquals("Continue Watching matches the 176dp provider episode card at "
                            + viewportWidthsDp[index] + "dp phone width",
                    expectedWidthPx, cardWidthsPx[index]);
            assertEquals("Continue Watching artwork preserves 16:9 at "
                            + viewportWidthsDp[index] + "dp phone width",
                    Math.round((cardWidthsPx[index] - Math.round(8 * density)) * 9f / 16f),
                    artworkHeightsPx[index]);
        }
    }

    @Test public void televisionContinueWatchingShowsReadableEpisodeCardAtBothTvWidths()
            throws Exception {
        LocalLibraryStore.SavedTitle series = new LocalLibraryStore.SavedTitle(
                "tv-cw-sizing-fixture", "fixture", "Sizing Fixture Series", "SERIES", "",
                "", "2025", "");
        library.saveTitle(profileId, series);
        assertTrue("episode progress fixture must persist", library.recordPlaybackProgress(
                profileId, series, 12 * 60_000L, 48 * 60_000L, "fixture-episode-7",
                "The Long Black Night", 1, 7));
        LocalLibraryStore.SavedTitle progress = findProgress("tv-cw-sizing-fixture");
        assertNotNull("episode progress fixture must be available", progress);

        int[] viewportWidthsDp = {960, 540};
        int[] expectedCardWidthsDp = {243, 145};
        int[] cardWidthsPx = new int[viewportWidthsDp.length];
        int[] artworkHeightsPx = new int[viewportWidthsDp.length];
        boolean[] progressIndicators = new boolean[viewportWidthsDp.length];
        boolean[] focusableCards = new boolean[viewportWidthsDp.length];
        List<List<String>> visibleLabels = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        instrumentation.runOnMainSync(() -> {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int originalWidthPx = metrics.widthPixels;
            float density = metrics.density;
            try {
                for (int index = 0; index < viewportWidthsDp.length; index++) {
                    metrics.widthPixels = Math.round(viewportWidthsDp[index] * density);
                    LinearLayout page = new LinearLayout(activity);
                    page.setOrientation(LinearLayout.VERTICAL);
                    Method addRail = OrbitShellActivity.class.getDeclaredMethod(
                            "addHomeContinueWatchingRail", LinearLayout.class, List.class,
                            boolean.class, String.class);
                    addRail.setAccessible(true);
                    addRail.invoke(activity, page, Collections.singletonList(progress), true,
                            profileId);

                    HorizontalScrollView rail = (HorizontalScrollView) page.getChildAt(0);
                    LinearLayout cards = (LinearLayout) rail.getChildAt(0);
                    View card = cards.getChildAt(0);
                    int actualWidthPx = card.getLayoutParams().width;
                    cardWidthsPx[index] = actualWidthPx;
                    focusableCards[index] = card.isFocusable();
                    progressIndicators[index] = findTagged(card,
                            "home_continue_progress") != null;
                    View artwork = findTagged(card, "home_continue_art");
                    if (artwork != null) {
                        int artworkWidthPx = actualWidthPx - Math.round(8 * density);
                        artwork.measure(View.MeasureSpec.makeMeasureSpec(artworkWidthPx,
                                        View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        artworkHeightsPx[index] = artwork.getMeasuredHeight();
                    } else {
                        artworkHeightsPx[index] = -1;
                    }
                    ArrayList<String> labels = new ArrayList<>();
                    collectText(card, labels);
                    visibleLabels.add(labels);
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                metrics.widthPixels = originalWidthPx;
            }
        });

        if (failure.get() != null) {
            throw new AssertionError("Could not inspect TV Continue Watching episode card",
                    failure.get());
        }
        float density = activity.getResources().getDisplayMetrics().density;
        for (int index = 0; index < viewportWidthsDp.length; index++) {
            assertEquals("TV Continue Watching uses a readable episode-card width at "
                            + viewportWidthsDp[index] + "dp screen width",
                    Math.round(expectedCardWidthsDp[index] * density), cardWidthsPx[index]);
            assertEquals("TV Continue Watching artwork preserves 16:9 at "
                            + viewportWidthsDp[index] + "dp screen width",
                    Math.round((cardWidthsPx[index] - Math.round(8 * density)) * 9f / 16f),
                    artworkHeightsPx[index]);
            assertTrue("series title remains visible on TV", visibleLabels.get(index)
                    .contains("Sizing Fixture Series"));
            assertTrue("season and episode remain readable as their own label on TV",
                    visibleLabels.get(index).contains("S1 · E07"));
            assertTrue("episode title remains readable as its own label on TV",
                    visibleLabels.get(index).contains("The Long Black Night"));
            assertTrue("remaining time remains visible on TV",
                    visibleLabels.get(index).contains("36 min left"));
            assertTrue("TV episode artwork retains its playback progress indicator",
                    progressIndicators[index]);
            assertTrue("TV episode cards remain reachable by remote focus",
                    focusableCards[index]);
        }
    }

    @Test public void continueWatchingRestoresCachedEpisodeArtworkAndKeepsLegacyTitleArt()
            throws Exception {
        String episodeArtworkRef = libraryArtworkReference('b');
        String titleArtworkRef = libraryArtworkReference('d');
        writeLibraryArtwork(episodeArtworkRef, Color.rgb(35, 190, 80));
        writeLibraryArtwork(titleArtworkRef, Color.rgb(205, 45, 35));

        LocalLibraryStore.SavedTitle series = new LocalLibraryStore.SavedTitle(
                "cw-artwork-series-fixture", "fixture", "Fixture Series", "SERIES", "",
                titleArtworkRef, "2024", "");
        LocalLibraryStore.SavedTitle movie = new LocalLibraryStore.SavedTitle(
                "cw-artwork-movie-fixture", "fixture", "Fixture Movie", "MOVIE", "",
                titleArtworkRef, "2024", "");
        LocalLibraryStore.SavedTitle legacy = new LocalLibraryStore.SavedTitle(
                "cw-artwork-legacy-fixture", "fixture", "Legacy Movie", "MOVIE", "",
                titleArtworkRef, "2024", "");
        LocalLibraryStore.SavedTitle correctedTuple = new LocalLibraryStore.SavedTitle(
                "cw-artwork-tie-fixture", "fixture", "Tie Fixture Series", "SERIES", "",
                "", "2024", "");
        library.saveTitle(profileId, series);
        library.saveTitle(profileId, movie);
        library.saveTitle(profileId, correctedTuple);
        Method recordProgress = LocalLibraryStore.class.getMethod("recordPlaybackProgress",
                String.class, LocalLibraryStore.SavedTitle.class, long.class, long.class,
                String.class, String.class, int.class, int.class, String.class);
        assertEquals(Boolean.TRUE, recordProgress.invoke(library, profileId, series, 500_000L,
                1_800_000L, "fixture-episode-7", "The Still", 1, 7, episodeArtworkRef));
        assertEquals(Boolean.TRUE, recordProgress.invoke(library, profileId, movie, 300_000L,
                1_200_000L, "", "", -1, -1, titleArtworkRef));
        assertEquals(Boolean.TRUE, recordProgress.invoke(library, profileId, correctedTuple,
                400_000L, 1_800_000L, "fixture-episode-7", "The Still", 1, 7,
                episodeArtworkRef));
        assertTrue("legacy progress fixture must persist",
                library.recordPlaybackProgress(profileId, legacy, 200_000L, 1_000_000L,
                        "", "", -1, -1));

        LocalLibraryStore.SavedTitle savedSeries = findProgress("cw-artwork-series-fixture");
        LocalLibraryStore.SavedTitle savedMovie = findProgress("cw-artwork-movie-fixture");
        assertNotNull("series progress must be retained", savedSeries);
        assertNotNull("movie progress must be retained", savedMovie);
        Field artworkRef = LocalLibraryStore.SavedTitle.class.getField("resumeArtworkRef");
        assertEquals("series must retain its active episode still", episodeArtworkRef,
                artworkRef.get(savedSeries));
        assertEquals("movies must retain their landscape title artwork", titleArtworkRef,
                artworkRef.get(savedMovie));
        LocalLibraryStore.SavedTitle savedLegacy = findProgress("cw-artwork-legacy-fixture");
        assertNotNull("legacy progress must remain available", savedLegacy);
        assertEquals("legacy rows have no episode resume reference", "",
                artworkRef.get(savedLegacy));

        JSONObject archive = library.exportArchiveState();
        JSONObject archiveRow = findArchiveProgress(archive, "cw-artwork-series-fixture");
        assertTrue("archives must omit device-local resume image refs",
                !archiveRow.has("resumeArtworkRef"));
        assertEquals("archives must omit device-local title art refs", "",
                archiveRow.optString("posterUrl"));
        JSONObject savedTitleRow = findArchiveSavedTitle(archive, "cw-artwork-series-fixture");
        assertTrue("saved-title archive rows must omit device-local resume refs",
                !savedTitleRow.has("resumeArtworkRef"));
        assertEquals("saved-title archive rows must omit local image refs", "",
                savedTitleRow.optString("posterUrl"));
        validateArchiveRow(archiveRow);
        JSONObject legacyRow = new JSONObject(archiveRow.toString());
        legacyRow.remove("resumeArtworkRef");
        validateArchiveRow(legacyRow);
        assertEquals("old archives default to no resume artwork", "",
                importResumeArtworkValue(legacyRow));
        JSONObject sameEpisodeArchive = new JSONObject(archive.toString());
        setArchiveResumeEpisode(findArchiveProgress(sameEpisodeArchive,
                        "cw-artwork-series-fixture"),
                "fixture-episode-7", "The Still", 1, 7);
        setArchiveResumeEpisode(findArchiveSavedTitle(sameEpisodeArchive,
                        "cw-artwork-series-fixture"),
                "fixture-episode-7", "The Still", 1, 7);
        library.mergeArchiveState(sameEpisodeArchive, Collections.singleton(profileId),
                UUID.randomUUID().toString());
        assertEquals("archive merge must retain episode art", episodeArtworkRef,
                artworkRef.get(findProgress("cw-artwork-series-fixture")));
        assertEquals("archive merge must retain saved-title episode art", episodeArtworkRef,
                artworkRef.get(library.getSavedTitle(profileId, "cw-artwork-series-fixture")));

        JSONObject correctedTupleArchive = new JSONObject(archive.toString());
        JSONObject correctedTupleProgress = findArchiveProgress(correctedTupleArchive,
                "cw-artwork-tie-fixture");
        JSONObject correctedTupleTitle = findArchiveSavedTitle(correctedTupleArchive,
                "cw-artwork-tie-fixture");
        long equalUpdatedAt = correctedTupleProgress.getLong("updatedAt");
        setArchiveResumeEpisodeTuple(correctedTupleProgress, "fixture-episode-7", "The Still", 1, 8);
        setArchiveResumeEpisodeTuple(correctedTupleTitle, "fixture-episode-7", "The Still", 1, 8);
        assertEquals("corrected tuple import must retain the current timestamp", equalUpdatedAt,
                correctedTupleProgress.getLong("updatedAt"));
        assertEquals("tie fixture must be an equal-timestamp merge", equalUpdatedAt,
                findProgress("cw-artwork-tie-fixture").updatedAt);
        LocalLibraryStore.SavedTitle currentSavedTitle =
                library.getSavedTitle(profileId, "cw-artwork-tie-fixture");
        assertNotNull("saved-title tie fixture must exist before archive merge", currentSavedTitle);
        assertEquals("saved-title archive row must match the current row timestamp",
                correctedTupleTitle.getLong("updatedAt"), currentSavedTitle.updatedAt);
        library.mergeArchiveState(correctedTupleArchive, Collections.singleton(profileId),
                UUID.randomUUID().toString());
        LocalLibraryStore.SavedTitle correctedProgress = findProgress("cw-artwork-tie-fixture");
        assertEquals("the greater corrected episode number must win an equal-timestamp tie", 8,
                correctedProgress.resumeEpisodeNumber);
        assertEquals("same episode ID correction must preserve its local still", episodeArtworkRef,
                artworkRef.get(correctedProgress));
        LocalLibraryStore.SavedTitle correctedSavedTitle =
                library.getSavedTitle(profileId, "cw-artwork-tie-fixture");
        assertEquals("saved-title tie correction must be applied", 8,
                correctedSavedTitle.resumeEpisodeNumber);
        assertEquals("saved-title same-episode correction must preserve its still", episodeArtworkRef,
                artworkRef.get(correctedSavedTitle));

        JSONObject newerMovieArchive = new JSONObject(archive.toString());
        advanceArchiveUpdatedAt(findArchiveProgress(newerMovieArchive,
                "cw-artwork-movie-fixture"));
        advanceArchiveUpdatedAt(findArchiveSavedTitle(newerMovieArchive,
                "cw-artwork-movie-fixture"));
        library.mergeArchiveState(newerMovieArchive, Collections.singleton(profileId),
                UUID.randomUUID().toString());
        assertEquals("newer movie progress without an episode tuple must retain landscape art",
                titleArtworkRef, artworkRef.get(findProgress("cw-artwork-movie-fixture")));
        assertEquals("newer movie saved-title row must retain landscape art", titleArtworkRef,
                artworkRef.get(library.getSavedTitle(profileId, "cw-artwork-movie-fixture")));

        ArrayList<LocalLibraryStore.SavedTitle> titles = new ArrayList<>();
        titles.add(findProgress("cw-artwork-series-fixture"));
        titles.add(findProgress("cw-artwork-movie-fixture"));
        titles.add(findProgress("cw-artwork-legacy-fixture"));
        AtomicReference<View[]> cards = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            LinearLayout page = new LinearLayout(activity);
            Method addRail = null;
            try {
                addRail = OrbitShellActivity.class.getDeclaredMethod(
                        "addHomeContinueWatchingRail", LinearLayout.class, List.class,
                        boolean.class, String.class);
                addRail.setAccessible(true);
                addRail.invoke(activity, page, titles, false, profileId);
                HorizontalScrollView rail = (HorizontalScrollView) page.getChildAt(0);
                LinearLayout row = (LinearLayout) rail.getChildAt(0);
                cards.set(new View[]{row.getChildAt(0), row.getChildAt(1), row.getChildAt(2)});
            } catch (Throwable error) {
                throw new AssertionError("Could not build artwork fixture cards", error);
            }
        });
        assertNotNull("the Home rail must create both artwork cards", cards.get());
        awaitUi("episode still artwork", () -> hasLoadedArtwork(cards.get()[0]));
        awaitUi("movie backdrop artwork", () -> hasLoadedArtwork(cards.get()[1]));
        awaitUi("legacy title artwork", () -> hasLoadedArtwork(cards.get()[2]));
        Bitmap episodeBitmap = ((BitmapDrawable) continueArtworkImage(cards.get()[0])
                .getDrawable()).getBitmap();
        int episodeColor = episodeBitmap.getPixel(10, 10);
        assertTrue("series cards must show the cached episode still ahead of title art",
                Color.green(episodeColor) > Color.red(episodeColor));
        Bitmap movieBitmap = ((BitmapDrawable) continueArtworkImage(cards.get()[1])
                .getDrawable()).getBitmap();
        int movieColor = movieBitmap.getPixel(10, 10);
        assertTrue("movie cards must show their retained landscape backdrop",
                Color.red(movieColor) > Color.green(movieColor));
        Bitmap legacyBitmap = ((BitmapDrawable) continueArtworkImage(cards.get()[2])
                .getDrawable()).getBitmap();
        int legacyColor = legacyBitmap.getPixel(10, 10);
        assertTrue("legacy rows must fall back to their retained title art",
                Color.red(legacyColor) > Color.green(legacyColor));
        assertNotNull("episode-card progress must remain visible",
                findTagged(cards.get()[0], "home_continue_progress"));

        JSONObject nextEpisodeArchive = new JSONObject(sameEpisodeArchive.toString());
        JSONObject nextEpisodeProgress = findArchiveProgress(nextEpisodeArchive,
                "cw-artwork-series-fixture");
        JSONObject nextEpisodeTitle = findArchiveSavedTitle(nextEpisodeArchive,
                "cw-artwork-series-fixture");
        setArchiveResumeEpisode(nextEpisodeProgress, "fixture-episode-8", "Next Still", 1, 8);
        setArchiveResumeEpisode(nextEpisodeTitle, "fixture-episode-8", "Next Still", 1, 8);
        library.mergeArchiveState(nextEpisodeArchive, Collections.singleton(profileId),
                UUID.randomUUID().toString());
        LocalLibraryStore.SavedTitle changedEpisode = findProgress("cw-artwork-series-fixture");
        assertEquals("a different imported episode must clear its stale playback artwork", "",
                artworkRef.get(changedEpisode));
        assertEquals("saved-title progress must clear stale episode artwork too", "",
                artworkRef.get(library.getSavedTitle(profileId, "cw-artwork-series-fixture")));
        assertEquals("the newer imported episode tuple must be applied", "fixture-episode-8",
                changedEpisode.resumeEpisodeId);

        AtomicReference<View> changedEpisodeCard = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            LinearLayout page = new LinearLayout(activity);
            try {
                Method addRail = OrbitShellActivity.class.getDeclaredMethod(
                        "addHomeContinueWatchingRail", LinearLayout.class, List.class,
                        boolean.class, String.class);
                addRail.setAccessible(true);
                addRail.invoke(activity, page, Collections.singletonList(changedEpisode), false,
                        profileId);
                HorizontalScrollView rail = (HorizontalScrollView) page.getChildAt(0);
                changedEpisodeCard.set(((LinearLayout) rail.getChildAt(0)).getChildAt(0));
            } catch (Throwable error) {
                throw new AssertionError("Could not build changed-episode fallback card", error);
            }
        });
        awaitUi("changed episode title-art fallback", () -> hasLoadedArtwork(changedEpisodeCard.get()));
        Bitmap changedEpisodeFallback = ((BitmapDrawable) continueArtworkImage(
                changedEpisodeCard.get()).getDrawable()).getBitmap();
        int changedEpisodeFallbackColor = changedEpisodeFallback.getPixel(10, 10);
        assertTrue("a changed episode without retained still art must fall back to title art",
                Color.red(changedEpisodeFallbackColor) > Color.green(changedEpisodeFallbackColor));
    }

    @Test public void homeProviderPosterUses114DpAndSearchAndTvWidthsStayUnchanged()
            throws Exception {
        AtomicInteger homeWidthPx = new AtomicInteger();
        AtomicInteger searchWidthPx = new AtomicInteger();
        AtomicInteger televisionWidthPx = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        instrumentation.runOnMainSync(() -> {
            try {
                homeWidthPx.set(providerRailCardWidthPx(ShellRoute.HOME, false));
                searchWidthPx.set(providerRailCardWidthPx(ShellRoute.SEARCH, false));
                televisionWidthPx.set(providerRailCardWidthPx(ShellRoute.HOME, true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        if (failure.get() != null) throw new AssertionError("Could not inspect provider rail card", failure.get());
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals("Home phone posters must match the 114dp mockup width",
                Math.round(114 * density), homeWidthPx.get());
        assertEquals("phone Search posters keep their existing 93dp width",
                Math.round(93 * density), searchWidthPx.get());
        assertEquals("TV provider posters keep their existing 94dp width",
                Math.round(94 * density), televisionWidthPx.get());
    }

    private int providerRailCardWidthPx(ShellRoute route, boolean television) throws Exception {
        Field shellStateField = OrbitShellActivity.class.getDeclaredField("shellState");
        shellStateField.setAccessible(true);
        ShellState shellState = (ShellState) shellStateField.get(activity);
        ShellRoute previousRoute = shellState.getRoute();
        shellState.navigateTo(route);
        try {
            LinearLayout page = new LinearLayout(activity);
            List<MediaItem> items = Collections.singletonList(new MediaItem(
                    "home-sizing-provider-fixture", "Provider Sizing Fixture", MediaKind.MOVIE,
                    "", "", "", ""));
            Method addRail = OrbitShellActivity.class.getDeclaredMethod("addProviderMediaRail",
                    LinearLayout.class, List.class, MediaProvider.class, boolean.class, String.class);
            addRail.setAccessible(true);
            addRail.invoke(activity, page, items, null, television, "home_sizing_fixture");

            RecyclerView rail = (RecyclerView) page.getChildAt(0);
            RecyclerView.ViewHolder card = rail.getAdapter().createViewHolder(rail, 0);
            return card.itemView.getLayoutParams().width;
        } finally {
            shellState.navigateTo(previousRoute);
        }
    }

    private LocalLibraryStore.SavedTitle findProgress(String stableId) {
        for (LocalLibraryStore.SavedTitle title : library.getContinueWatching(profileId, 50)) {
            if (stableId.equals(title.stableId)) return title;
        }
        return null;
    }

    private JSONObject findArchiveProgress(JSONObject archive, String stableId) throws Exception {
        JSONArray rows = archive.getJSONArray("playbackProgress");
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            if (stableId.equals(row.optString("stableId"))) return row;
        }
        throw new AssertionError("Could not find archived playback row " + stableId);
    }

    private JSONObject findArchiveSavedTitle(JSONObject archive, String stableId) throws Exception {
        JSONArray rows = archive.getJSONArray("savedTitles");
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            if (stableId.equals(row.optString("stableId"))) return row;
        }
        throw new AssertionError("Could not find archived saved title " + stableId);
    }

    private void validateArchiveRow(JSONObject row) throws Exception {
        Class<?> archiveService = Class.forName(
                "com.auras.orbit.backup.OrbitLibraryArchiveService");
        Method validate = archiveService.getDeclaredMethod("validateTitleRow",
                JSONObject.class, java.util.Set.class);
        validate.setAccessible(true);
        validate.invoke(null, row, Collections.singleton(profileId));
    }

    private String importResumeArtworkValue(JSONObject row) throws Exception {
        Method convert = LocalLibraryStore.class.getDeclaredMethod("titleValues", JSONObject.class);
        convert.setAccessible(true);
        ContentValues values = (ContentValues) convert.invoke(null, row);
        return values.getAsString("resume_artwork_ref");
    }

    private void setArchiveResumeEpisode(JSONObject row, String id, String title, int season,
            int episodeNumber) throws Exception {
        setArchiveResumeEpisodeTuple(row, id, title, season, episodeNumber);
        advanceArchiveUpdatedAt(row);
    }

    private void setArchiveResumeEpisodeTuple(JSONObject row, String id, String title, int season,
            int episodeNumber) throws Exception {
        row.put("resumeEpisodeId", id);
        row.put("resumeEpisodeTitle", title);
        row.put("resumeSeason", season);
        row.put("resumeEpisodeNumber", episodeNumber);
    }

    private void advanceArchiveUpdatedAt(JSONObject row) throws Exception {
        row.put("updatedAt", row.getLong("updatedAt") + 1_000L);
    }

    private String libraryArtworkReference(char digestChar) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] profileBytes = digest.digest(profileId.getBytes(StandardCharsets.UTF_8));
        StringBuilder profileHash = new StringBuilder(64);
        for (byte value : profileBytes) profileHash.append(String.format(java.util.Locale.ROOT,
                "%02x", value & 0xff));
        return "orbit-library-artwork:" + profileHash + ":" + repeated(digestChar, 64);
    }

    private String repeated(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private void writeLibraryArtwork(String reference, int color) throws Exception {
        String encoded = reference.substring("orbit-library-artwork:".length());
        int separator = encoded.indexOf(':');
        File directory = new File(new File(target.getFilesDir(), "provider-library-artwork"),
                encoded.substring(0, separator));
        assertTrue("artwork cache directory must be writable",
                directory.isDirectory() || directory.mkdirs());
        File outputFile = new File(directory, encoded.substring(separator + 1) + ".img");
        Bitmap bitmap = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            assertTrue("test artwork must encode", bitmap.compress(Bitmap.CompressFormat.PNG,
                    100, output));
        } finally {
            bitmap.recycle();
        }
    }

    private boolean hasLoadedArtwork(View card) {
        AtomicReference<Boolean> loaded = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            ImageView candidate = continueArtworkImage(card);
            loaded.set(candidate.getDrawable() instanceof BitmapDrawable);
        });
        return loaded.get();
    }

    private ImageView continueArtworkImage(View card) {
        View artwork = ((ViewGroup) card).getChildAt(0);
        return (ImageView) ((ViewGroup) artwork).getChildAt(1);
    }

    private void awaitUi(String description, Condition condition) {
        long deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition.isTrue()) return;
            android.os.SystemClock.sleep(40L);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private View findTagged(String tag) {
        AtomicReference<View> match = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> match.set(findTagged(
                activity.getWindow().getDecorView(), tag)));
        return match.get();
    }

    private View findTagged(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = findTagged(group.getChildAt(index), tag);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void collectText(View root, List<String> labels) {
        if (root instanceof TextView) {
            labels.add(((TextView) root).getText().toString());
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectText(group.getChildAt(index), labels);
            }
        }
    }

    private View findText(String text) {
        AtomicReference<View> match = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> match.set(findText(
                activity.getWindow().getDecorView(), text)));
        return match.get();
    }

    private View findText(View root, String text) {
        if (root == null) return null;
        if (root instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = findText(group.getChildAt(index), text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void click(View view) {
        instrumentation.runOnMainSync(() -> view.performClick());
    }

    private interface Condition { boolean isTrue(); }
}
