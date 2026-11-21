package com.auras.orbit.player;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.InputType;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.view.WindowCompat;
import androidx.media3.common.C;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import com.auras.orbit.OrbitApplication;
import com.auras.orbit.R;
import com.auras.orbit.library.LocalLibraryStore;
import com.auras.orbit.library.LocalLibraryStore.SavedTitle;
import com.auras.orbit.ui.PageWindow;
import com.auras.orbit.providers.Cs3PlaybackHooks;
import com.auras.orbit.providers.MediaProvider;
import com.auras.orbit.providers.ProviderBatch;
import com.auras.orbit.providers.ProviderLinkResult;
import com.auras.orbit.providers.ProviderOperationCoordinator;
import com.auras.orbit.providers.ProviderOutcome;
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities;
import com.lagradost.cloudstream3.subtitles.SubtitleResource;
import com.lagradost.cloudstream3.syncproviders.AccountManager;
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One fullscreen player implementation for phone, Android TV, provider streams, and Jellyfin. */
public final class OrbitPlayerActivity extends Activity {
    private static final String TORRENT_AUTH_HEADER = "X-Orbit-Torrent-Token";
    private static final int SCREEN = Color.rgb(5, 7, 9);
    private static final int SURFACE = Color.rgb(23, 27, 31);
    private static final int SURFACE_HI = Color.rgb(36, 41, 46);
    private static final int TEAL = Color.rgb(85, 206, 184);
    private static final int TEXT = Color.rgb(243, 244, 241);
    private static final int MUTED = Color.rgb(162, 172, 178);
    private static final int CHEVRON_COLOR = Color.rgb(137, 147, 155);
    private static final long CONTROL_TIMEOUT_MS = 5_500L;
    private static final long PROGRESS_INTERVAL_MS = 5_000L;

    private static final Map<String, PlaybackObserver> OBSERVERS = new ConcurrentHashMap<>();
    private static final Map<String, PendingPlayback> PENDING_REQUESTS = new ConcurrentHashMap<>();
    private static int pendingShellReturnOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private static final long REQUEST_TTL_MS = 10L * 60L * 1000L;
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final int MAX_OBSERVERS = 64;
    private static final int MAX_ONLINE_SUBTITLE_RESOURCES = 30;
    private static final int PROVIDER_SOURCE_PAGE_SIZE = 40;
    private static final String PROVIDER_SOURCE_ROW_FOCUS_PREFIX = "provider_source_row:";
    private static final int TORRENT_STREAM_READ_TIMEOUT_SECONDS = 120;
    private static final int REQUEST_OPEN_SUBTITLE_DOCUMENT = 7314;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsTask = () -> setControlsVisible(false);
    private final Runnable progressTask = new Runnable() {
        @Override public void run() {
            if (player != null && request != null) {
                saveProgress();
                sendObserverProgress();
                updateTimeAndPrompts();
                main.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        }
    };

    private PlaybackRequest request;
    private DeferredProviderPlayback deferredProviderPlayback;
    private ProviderOperationCoordinator providerOperationCoordinator;
    private ProviderBatch<ProviderLinkResult> providerLinksBatch;
    private int providerResolutionGeneration;
    private boolean providerResolutionTerminal = true;
    private PlaybackRequest.Source currentSource;
    private PlaybackRequest.Source dialogSource;
    private final Set<String> attemptedSourceIds = new HashSet<>();
    private final Set<String> failedSourceIds = new HashSet<>();
    private boolean sourceFailoverPending;
    private boolean playerErrorHandledForCurrentLoad;
    private long pendingSourceFailoverPositionMs;
    private String pendingSourceFailureMessage;
    private String retryingSourceFailureMessage;
    private Cs3PlaybackHooks activeVerifierHooks;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private LocalLibraryStore libraryStore;

    private FrameLayout root;
    private PlayerView playerView;
    private View controlsScrim;
    private LinearLayout topBar;
    private LinearLayout topActions;
    private LinearLayout bottomBar;
    private LinearLayout bottomActions;
    private LinearLayout centerControls;
    private TextView titleView;
    private TextView episodeView;
    private TextView bottomSourceView;
    private TextView infoView;
    private TextView pauseMetaView;
    private TextView statusView;
    private TextView promptButton;
    private TextView playPauseButton;
    private TextView currentTimeView;
    private TextView durationView;
    private SeekBar seekBar;
    private Button captionsButton;
    private Button sourcesButton;
    private Dialog sourcesDialog;
    private Dialog audioTracksDialog;
    private Dialog settingsDialog;
    private LinearLayout providerSourceRows;
    private LinearLayout providerSourcePager;
    private LinearLayout providerSubtitleRows;
    private TextView providerSelectionCaption;
    private TextView providerSourceHeading;
    private int providerSourcePage;
    private Button settingsSpeedButton;
    private Button settingsFitButton;
    private Button settingsSubtitleDelayButton;
    private TextView settingsSpeedHint;
    private TextView settingsSubtitleDelayHint;
    private float playbackSpeed = 1f;
    private long subtitleDelayMs;
    private int screenFitMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private boolean television;
    private Object api33BackCallback;
    private int returnOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
    private boolean controlsVisible = true;
    private boolean seeking;
    private boolean wasPlayingBeforePause;
    private boolean activityStopNotified;
    private boolean startedForCurrentItem;
    private boolean observerReadyForCurrentItem;
    private boolean lastSourceFailed;
    private boolean itemEndHandled;
    private boolean torrentSessionReleased;
    private String activePromptId = "";
    private String pendingSubtitleId = "";
    private String selectedSubtitleId = "";
    private String dialogSubtitleId = "";
    private final Set<String> unrecognizedProviderSubtitleKeys = new HashSet<>();
    private boolean subtitlesEnabled;
    private boolean automaticSubtitleSelectionApplied;
    private boolean manualSubtitleChoiceMade;
    private String selectedExternalAudioTrackId = "";
    private String pendingNativeAudioGroupId = "";
    private int pendingNativeAudioTrackIndex = -1;
    private Tracks.Group externalAudioTrackGroup;
    private boolean pendingExternalAudioTrackSelection;
    private boolean pendingNativeAudioTrackSelection;
    private boolean nativeAudioTrackSelected;

    /** Observer callbacks execute on the player Activity's main thread. */
    public interface PlaybackObserver {
        /** Called when Media3 reports the selected item as ready; source changes may report again. */
        void onPlaybackReady(PlaybackSnapshot snapshot);
        /** Called once when playback first starts for each queued item. */
        void onPlaybackStarted(PlaybackSnapshot snapshot);
        /** Called every five seconds and immediately after seeks and pauses. */
        void onPlaybackProgress(PlaybackSnapshot snapshot);
        /** Called for a recoverable source error; a later source choice can continue playback. */
        void onPlaybackError(PlaybackSnapshot snapshot);
        /** Called when an item completes or the player Activity ends. */
        void onPlaybackStopped(PlaybackSnapshot snapshot, StopReason reason);
    }

    /** Final reason for an episode or player session stop. */
    public enum StopReason { COMPLETED, USER_EXIT, FAILURE }

    /** Playback metadata suitable for server reporting; this value never contains URLs or headers. */
    public static final class PlaybackSnapshot {
        public final String sessionKey, profileId, stableId, providerId, title, mediaType;
        public final String episodeId, episodeTitle;
        public final int season, episodeNumber;
        public final long positionMs, durationMs;
        public final boolean isPlaying;

        private PlaybackSnapshot(String sessionKey, PlaybackRequest request, long positionMs,
                long durationMs, boolean isPlaying) {
            this.sessionKey = sessionKey;
            this.profileId = request.profileId;
            this.stableId = request.media.stableId;
            this.providerId = request.media.providerId;
            this.title = request.media.title;
            this.mediaType = request.media.mediaType;
            this.episodeId = request.episode.id;
            this.episodeTitle = request.episode.title;
            this.season = request.episode.season;
            this.episodeNumber = request.episode.number;
            this.positionMs = Math.max(0L, positionMs);
            this.durationMs = Math.max(0L, durationMs);
            this.isPlaying = isPlaying;
        }
    }

    /** Registers a process-local observer and returns a random request correlation key. */
    public static String registerPlaybackObserver(PlaybackObserver observer) {
        if (observer == null) throw new IllegalArgumentException("Playback observer is required.");
        String key = UUID.randomUUID().toString();
        synchronized (OBSERVERS) {
            if (OBSERVERS.size() >= MAX_OBSERVERS) {
                throw new IllegalStateException("Too many playback observers are registered.");
            }
            OBSERVERS.put(key, observer);
        }
        return key;
    }

    /** Release a key if the caller registered an observer but did not launch playback. */
    public static void unregisterPlaybackObserver(String key) {
        if (key != null && !key.isEmpty()) OBSERVERS.remove(key);
    }

    /** The Intent contains only this random, one-use key; stream URLs and headers stay in process memory. */
    static Intent createPlaybackIntent(Context context, PlaybackRequest request) {
        if (context == null || request == null) throw new IllegalArgumentException("Playback context and request are required.");
        return createPendingPlaybackIntent(context, new PendingPlayback(request, null,
                SystemClock.elapsedRealtime()));
    }

    /** Opens Orbit Player before resolving a provider's movie or episode links. */
    public static Intent createDeferredProviderPlaybackIntent(Context context, String profileId,
            MediaProvider provider, String providerItemId, PlaybackRequest.MediaMetadata media,
            PlaybackRequest.EpisodeMetadata episode, long initialPositionMs,
            ProviderAvailabilityCheck availabilityCheck) {
        if (context == null || provider == null || provider.descriptor() == null
                || providerItemId == null || providerItemId.trim().isEmpty()
                || media == null || availabilityCheck == null) {
            throw new IllegalArgumentException("A provider playback request is incomplete.");
        }
        DeferredProviderPlayback deferred = new DeferredProviderPlayback(profileId, provider,
                providerItemId, media, episode, initialPositionMs, availabilityCheck);
        return createPendingPlaybackIntent(context, new PendingPlayback(null, deferred,
                SystemClock.elapsedRealtime()));
    }

    /** Captures the phone shell's orientation so it can restore its window after player exit. */
    private static synchronized void rememberShellReturnOrientation(int orientation) {
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            pendingShellReturnOrientation = orientation;
        }
    }

    /** Consumed once by the shell when it resumes from this player. */
    public static synchronized int consumeShellReturnOrientation() {
        int orientation = pendingShellReturnOrientation;
        pendingShellReturnOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        return orientation;
    }

    private static Intent createPendingPlaybackIntent(Context context, PendingPlayback pending) {
        String key = UUID.randomUUID().toString();
        synchronized (PENDING_REQUESTS) {
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, PendingPlayback> entry : PENDING_REQUESTS.entrySet()) {
                PendingPlayback stale = entry.getValue();
                if (PlaybackHandoffExpiry.isExpired(stale.createdAtMs, now, REQUEST_TTL_MS)
                        && PENDING_REQUESTS.remove(entry.getKey(), stale)) {
                    stale.close();
                }
            }
            if (PENDING_REQUESTS.size() >= MAX_PENDING_REQUESTS) {
                throw new IllegalStateException("Too many playback requests are waiting to open.");
            }
            PENDING_REQUESTS.put(key, pending);
        }
        return new Intent(context, OrbitPlayerActivity.class)
                .putExtra(PlaybackRequest.EXTRA_REQUEST_KEY, key);
    }

    /** Consumes the in-memory request exactly once. */
    static PlaybackRequest consumePlaybackRequest(String key) {
        PendingPlayback pending = consumePendingPlayback(key);
        return pending == null ? null : pending.request;
    }

    private static PendingPlayback consumePendingPlayback(String key) {
        PendingPlayback pending = key == null ? null : PENDING_REQUESTS.remove(key);
        if (pending == null) return null;
        if (PlaybackHandoffExpiry.isExpired(pending.createdAtMs,
                SystemClock.elapsedRealtime(), REQUEST_TTL_MS)) {
            pending.close();
            return null;
        }
        return pending;
    }

    private static final class PendingPlayback {
        final PlaybackRequest request;
        final DeferredProviderPlayback deferredProviderPlayback;
        final long createdAtMs;
        PendingPlayback(PlaybackRequest request, DeferredProviderPlayback deferredProviderPlayback,
                long createdAtMs) {
            this.request = request;
            this.deferredProviderPlayback = deferredProviderPlayback;
            this.createdAtMs = createdAtMs;
        }
        void close() {
            if (request != null) {
                unregisterPlaybackObserver(request.observerKey);
                request.closeProviderHooks();
            }
        }
    }

    private static final class DeferredProviderPlayback {
        final String profileId;
        final MediaProvider provider;
        final String providerItemId;
        final PlaybackRequest.MediaMetadata media;
        final PlaybackRequest.EpisodeMetadata episode;
        final long initialPositionMs;
        final ProviderAvailabilityCheck availabilityCheck;
        DeferredProviderPlayback(String profileId, MediaProvider provider, String providerItemId,
                PlaybackRequest.MediaMetadata media, PlaybackRequest.EpisodeMetadata episode,
                long initialPositionMs, ProviderAvailabilityCheck availabilityCheck) {
            this.profileId = profileId;
            this.provider = provider;
            this.providerItemId = providerItemId.trim();
            this.media = media;
            this.episode = episode == null ? PlaybackRequest.EpisodeMetadata.EMPTY : episode;
            this.initialPositionMs = Math.max(0L, initialPositionMs);
            this.availabilityCheck = availabilityCheck;
        }
    }

    /** A weak shell-side guard for provider removal and episode/profile changes during lookup. */
    public interface ProviderAvailabilityCheck {
        boolean isAvailable(String profileId, MediaProvider provider);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerApi33BackCallback();
        String requestKey = getIntent() == null ? null
                : getIntent().getStringExtra(PlaybackRequest.EXTRA_REQUEST_KEY);
        PendingPlayback pending = consumePendingPlayback(requestKey);
        request = pending == null ? null : pending.request;
        deferredProviderPlayback = pending == null ? null : pending.deferredProviderPlayback;
        releaseUnreferencedSubtitleDocumentGrants();
        television = isTelevisionDevice();
        if (!television) {
            int initialOrientation = getResources().getConfiguration().orientation;
            returnOrientation = initialOrientation == Configuration.ORIENTATION_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            rememberShellReturnOrientation(returnOrientation);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        hideSystemChrome();
        if (request == null && deferredProviderPlayback == null) {
            showInvalidRequest();
            return;
        }
        android.app.Application application = getApplication();
        libraryStore = application instanceof OrbitApplication
                ? ((OrbitApplication) application).getLocalLibraryStore()
                : new LocalLibraryStore(this);
        if (deferredProviderPlayback != null) {
            showProviderResolutionState("Finding links…", true);
            startDeferredProviderResolution();
            return;
        }
        currentSource = request.getDefaultSource();
        buildPlayerSurface();
        buildPlayer();
        loadSource(currentSource, request.initialPositionMs, true);
        main.postDelayed(progressTask, PROGRESS_INTERVAL_MS);
    }

    private void hideSystemChrome() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveSystemBars(getWindow());
    }

    private static void applyImmersiveSystemBars(Window window) {
        WindowCompat.enableEdgeToEdge(window);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            applyLegacyTransparentSystemBarColors(window);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Api30WindowInsets.hideSystemBars(window)) return;
        applyLegacyImmersiveFlags(window);
    }

    /** API 21–34 need explicit transparent bar colors; API 35+ enforces edge-to-edge. */
    @SuppressWarnings("deprecation")
    private static void applyLegacyTransparentSystemBarColors(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    /** Legacy immersive flags remain the compatibility path on devices before API 30. */
    @SuppressWarnings("deprecation")
    private static void applyLegacyImmersiveFlags(Window window) {
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    private static final class Api30WindowInsets {
        static boolean hideSystemBars(Window window) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return false;
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
            return true;
        }
    }

    private void registerApi33BackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33BackHandling.register(this);
        }
    }

    private void unregisterApi33BackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33BackHandling.unregister(this);
        }
    }

    private void handleBackNavigation() {
        finishPlayback(lastSourceFailed ? StopReason.FAILURE : StopReason.USER_EXIT);
    }

    private static final class Api33BackHandling {
        static void register(OrbitPlayerActivity activity) {
            OnBackInvokedCallback callback = activity::handleBackNavigation;
            activity.api33BackCallback = callback;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }

        static void unregister(OrbitPlayerActivity activity) {
            Object callback = activity.api33BackCallback;
            if (callback instanceof OnBackInvokedCallback) {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                        (OnBackInvokedCallback) callback);
                activity.api33BackCallback = null;
            }
        }
    }

    private void showInvalidRequest() {
        root = new FrameLayout(this);
        root.setBackgroundColor(SCREEN);
        LinearLayout message = new LinearLayout(this);
        message.setGravity(Gravity.CENTER);
        message.setOrientation(LinearLayout.VERTICAL);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView heading = text("Playback request unavailable", 22, TEXT, true);
        TextView detail = text("This playback link is invalid or no longer available.", 15, MUTED, false);
        Button back = button("Back", v -> finishPlayback(StopReason.USER_EXIT));
        message.addView(heading);
        message.addView(detail, marginParams(-2, -2, 0, 12, 0, 18));
        message.addView(back, new LinearLayout.LayoutParams(dp(180), dp(52)));
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        back.requestFocus();
    }

    private void showProviderResolutionState(String status, boolean loading) {
        DeferredProviderPlayback pending = deferredProviderPlayback;
        root = new FrameLayout(this);
        root.setBackgroundColor(SCREEN);
        LinearLayout message = new LinearLayout(this);
        message.setGravity(Gravity.CENTER);
        message.setOrientation(LinearLayout.VERTICAL);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        String title = pending == null ? "Provider playback" : pending.media.title;
        message.addView(text(title, 22, TEXT, true));
        String episodeTitle = pending == null ? "" : pending.episode.title;
        if (episodeTitle != null && !episodeTitle.trim().isEmpty()) {
            message.addView(text(episodeTitle, 15, MUTED, false),
                    marginParams(-2, -2, 0, 8, 0, 0));
        }
        if (loading) {
            ProgressBar progress = new ProgressBar(this);
            message.addView(progress, marginParams(dp(42), dp(42), 0, 20, 0, 18));
        }
        TextView detail = text(status, 15, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        message.addView(detail, marginParams(-2, -2, 0, loading ? 0 : 18, 0, 0));
        Button back = button("Back", v -> finishPlayback(StopReason.USER_EXIT));
        message.addView(back, marginParams(dp(180), dp(52), 0, 22, 0, 0));
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        back.requestFocus();
    }

    private void startDeferredProviderResolution() {
        DeferredProviderPlayback pending = deferredProviderPlayback;
        if (pending == null || !(getApplication() instanceof OrbitApplication)) {
            showProviderResolutionState("Orbit could not start this provider link lookup. Return to Details and try again.", false);
            return;
        }
        if (!pending.provider.descriptor().playback || !pending.provider.descriptor().links) {
            showProviderResolutionState("This provider no longer supports playback. Return to Details to choose another title.", false);
            return;
        }
        if (!isDeferredProviderContextCurrent()) {
            showProviderResolutionState("The active profile or provider changed. Return to Details to choose a current title.", false);
            return;
        }
        OrbitApplication application = (OrbitApplication) getApplication();
        providerOperationCoordinator = application.getProviderOperationCoordinator();
        if (providerOperationCoordinator == null) {
            showProviderResolutionState("Orbit could not start this provider link lookup. Return to Details and try again.", false);
            return;
        }
        final int generation = ++providerResolutionGeneration;
        providerResolutionTerminal = false;
        try {
            long timeoutMs = ProviderOperationCoordinator.boundedLinkResolutionTimeoutMs(
                    pending.provider);
            providerLinksBatch = providerOperationCoordinator.loadLinksProgressively(
                    pending.provider, pending.providerItemId, false, timeoutMs, outcomes -> main.post(() ->
                            onDeferredProviderLinksChanged(generation, outcomes)));
        } catch (RuntimeException error) {
            providerLinksBatch = null;
            providerResolutionTerminal = true;
            if (request == null) {
                showProviderResolutionState("Orbit could not start this provider link lookup. Return to Details and try again.", false);
            } else {
                showStatus("Orbit could not continue looking for additional links.");
            }
        }
    }

    private boolean isDeferredProviderContextCurrent() {
        DeferredProviderPlayback pending = deferredProviderPlayback;
        if (pending == null || isFinishing() || isDestroyed()) return false;
        if (!(getApplication() instanceof OrbitApplication)) return false;
        OrbitApplication application = (OrbitApplication) getApplication();
        com.auras.orbit.profile.LocalProfileStore.Profile active =
                application.getLocalProfileStore().getActiveProfile();
        if (active == null || !active.id.equals(pending.profileId)) return false;
        try {
            return pending.availabilityCheck.isAvailable(pending.profileId, pending.provider);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void onDeferredProviderLinksChanged(int generation,
            List<ProviderOutcome<ProviderLinkResult>> outcomes) {
        if (generation != providerResolutionGeneration || deferredProviderPlayback == null
                || isFinishing() || isDestroyed()) return;
        ProviderOutcome<ProviderLinkResult> outcome = outcomes == null || outcomes.isEmpty()
                ? null : outcomes.get(0);
        if (!isDeferredProviderContextCurrent()) {
            providerResolutionTerminal = true;
            if (providerLinksBatch != null) providerLinksBatch.close();
            providerLinksBatch = null;
            deferredProviderPlayback = null;
            String message = "The active profile, title, or provider changed. Return to Details to choose a current title.";
            if (request == null) showProviderResolutionState(message, false);
            else showStatus(message);
            return;
        }
        if (outcome != null && outcome.value != null) {
            try {
                consumeProviderLinkSnapshot(outcome.value);
            } catch (RuntimeException ignored) {
                // An invalid optional candidate must not replace a source already in playback.
            }
        }
        if (outcome != null && outcome.status == ProviderOutcome.Status.LOADING) return;

        providerResolutionTerminal = true;
        providerLinksBatch = null;
        deferredProviderPlayback = null;
        if (request == null) {
            showProviderResolutionState(
                    "No playable links available. Choose another source or press Back to exit.", false);
            return;
        }
        if (sourceFailoverPending) continueSourceFailover();
    }

    private void consumeProviderLinkSnapshot(ProviderLinkResult result) {
        DeferredProviderPlayback pending = deferredProviderPlayback;
        if (pending == null || result == null) return;
        if (request == null) {
            PlaybackRequest firstRequest;
            try {
                firstRequest = PlaybackRequest.fromProviderLinkResult(pending.profileId,
                        pending.media, pending.episode, result,
                        pending.provider.descriptor().name, pending.initialPositionMs);
            } catch (IllegalArgumentException noPlayableCandidateYet) {
                return;
            }
            firstRequest.activateProviderHooks(providerOperationCoordinator);
            request = firstRequest;
            currentSource = request.getDefaultSource();
            releaseUnreferencedSubtitleDocumentGrants();
            buildPlayerSurface();
            buildPlayer();
            loadSource(currentSource, request.initialPositionMs, true);
            main.postDelayed(progressTask, PROGRESS_INTERVAL_MS);
            return;
        }

        PlaybackRequest.Source activeSource = currentSource;
        List<PlaybackRequest.Subtitle> previousSubtitles = activeSource == null
                ? Collections.emptyList() : new ArrayList<>(activeSource.subtitles);
        request.mergeProviderLinkResult(result, pending.provider.descriptor().name,
                providerOperationCoordinator);
        refreshProviderSourceLabels();
        if (activeSource != null && activeSource == currentSource
                && !sameProviderSubtitles(previousSubtitles, activeSource.subtitles)
                && player != null && !sourceFailoverPending) {
            long resumePositionMs = safeCurrentPositionForSourceChange();
            boolean playWhenReady = player.getPlayWhenReady();
            loadSource(activeSource, resumePositionMs, playWhenReady);
        }
        if (sourceFailoverPending) continueSourceFailover();
    }

    private static boolean sameProviderSubtitles(List<PlaybackRequest.Subtitle> first,
            List<PlaybackRequest.Subtitle> second) {
        if (first == second) return true;
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            PlaybackRequest.Subtitle left = first.get(index);
            PlaybackRequest.Subtitle right = second.get(index);
            if (left == null || right == null || !left.id.equals(right.id)
                    || !left.uri.equals(right.uri) || !left.mimeType.equals(right.mimeType)) return false;
        }
        return true;
    }

    private void refreshProviderSourceLabels() {
        if (request == null) return;
        if (sourcesButton != null) sourcesButton.setText("≋  Sources · " + request.sources.size());
        if (currentSource != null) {
            String caption = sourceCaption(currentSource)
                    + subtitleCaption(subtitlesEnabled ? selectedSubtitleId : "");
            if (infoView != null) infoView.setText(caption);
            if (bottomSourceView != null) bottomSourceView.setText(sourceCaption(currentSource));
        }
        updateProviderDialogRows();
    }

    private void updateProviderDialogRows() {
        if (request == null || sourcesDialog == null || !sourcesDialog.isShowing()
                || providerSourceRows == null || providerSubtitleRows == null
                || providerSelectionCaption == null || providerSourcePager == null) return;
        View focused = getCurrentFocus();
        Object focusedTagValue = focused == null ? null : focused.getTag();
        String focusedTag = focusedTagValue instanceof String ? (String) focusedTagValue : null;
        String focusedSourceId = sourceIdFromProviderSourceRowFocusTag(focusedTag);
        providerSourcePage = sourcePageForFocusedSource(request.sources, providerSourcePage,
                focusedSourceId);
        if (providerSourceHeading != null) {
            providerSourceHeading.setText("VIDEO LINKS · " + request.sources.size() + " AVAILABLE");
        }
        PageWindow.Window sourceWindow = PageWindow.of(request.sources.size(), providerSourcePage,
                PROVIDER_SOURCE_PAGE_SIZE);
        providerSourcePage = sourceWindow.page;
        providerSourcePager.removeAllViews();
        TextView range = text("Showing " + (sourceWindow.start + 1) + "–"
                + sourceWindow.end + " of " + sourceWindow.total, 10, MUTED, false);
        providerSourcePager.addView(range, new LinearLayout.LayoutParams(0, dp(36), 1f));
        if (sourceWindow.lastPage > 0) {
            Button previous = button("Previous", view -> {
                providerSourcePage = Math.max(0, sourceWindow.page - 1);
                updateProviderDialogRows();
            });
            previous.setTag("provider_source_page_previous");
            previous.setEnabled(sourceWindow.hasPrevious());
            providerSourcePager.addView(previous);
            TextView pageLabel = text(" " + (sourceWindow.page + 1) + " / "
                    + (sourceWindow.lastPage + 1) + " ", 10, MUTED, false);
            providerSourcePager.addView(pageLabel);
            Button next = button("Next", view -> {
                providerSourcePage = Math.min(sourceWindow.lastPage, sourceWindow.page + 1);
                updateProviderDialogRows();
            });
            next.setTag("provider_source_page_next");
            next.setEnabled(sourceWindow.hasNext());
            providerSourcePager.addView(next);
        }
        providerSourceRows.removeAllViews();
        for (int index = sourceWindow.start; index < sourceWindow.end; index++) {
            PlaybackRequest.Source source = request.sources.get(index);
            Button row = button((source == dialogSource ? "●  " : "○  ")
                    + safeLabel(source.label), view -> {
                dialogSource = source;
                dialogSubtitleId = source == currentSource && subtitlesEnabled
                        ? selectedSubtitleId : "";
                fillSourceRows(providerSourceRows, dialogSource, currentSource);
                fillSubtitleRows(providerSubtitleRows, dialogSource, providerSelectionCaption);
                providerSelectionCaption.setText(sourceCaption(dialogSource)
                        + subtitleCaption(dialogSubtitleId));
            });
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            row.setTag(providerSourceRowFocusTag(source.id));
            providerSourceRows.addView(row, new LinearLayout.LayoutParams(-1, dp(50)));
        }
        fillSourceRows(providerSourceRows, dialogSource, currentSource);
        if (dialogSource != null && dialogSubtitleId.startsWith("external:")
                && !hasSubtitleId(dialogSource,
                        dialogSubtitleId.substring("external:".length()))) {
            dialogSubtitleId = "";
        }
        fillSubtitleRows(providerSubtitleRows, dialogSource, providerSelectionCaption);
        providerSelectionCaption.setText(sourceCaption(dialogSource)
                + subtitleCaption(dialogSubtitleId));
        View restore = null;
        if (focusedSourceId != null) {
            restore = providerSourceRows.findViewWithTag(
                    providerSourceRowFocusTag(focusedSourceId));
        }
        boolean focusedPager = "provider_source_page_previous".equals(focusedTag)
                || "provider_source_page_next".equals(focusedTag);
        if (restore == null && focusedTag != null) {
            restore = providerSourcePager.findViewWithTag(focusedTag);
            if (restore == null) restore = providerSourceRows.findViewWithTag(focusedTag);
            if (restore != null && !restore.isEnabled()) restore = null;
        }
        if ((restore == null || !restore.isEnabled()) && focusedPager) {
            if ("provider_source_page_previous".equals(focusedTag) && sourceWindow.hasNext()) {
                restore = providerSourcePager.findViewWithTag("provider_source_page_next");
            } else if (sourceWindow.hasPrevious()) {
                restore = providerSourcePager.findViewWithTag("provider_source_page_previous");
            } else if (sourceWindow.hasNext()) {
                restore = providerSourcePager.findViewWithTag("provider_source_page_next");
            }
        }
        if ((restore == null || !restore.isEnabled()) && focusedSourceId != null) {
            PlaybackRequest.Source preferred = dialogSource == null ? currentSource : dialogSource;
            if (preferred != null) {
                restore = providerSourceRows.findViewWithTag(
                        providerSourceRowFocusTag(preferred.id));
            }
            if (restore == null && providerSourceRows.getChildCount() > 0) {
                restore = providerSourceRows.getChildAt(0);
            }
        }
        if (restore != null && restore.isEnabled()) restore.requestFocus();
    }

    private void buildPlayerSurface() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setKeepContentOnPlayerReset(true);
        applySavedSubtitleStyle();
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        controlsScrim = new View(this);
        controlsScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000, 0x00000000, 0xD9000000}));
        controlsScrim.setClickable(false);
        root.addView(controlsScrim, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(0, dp(5), dp(20), dp(8));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(topBar, topParams);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);
        titleView = text(request.media.title, television ? 13 : 16, TEXT, true);
        // The vertical LinearLayout gives its text children the full lane width, so center the
        // rendered phone text directly while retaining the TV title's left alignment.
        titleView.setGravity(television ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        String initialEpisodeCaption = episodeCaption();
        episodeView = text(initialEpisodeCaption, television ? 7 : 11, MUTED, false);
        episodeView.setGravity(television ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        episodeView.setVisibility(initialEpisodeCaption.isEmpty() ? View.GONE : View.VISIBLE);
        titleBlock.addView(titleView);
        titleBlock.addView(episodeView, marginParams(-2, -2, 0, dp(3), 0, 0));

        LinearLayout navActions = new LinearLayout(this);
        navActions.setGravity(Gravity.CENTER_VERTICAL);
        navActions.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout backButton = playerNavigationButton(
                "Stop playback and return to title details", v -> finishPlayback(StopReason.USER_EXIT));
        Drawable backChevron = getDrawable(R.drawable.orbit_player_back_chevron);
        if (backChevron != null) {
            backChevron = backChevron.mutate();
            backChevron.setTint(Color.WHITE);
            ImageView backGlyph = playerNavigationGlyph(backChevron, dp(20), dp(20));
            backButton.addView(backGlyph);
        }
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        backParams.leftMargin = dp(5);
        navActions.addView(backButton, backParams);
        FrameLayout replayButton = playerNavigationButton("Replay from beginning", v -> replay());
        Drawable replayIcon = getDrawable(R.drawable.orbit_player_replay);
        if (replayIcon != null) {
            replayIcon = replayIcon.mutate();
            replayIcon.setTint(Color.WHITE);
            replayButton.addView(playerNavigationGlyph(replayIcon, dp(20), dp(20)));
        }
        LinearLayout.LayoutParams replayParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        replayParams.leftMargin = -dp(8);
        replayParams.rightMargin = dp(10);
        navActions.addView(replayButton, replayParams);
        topBar.addView(navActions, new LinearLayout.LayoutParams(-2, -2));
        if (television) {
            LinearLayout brandBlock = new LinearLayout(this);
            brandBlock.setOrientation(LinearLayout.HORIZONTAL);
            brandBlock.setGravity(Gravity.CENTER_VERTICAL);
            ImageView brandMark = new ImageView(this);
            brandMark.setImageResource(R.drawable.auras_orbit_mark);
            brandMark.setContentDescription("Auras Orbit");
            brandMark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            brandBlock.addView(brandMark, new LinearLayout.LayoutParams(dp(32), dp(32)));
            LinearLayout.LayoutParams brandCopyParams = new LinearLayout.LayoutParams(0, -2, 1f);
            brandCopyParams.leftMargin = dp(12);
            brandBlock.addView(titleBlock, brandCopyParams);
            topBar.addView(brandBlock, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            topBar.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (!television) {
            topActions = new LinearLayout(this);
            topActions.setGravity(Gravity.CENTER_VERTICAL);
            topActions.setOrientation(LinearLayout.HORIZONTAL);
            topActions.addView(createSourcesButton(), playerActionLayoutParams(3));
            topActions.addView(createCaptionsButton(), playerActionLayoutParams(3));
            topActions.addView(createTracksButton(), playerActionLayoutParams(3));
            Button settings = button("⋯", v -> openPlaybackSettingsDialog());
            settings.setContentDescription("Player settings");
            stylePlayerIconButton(settings);
            topActions.addView(settings, playerIconLayoutParams(3));
            topBar.addView(topActions, new LinearLayout.LayoutParams(-2, -2));
        }

        infoView = text(sourceCaption(currentSource), television ? 13 : 11, MUTED, false);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        infoParams.leftMargin = dp(24);
        infoParams.bottomMargin = dp(100);
        root.addView(infoView, infoParams);

        String metaLine = request.media.mediaType.toUpperCase(java.util.Locale.ROOT)
                + (request.media.year.isEmpty() ? "" : "  ·  " + request.media.year);
        String pauseCopy = television && !request.media.summary.isEmpty()
                ? metaLine + "\n" + request.media.summary : metaLine;
        pauseMetaView = text(pauseCopy, television ? 15 : 11, TEXT, television);
        pauseMetaView.setMaxLines(television ? 3 : 1);
        pauseMetaView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pauseMetaView.setShadowLayer(dp(4), 0, dp(2), Color.BLACK);
        FrameLayout.LayoutParams pauseMetaParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(television ? 700 : 460)),
                -2, Gravity.BOTTOM | Gravity.LEFT);
        pauseMetaParams.leftMargin = dp(24);
        pauseMetaParams.bottomMargin = dp(126);
        root.addView(pauseMetaView, pauseMetaParams);
        pauseMetaView.setVisibility(View.GONE);

        centerControls = new LinearLayout(this);
        centerControls.setGravity(Gravity.CENTER);
        centerControls.setOrientation(LinearLayout.HORIZONTAL);
        centerControls.setClipChildren(false);
        centerControls.setClipToPadding(false);
        centerControls.setTranslationX(dp(television ? 10.5f : 16f));
        FrameLayout.LayoutParams centerParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(centerControls, centerParams);
        Button rewindButton = button("", v -> seekBy(-10_000L));
        Button forwardButton = button("", v -> seekBy(10_000L));
        int sideTransportSize = television ? 48 : 76;
        styleTransportButton(rewindButton, sideTransportSize, false);
        styleTransportButton(forwardButton, sideTransportSize, false);
        setSkipTransportLabel(rewindButton, "↶", "Rewind 10 seconds");
        setSkipTransportLabel(forwardButton, "↷", "Forward 10 seconds");
        centerControls.addView(rewindButton, fixedTransportLayout(sideTransportSize, television ? 18 : 14));
        playPauseButton = button("Ⅱ", v -> togglePlayPause());
        int mainButtonSize = television ? 65 : 104;
        styleTransportButton(playPauseButton, mainButtonSize, true);
        updatePlayPauseButton(false);
        centerControls.addView(playPauseButton, new LinearLayout.LayoutParams(dp(mainButtonSize), dp(mainButtonSize)));
        centerControls.addView(forwardButton, fixedTransportLayout(sideTransportSize, television ? 18 : 14));

        promptButton = button("", v -> activatePrompt());
        promptButton.setBackground(roundRect(SURFACE_HI, dp(24)));
        promptButton.setOnFocusChangeListener((view, focused) -> view.setBackground(
                roundRect(focused ? SURFACE_HI : SURFACE, dp(24))));
        promptButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams promptParams = new FrameLayout.LayoutParams(-2, dp(48), Gravity.RIGHT | Gravity.BOTTOM);
        promptParams.rightMargin = dp(24);
        promptParams.bottomMargin = dp(108);
        root.addView(promptButton, promptParams);

        statusView = text("", 14, TEXT, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setBackground(roundRect(0xCC11181D, dp(8)));
        statusView.setPadding(dp(18), dp(13), dp(18), dp(13));
        statusView.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(statusView, statusParams);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(22), dp(8), dp(22), dp(17));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottomBar, bottomParams);
        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        currentTimeView = text("0:00", television ? 8 : 12, MUTED, false);
        durationView = text("--:--", television ? 8 : 12, MUTED, false);
        times.addView(currentTimeView);
        View spacer = new View(this);
        times.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        times.addView(durationView);
        seekBar = new SeekBar(this);
        seekBar.setMax(10_000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(TEAL));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xA0FFFFFF));
        seekBar.setContentDescription("Playback timeline");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && player.getDuration() > 0L) {
                    player.seekTo((long) (player.getDuration() * (bar.getProgress() / 10_000d)));
                    saveProgress();
                    sendObserverProgress();
                }
                seeking = false;
                updateTimeAndPrompts();
            }
        });
        bottomBar.addView(times);
        bottomBar.addView(seekBar, marginParams(-1, dp(40), 0, 0, 0, 0));
        if (television) {
            bottomActions = new LinearLayout(this);
            bottomActions.setOrientation(LinearLayout.HORIZONTAL);
            bottomActions.setGravity(Gravity.CENTER_VERTICAL);
            bottomActions.setPadding(0, dp(4), 0, 0);
            LinearLayout episodeBlock = new LinearLayout(this);
            episodeBlock.setOrientation(LinearLayout.VERTICAL);
            TextView bottomEpisode = text(episodeCaption(), 9, TEXT, true);
            bottomSourceView = text(sourceCaption(currentSource), 9, MUTED, false);
            episodeBlock.addView(bottomEpisode);
            episodeBlock.addView(bottomSourceView, marginParams(-2, -2, 0, dp(2), 0, 0));
            bottomActions.addView(episodeBlock, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout centeredActions = new LinearLayout(this);
            centeredActions.setOrientation(LinearLayout.HORIZONTAL);
            centeredActions.setGravity(Gravity.CENTER_VERTICAL);
            centeredActions.addView(createSourcesButton(), playerActionLayoutParams(3));
            centeredActions.addView(createCaptionsButton(), playerActionLayoutParams(3));
            centeredActions.addView(createTracksButton(), playerActionLayoutParams(3));
            Button options = button("Options", v -> openPlaybackSettingsDialog());
            stylePlayerActionButton(options);
            centeredActions.addView(options, playerActionLayoutParams(3));
            bottomActions.addView(centeredActions, new LinearLayout.LayoutParams(-2, -2));
            bottomActions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            bottomBar.addView(bottomActions, new LinearLayout.LayoutParams(-1, -2));
        }

        playerView.setOnClickListener(v -> {
            if (!controlsVisible) setControlsVisible(true);
            else togglePlayPause();
        });
        root.setFocusableInTouchMode(true);
        root.setOnClickListener(v -> {
            if (!controlsVisible) setControlsVisible(true);
        });
        setContentView(root);
        updateTitleAndEpisode();
        setControlsVisible(true);
        if (television) {
            playPauseButton.setFocusableInTouchMode(true);
            playPauseButton.requestFocus();
        }
    }

    private void applySavedSubtitleStyle() {
        if (playerView == null || request == null) return;
        SubtitlePreferences.Style style = SubtitlePreferences.style(this, request.profileId);
        androidx.media3.ui.SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView == null) return;
        subtitleView.setStyle(new CaptionStyleCompat(style.foregroundColor, style.backgroundColor,
                Color.TRANSPARENT, style.edgeType, style.edgeColor, Typeface.DEFAULT));
        subtitleView.setFractionalTextSize(0.0533f * style.textScalePercent / 100f);
    }

    private void buildPlayer() {
        trackSelector = new DefaultTrackSelector(this);
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_BUFFERING) {
                    showStatus(PlaybackStatusMessages.sourceLoadingMessage(retryingSourceFailureMessage));
                } else if (playbackState == Player.STATE_READY) {
                    pendingSourceFailureMessage = null;
                    retryingSourceFailureMessage = null;
                    hideStatus();
                    if (currentSource != null) failedSourceIds.remove(currentSource.id);
                    applyPendingAudioTrackSelection(true);
                    if (!observerReadyForCurrentItem) {
                        observerReadyForCurrentItem = true;
                        notifyObserverReady();
                    }
                    if (!pendingSubtitleId.isEmpty()) selectSubtitleAfterLoad(pendingSubtitleId);
                    else applySubtitleDefaultsIfNeeded();
                    updateTimeAndPrompts();
                } else if (playbackState == Player.STATE_ENDED) {
                    onCurrentItemEnded();
                }
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButton(isPlaying);
                if (isPlaying) {
                    hideStatus();
                    if (!startedForCurrentItem) {
                        startedForCurrentItem = true;
                        notifyObserverStarted();
                    }
                    scheduleControlsHide();
                } else {
                    saveProgress();
                    sendObserverProgress();
                    setControlsVisible(true);
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (!isPlayerErrorForCurrentSource()) return;
                if (playerErrorHandledForCurrentLoad) return;
                playerErrorHandledForCurrentLoad = true;
                if (!selectedExternalAudioTrackId.isEmpty()) {
                    // An optional external audio file must never strand an otherwise playable video.
                    selectedExternalAudioTrackId = "";
                    externalAudioTrackGroup = null;
                    pendingExternalAudioTrackSelection = false;
                    pendingNativeAudioTrackSelection = false;
                    pendingNativeAudioGroupId = "";
                    pendingNativeAudioTrackIndex = -1;
                    resetAudioSelectionOverrides();
                    showStatus("External audio is unavailable; continuing with video audio.");
                    loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                    return;
                }
                if (nativeAudioTrackSelected) {
                    nativeAudioTrackSelected = false;
                    pendingNativeAudioTrackSelection = false;
                    pendingNativeAudioGroupId = "";
                    pendingNativeAudioTrackIndex = -1;
                    resetAudioSelectionOverrides();
                    showStatus("The selected audio track is unavailable; continuing with the default audio.");
                    loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                    return;
                }
                if (subtitlesEnabled || !pendingSubtitleId.isEmpty()) {
                    selectedSubtitleId = "";
                    pendingSubtitleId = "";
                    subtitlesEnabled = false;
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                    updateCaptionsButton();
                    showStatus("The selected subtitles are unavailable; continuing without subtitles.");
                    loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                    return;
                }
                long resumePositionMs = safeCurrentPositionForSourceChange();
                saveProgress();
                hideStatus();
                failCurrentSource(resumePositionMs,
                        PlaybackStatusMessages.sourceFailureMessage(error.errorCode));
            }
            @Override public void onTracksChanged(Tracks tracks) {
                applyPendingAudioTrackSelection(false);
                if (!pendingSubtitleId.isEmpty() && player.getPlaybackState() == Player.STATE_READY) {
                    selectSubtitleAfterLoad(pendingSubtitleId);
                } else if (player.getPlaybackState() == Player.STATE_READY) applySubtitleDefaultsIfNeeded();
            }
        });
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
    }

    private void loadSource(PlaybackRequest.Source source, long resumePositionMs, boolean playWhenReady) {
        if (player == null || source == null) return;
        playerErrorHandledForCurrentLoad = false;
        sourceFailoverPending = false;
        pendingSourceFailoverPositionMs = 0L;
        attemptedSourceIds.add(source.id);
        Origin selectedOrigin = Origin.parse(source.uri);
        boolean loopbackTorrent = isLoopbackTorrentStreamUrl(source.uri)
                && isValidTorrentAuthHeaders(source.headers)
                && OrbitTorrentEngine.isTrustedStreamRequest(source.uri, source.headers);
        if (selectedOrigin != null && "http".equals(selectedOrigin.scheme)
                && ((!source.headers.isEmpty() && !loopbackTorrent)
                        || (hasCleartextQuery(source.uri) && !loopbackTorrent))) {
            long failoverPositionMs = safeCurrentPositionForSourceChange();
            if (activeVerifierHooks != null) activeVerifierHooks.cancelVerifierJob();
            activeVerifierHooks = null;
            saveProgress();
            player.stop();
            player.clearMediaItems();
            lastSourceFailed = true;
            itemEndHandled = false;
            observerReadyForCurrentItem = false;
            currentSource = source;
            dialogSource = source;
            selectedSubtitleId = "";
            pendingSubtitleId = "";
            subtitlesEnabled = false;
            updateCaptionsButton();
            infoView.setText(sourceCaption(source));
            showStatus("Orbit cannot use HTTP links with query strings or provider headers. Choose HTTPS or a headerless link without a query.");
            setControlsVisible(true);
            playerErrorHandledForCurrentLoad = true;
            failCurrentSource(failoverPositionMs);
            return;
        }
        lastSourceFailed = false;
        itemEndHandled = false;
        observerReadyForCurrentItem = false;
        boolean sourceChanged = currentSource != source;
        currentSource = source;
        dialogSource = source;
        Cs3PlaybackHooks nextVerifierHooks = request == null
                ? null : request.providerHooksFor(source);
        if (activeVerifierHooks != nextVerifierHooks) {
            if (activeVerifierHooks != null) activeVerifierHooks.cancelVerifierJob();
            activeVerifierHooks = nextVerifierHooks;
            if (activeVerifierHooks != null) activeVerifierHooks.startVerifierJob();
        }
        if (sourceChanged) {
            selectedExternalAudioTrackId = "";
            externalAudioTrackGroup = null;
            pendingExternalAudioTrackSelection = false;
            pendingNativeAudioTrackSelection = false;
            pendingNativeAudioGroupId = "";
            pendingNativeAudioTrackIndex = -1;
            nativeAudioTrackSelected = false;
            resetAudioSelectionOverrides();
            selectedSubtitleId = "";
            subtitlesEnabled = false;
            automaticSubtitleSelectionApplied = false;
            manualSubtitleChoiceMade = false;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            updateCaptionsButton();
        }
        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(source.uri).setMediaId(source.id);
        if (!source.mimeType.isEmpty()) itemBuilder.setMimeType(source.mimeType);
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (PlaybackRequest.Subtitle subtitle : source.subtitles) {
            MediaItem.SubtitleConfiguration.Builder subtitleBuilder =
                    new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.uri))
                            .setId(subtitle.id).setLabel(subtitle.label).setSelectionFlags(0)
                            .setMimeType(subtitle.mimeType);
            if (!subtitle.language.isEmpty()) subtitleBuilder.setLanguage(subtitle.language);
            subtitles.add(subtitleBuilder.build());
        }
        itemBuilder.setSubtitleConfigurations(subtitles);
        long safePosition = Math.max(0L, resumePositionMs);
        Cs3PlaybackHooks sourceHooks = request == null ? null : request.providerHooksFor(source);
        Interceptor providerInterceptor;
        try {
            providerInterceptor = sourceHooks == null ? null : sourceHooks.videoInterceptor();
        } catch (Throwable providerHookFailure) {
            long failoverPositionMs = safeCurrentPositionForSourceChange();
            if (activeVerifierHooks != null) activeVerifierHooks.cancelVerifierJob();
            activeVerifierHooks = null;
            saveProgress();
            player.stop();
            player.clearMediaItems();
            lastSourceFailed = true;
            itemEndHandled = false;
            observerReadyForCurrentItem = false;
            currentSource = source;
            dialogSource = source;
            selectedSubtitleId = "";
            pendingSubtitleId = "";
            subtitlesEnabled = false;
            updateCaptionsButton();
            infoView.setText(sourceCaption(source));
            showStatus("This source could not start. Choose another source to try again.");
            setControlsVisible(true);
            playerErrorHandledForCurrentLoad = true;
            failCurrentSource(failoverPositionMs);
            return;
        }
        Map<String, Map<String, String>> subtitleHeadersByResource = subtitleHeadersByResource(source);
        OriginBoundHttpDataSourceFactory httpFactory =
                new OriginBoundHttpDataSourceFactory(source.uri, source.headers,
                        providerInterceptor, subtitleHeadersByResource, subtitleResourceUris(source));
        DefaultDataSource.Factory dataSources = new DefaultDataSource.Factory(this, httpFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSources);
        mediaSourceFactory.setSubtitleParserFactory(new SubtitleOffsetParserFactory(
                new DefaultSubtitleParserFactory(), () -> subtitleDelayMs,
                subtitleId -> reportUnrecognizedProviderSubtitle(source.id, subtitleId)));
        MediaSource mediaSource = mediaSourceFactory.createMediaSource(itemBuilder.build());
        PlaybackRequest.AudioTrack externalAudio = findAudioTrack(source, selectedExternalAudioTrackId);
        if (externalAudio != null && isUnsafeCleartextMediaUri(externalAudio.uri, externalAudio.headers)) {
            selectedExternalAudioTrackId = "";
            externalAudio = null;
            resetAudioSelectionOverrides();
            showStatus("External audio is unavailable; continuing with video audio.");
        }
        if (externalAudio != null) {
            try {
                MediaItem audioItem = new MediaItem.Builder()
                        .setUri(externalAudio.uri)
                        .setMimeType(MimeTypes.AUDIO_UNKNOWN)
                        .setMediaId("orbit-external-audio-" + externalAudio.id)
                        .build();
                OriginBoundHttpDataSourceFactory audioHttpFactory =
                        new OriginBoundHttpDataSourceFactory(externalAudio.uri, externalAudio.headers,
                                providerInterceptor);
                DefaultDataSource.Factory audioDataSources =
                        new DefaultDataSource.Factory(this, audioHttpFactory);
                MediaSource audioMediaSource = new DefaultMediaSourceFactory(audioDataSources)
                        .createMediaSource(audioItem);
                mediaSource = new MergingMediaSource(mediaSource, audioMediaSource);
                externalAudioTrackGroup = null;
                pendingExternalAudioTrackSelection = true;
            } catch (RuntimeException invalidAudio) {
                selectedExternalAudioTrackId = "";
                externalAudioTrackGroup = null;
                pendingExternalAudioTrackSelection = false;
                resetAudioSelectionOverrides();
                showStatus("External audio is unavailable; continuing with video audio.");
            }
        } else {
            externalAudioTrackGroup = null;
            pendingExternalAudioTrackSelection = false;
        }
        player.setMediaSource(mediaSource, safePosition);
        player.prepare();
        player.setPlayWhenReady(playWhenReady);
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(PlaybackStatusMessages.sourceLoadingMessage(retryingSourceFailureMessage));
        infoView.setText(sourceCaption(source));
        updateTitleAndEpisode();
    }

    private long safeCurrentPositionForSourceChange() {
        if (player == null) return 0L;
        long positionMs = Math.max(0L, player.getCurrentPosition());
        long durationMs = player.getDuration();
        if (durationMs > 0L) positionMs = Math.min(positionMs, durationMs);
        return positionMs;
    }

    private boolean isPlayerErrorForCurrentSource() {
        if (player == null || currentSource == null) return false;
        MediaItem mediaItem = player.getCurrentMediaItem();
        return mediaItem != null && currentSource.id.equals(mediaItem.mediaId);
    }

    private void prepareManualSourceSelection(PlaybackRequest.Source source) {
        if (source == null) return;
        attemptedSourceIds.remove(source.id);
        failedSourceIds.remove(source.id);
        sourceFailoverPending = false;
        pendingSourceFailoverPositionMs = 0L;
        pendingSourceFailureMessage = null;
        retryingSourceFailureMessage = null;
    }

    private void failCurrentSource(long resumePositionMs) {
        failCurrentSource(resumePositionMs, PlaybackStatusMessages.sourceFailureMessage());
    }

    private void failCurrentSource(long resumePositionMs, String failureMessage) {
        lastSourceFailed = true;
        sourceFailoverPending = true;
        pendingSourceFailoverPositionMs = Math.max(0L, resumePositionMs);
        pendingSourceFailureMessage = failureMessage;
        if (currentSource != null) failedSourceIds.add(currentSource.id);
        continueSourceFailover();
    }

    private PlaybackRequest.Source nextUnattemptedSourceAfterCurrent() {
        if (request == null || currentSource == null) return null;
        for (PlaybackRequest.Source source : request.sources) {
            if (source.id.equals(currentSource.id)) continue;
            if (!attemptedSourceIds.contains(source.id) && !failedSourceIds.contains(source.id)) {
                return source;
            }
        }
        return null;
    }

    /** Advances once per failed source, preserving the current in-episode position when possible. */
    private void continueSourceFailover() {
        if (!sourceFailoverPending) return;
        PlaybackRequest.Source next = nextUnattemptedSourceAfterCurrent();
        if (next != null) {
            long resumePositionMs = pendingSourceFailoverPositionMs;
            sourceFailoverPending = false;
            pendingSourceFailoverPositionMs = 0L;
            retryingSourceFailureMessage = pendingSourceFailureMessage;
            pendingSourceFailureMessage = null;
            showStatus(PlaybackStatusMessages.sourceLoadingMessage(retryingSourceFailureMessage));
            setControlsVisible(true);
            loadSource(next, resumePositionMs, true);
            return;
        }
        if (!providerResolutionTerminal) {
            showStatus(PlaybackStatusMessages.waitingForNextSource(pendingSourceFailureMessage));
            setControlsVisible(true);
            return;
        }
        sourceFailoverPending = false;
        pendingSourceFailoverPositionMs = 0L;
        pendingSourceFailureMessage = null;
        retryingSourceFailureMessage = null;
        lastSourceFailed = true;
        showStatus("No playable links available. Choose another source or press Back to exit.");
        setControlsVisible(true);
        notifyObserverError();
    }

    private void onCurrentItemEnded() {
        onCurrentItemEnded(false);
    }

    private void onCurrentItemEnded(boolean selectedNextPrompt) {
        if (itemEndHandled) return;
        itemEndHandled = true;
        if (selectedNextPrompt && player != null && player.getDuration() > 0L) {
            saveProgressAt(player.getDuration());
        } else {
            saveProgress();
        }
        if (!request.queue.isEmpty()) {
            notifyObserverStopped(StopReason.COMPLETED);
            releaseTorrentPlaybackSession();
            PlaybackRequest.QueueItem next = request.queue.get(0);
            dismissSourcesDialog();
            request = request.startNext(next);
            releaseUnreferencedSubtitleDocumentGrants();
            attemptedSourceIds.clear();
            failedSourceIds.clear();
            sourceFailoverPending = false;
            playerErrorHandledForCurrentLoad = false;
            pendingSourceFailoverPositionMs = 0L;
            // Track IDs can be provider-local positions (for example, audio-track-0),
            // so selection must not follow a queue transition even when the next item
            // happens to expose the same ID.
            selectedExternalAudioTrackId = "";
            externalAudioTrackGroup = null;
            pendingExternalAudioTrackSelection = false;
            pendingNativeAudioTrackSelection = false;
            pendingNativeAudioGroupId = "";
            pendingNativeAudioTrackIndex = -1;
            nativeAudioTrackSelected = false;
            resetAudioSelectionOverrides();
            torrentSessionReleased = false;
            currentSource = request.getDefaultSource();
            activePromptId = "";
            selectedSubtitleId = "";
            pendingSubtitleId = "";
            dialogSubtitleId = "";
            subtitlesEnabled = false;
            if (player != null) {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            }
            updateCaptionsButton();
            observerReadyForCurrentItem = false;
            startedForCurrentItem = false;
            setControlsVisible(true);
            loadSource(currentSource, 0L, true);
            return;
        }
        finishPlayback(StopReason.COMPLETED);
    }

    private void openSourcesDialog() {
        openSourcesDialog(false);
    }

    private void openSourcesDialog(boolean focusSubtitles) {
        if (request == null) return;
        dismissSourcesDialog();
        final Dialog dialog = new Dialog(this);
        sourcesDialog = dialog;
        dialogSource = currentSource;
        dialogSubtitleId = subtitlesEnabled ? selectedSubtitleId : "";
        int activeSourceIndex = request.sources.indexOf(currentSource);
        providerSourcePage = activeSourceIndex < 0 ? 0
                : activeSourceIndex / PROVIDER_SOURCE_PAGE_SIZE;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, 0);
        panel.setBackgroundColor(0xFF111518);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout headingBlock = new LinearLayout(this);
        headingBlock.setOrientation(LinearLayout.VERTICAL);
        headingBlock.setGravity(Gravity.CENTER_VERTICAL);
        headingBlock.addView(text("PLAYBACK OPTIONS", 10, TEAL, true));
        headingBlock.addView(text("Sources & subtitles", television ? 20 : 17, TEXT, true));
        headingBlock.addView(text("Subtitle availability can vary by selected link.", 11, MUTED, false));
        header.addView(headingBlock, new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(button("×", v -> dialog.dismiss()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(header);
        View headerDivider = new View(this);
        headerDivider.setBackgroundColor(0x44293037);
        panel.addView(headerDivider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        panes.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout sourcePane = new LinearLayout(this);
        sourcePane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout subtitlePane = new LinearLayout(this);
        subtitlePane.setOrientation(LinearLayout.VERTICAL);
        sourcePane.setPadding(dp(8), dp(4), dp(10), dp(4));
        subtitlePane.setPadding(dp(10), dp(4), dp(8), dp(4));
        providerSourceHeading = text("VIDEO LINKS · " + request.sources.size() + " AVAILABLE",
                13, TEAL, true);
        sourcePane.addView(providerSourceHeading);
        providerSourcePager = new LinearLayout(this);
        providerSourcePager.setGravity(Gravity.CENTER_VERTICAL);
        sourcePane.addView(providerSourcePager, new LinearLayout.LayoutParams(-1, dp(36)));
        subtitlePane.addView(text("SUBTITLES · THIS LINK", 13, TEAL, true));
        ScrollView sourceScroll = new ScrollView(this);
        LinearLayout sourceRows = new LinearLayout(this);
        sourceRows.setOrientation(LinearLayout.VERTICAL);
        providerSourceRows = sourceRows;
        sourceScroll.addView(sourceRows);
        ScrollView subtitleScroll = new ScrollView(this);
        LinearLayout subtitleRows = new LinearLayout(this);
        subtitleRows.setOrientation(LinearLayout.VERTICAL);
        providerSubtitleRows = subtitleRows;
        subtitleScroll.addView(subtitleRows);
        sourcePane.addView(sourceScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        subtitlePane.addView(subtitleScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams paneParams = new LinearLayout.LayoutParams(0, -1, 1f);
        panes.addView(sourcePane, paneParams);
        View divider = new View(this);
        divider.setBackgroundColor(0x443B8D80);
        panes.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
        panes.addView(subtitlePane, new LinearLayout.LayoutParams(0, -1, 1f));
        panel.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView selectionCaption = text(sourceCaption(currentSource) + subtitleCaption(dialogSubtitleId),
                12, MUTED, false);
        providerSelectionCaption = selectionCaption;
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(14), dp(7), dp(14), dp(7));
        View footerDivider = new View(this);
        footerDivider.setBackgroundColor(0x44293037);
        panel.addView(footerDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        footer.addView(selectionCaption, new LinearLayout.LayoutParams(0, -2, 1f));
        Button apply = button("Apply & play", v -> {
            PlaybackRequest.Source chosenSource = dialogSource;
            String chosenSubtitle = dialogSubtitleId;
            dialog.dismiss();
            boolean subtitleNeedsSourceReload = chosenSource == currentSource
                    && chosenSubtitle.startsWith("external:")
                    && !hasLoadedSubtitleTrack(chosenSubtitle);
            if (chosenSource != currentSource || player.getPlayerError() != null
                    || player.getCurrentMediaItem() == null || lastSourceFailed
                    || subtitleNeedsSourceReload) {
                pendingSubtitleId = chosenSubtitle;
                long resumePositionMs = sourceFailoverPending
                        ? pendingSourceFailoverPositionMs : safeCurrentPositionForSourceChange();
                prepareManualSourceSelection(chosenSource);
                loadSource(chosenSource, resumePositionMs, true);
            } else {
                applySubtitleChoice(chosenSubtitle);
                player.play();
                setControlsVisible(false);
            }
            infoView.setText(sourceCaption(chosenSource)
                    + subtitleCaption(subtitlesEnabled ? selectedSubtitleId : ""));
            if (bottomSourceView != null) bottomSourceView.setText(sourceCaption(chosenSource));
        });
        footer.addView(apply, marginLayout(6, 0));
        panel.addView(footer);

        fillSubtitleRows(subtitleRows, dialogSource, selectionCaption);
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> {
            if (sourcesDialog == dialog) {
                sourcesDialog = null;
                providerSourceRows = null;
                providerSourcePager = null;
                providerSubtitleRows = null;
                providerSelectionCaption = null;
                providerSourceHeading = null;
            }
            scheduleControlsHide();
        });
        dialog.show();
        updateProviderDialogRows();
        if (focusSubtitles) focusSubtitleChoices(subtitleRows);
        else if (sourceRows.getChildCount() > 0) sourceRows.getChildAt(0).requestFocus();
        Window window = dialog.getWindow();
        if (window != null) {
            applyImmersiveSystemBars(window);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.72f;
            window.setAttributes(lp);
            window.setLayout(-1, -1);
        }
        setControlsVisible(true);
    }

    private void fillSourceRows(LinearLayout rows, PlaybackRequest.Source selected, PlaybackRequest.Source active) {
        PageWindow.Window sourceWindow = PageWindow.of(request.sources.size(), providerSourcePage,
                PROVIDER_SOURCE_PAGE_SIZE);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View view = rows.getChildAt(i);
            if (!(view instanceof Button)) continue;
            PlaybackRequest.Source source = request.sources.get(sourceWindow.start + i);
            ((Button) view).setText((source == selected ? "●  " : "○  ") + safeLabel(source.label)
                    + (source == active ? "   · Playing" : "   · Ready"));
            view.setBackground(roundRect(source == selected ? 0x55242E33 : SURFACE, dp(5)));
        }
    }

    static int sourcePageForFocusedSource(List<PlaybackRequest.Source> sources,
            int requestedPage, String focusedSourceId) {
        if (sources == null || sources.isEmpty()) return 0;
        if (focusedSourceId != null && !focusedSourceId.isEmpty()) {
            for (int index = 0; index < sources.size(); index++) {
                PlaybackRequest.Source source = sources.get(index);
                if (source != null && focusedSourceId.equals(source.id)) {
                    return index / PROVIDER_SOURCE_PAGE_SIZE;
                }
            }
        }
        return PageWindow.of(sources.size(), requestedPage, PROVIDER_SOURCE_PAGE_SIZE).page;
    }

    static String providerSourceRowFocusTag(String sourceId) {
        return PROVIDER_SOURCE_ROW_FOCUS_PREFIX + (sourceId == null ? "" : sourceId);
    }

    private static String sourceIdFromProviderSourceRowFocusTag(String tag) {
        return tag != null && tag.startsWith(PROVIDER_SOURCE_ROW_FOCUS_PREFIX)
                ? tag.substring(PROVIDER_SOURCE_ROW_FOCUS_PREFIX.length()) : null;
    }

    private void fillSubtitleRows(LinearLayout rows, PlaybackRequest.Source source, TextView caption) {
        rows.removeAllViews();
        Button searchOnline = button("⌕  Search online", v -> openOnlineSubtitleSearch(source));
        searchOnline.setTag("subtitle-action");
        searchOnline.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        rows.addView(searchOnline, new LinearLayout.LayoutParams(-1, dp(48)));
        Button loadLocal = button("▣  Load subtitle file", v -> openSubtitleDocumentPicker());
        loadLocal.setTag("subtitle-action");
        loadLocal.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        rows.addView(loadLocal, new LinearLayout.LayoutParams(-1, dp(48)));
        Button off = button((dialogSubtitleId.isEmpty() ? "●  " : "○  ") + "Off", v -> {
            dialogSubtitleId = "";
            fillSubtitleRows(rows, source, caption);
            caption.setText(sourceCaption(dialogSource) + subtitleCaption(dialogSubtitleId));
        });
        off.setTag("subtitle-off");
        off.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        rows.addView(off, new LinearLayout.LayoutParams(-1, dp(48)));

        boolean hasOptions = false;
        if (!source.subtitles.isEmpty()) {
            rows.addView(text("REPORTED DURING LINK LOADING", 10, TEAL, true),
                    marginParams(-1, -2, 2, dp(8), 0, dp(2)));
        }
        for (PlaybackRequest.Subtitle subtitle : source.subtitles) {
            hasOptions = true;
            String id = "external:" + subtitle.id;
            String detail = SubtitleFormat.UNKNOWN_MIME_TYPE.equals(subtitle.mimeType)
                    ? "Pending content detection"
                    : "Format hint · " + subtitle.mimeType;
            if (!subtitle.language.isEmpty()) detail += " · " + subtitle.language;
            Button option = button((id.equals(dialogSubtitleId) ? "●  " : "○  ") + safeLabel(subtitle.label)
                    + "   · " + detail, v -> {
                dialogSubtitleId = id;
                fillSubtitleRows(rows, source, caption);
                caption.setText(sourceCaption(dialogSource) + subtitleCaption(dialogSubtitleId));
            });
            option.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            rows.addView(option, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        if (source == currentSource && player != null) {
            List<TrackChoice> embedded = getTrackChoices(C.TRACK_TYPE_TEXT);
            boolean hasEmbedded = false;
            for (TrackChoice track : embedded) {
                if (!track.choiceId.startsWith("external:")
                        || !hasSubtitleId(source, track.choiceId.substring("external:".length()))) {
                    hasEmbedded = true;
                    break;
                }
            }
            if (hasEmbedded) rows.addView(text("EMBEDDED IN SELECTED VIDEO", 10, TEAL, true),
                    marginParams(-1, -2, 2, dp(8), 0, dp(2)));
            for (TrackChoice track : embedded) {
                String id = track.choiceId;
                if (id.startsWith("external:") && hasSubtitleId(source, id.substring("external:".length()))) continue;
                hasOptions = true;
                Button option = button((id.equals(dialogSubtitleId) ? "●  " : "○  ") + track.name
                        + (track.detail.isEmpty() ? "" : "   · " + track.detail), v -> {
                    dialogSubtitleId = id;
                    fillSubtitleRows(rows, source, caption);
                    caption.setText(sourceCaption(dialogSource) + subtitleCaption(dialogSubtitleId));
                });
                option.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                rows.addView(option, new LinearLayout.LayoutParams(-1, dp(48)));
            }
        }
        if (source.unsupportedSubtitleCount > 0) {
            rows.addView(text("Skipped " + source.unsupportedSubtitleCount
                            + " provider subtitle candidate(s): invalid or unsafe URL/headers, "
                            + "or beyond the 30-track limit.", 11, MUTED, false),
                    marginParams(-1, -2, 2, dp(8), 0, dp(4)));
        }
        if (!hasOptions) {
            rows.addView(text("No other subtitles are available for this link.", 12, MUTED, false),
                    marginParams(-1, -2, 0, dp(8), 0, 0));
        }
    }

    private void openSubtitleDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_OPEN_SUBTITLE_DOCUMENT);
        } catch (RuntimeException unavailablePicker) {
            showStatus("No document picker is available.");
            main.postDelayed(this::hideStatus, 2200L);
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_SUBTITLE_DOCUMENT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri document = data.getData();
        PlaybackRequest.Source source = dialogSource == null ? currentSource : dialogSource;
        if (source == null || request == null || !request.sources.contains(source)) {
            showStatus("Choose a subtitle for an active playback source.");
            main.postDelayed(this::hideStatus, 2200L);
            return;
        }
        String name = documentDisplayName(document);
        String mimeType = subtitleMimeType(document.toString(), name,
                getContentResolver().getType(document));
        if (!SubtitleDocumentGrantPolicy.isAcceptedDocument(true,
                document.toString(), mimeType)) {
            showStatus("Subtitle format unavailable. Choose VTT, SRT, TTML/XML, SSA, or ASS.");
            main.postDelayed(this::hideStatus, 2600L);
            return;
        }
        if (source.subtitles.size() >= 30) {
            showStatus("This link already has the maximum number of subtitles.");
            main.postDelayed(this::hideStatus, 2200L);
            return;
        }
        boolean readGrantPersisted = SubtitleDocumentGrantManager.persistAcceptedReadGrant(
                this, document, data.getFlags());
        if (!SubtitleDocumentGrantPolicy.canAddToPlayback(true, document.toString(), mimeType,
                readGrantPersisted)) {
            showStatus("File access could not be saved. Choose a document that allows ongoing read access.");
            main.postDelayed(this::hideStatus, 3000L);
            return;
        }
        String id = "local-subtitle-" + UUID.randomUUID();
        try {
            int previousCount = source.subtitles.size();
            source.addSubtitles(Collections.singletonList(new PlaybackRequest.Subtitle(
                    id, safeLabel(name), "", document.toString(), mimeType, 0)));
            if (source.subtitles.size() == previousCount) {
                releaseUnreferencedSubtitleDocumentGrants();
                showStatus("This link already has the maximum number of subtitles.");
                main.postDelayed(this::hideStatus, 2200L);
                return;
            }
            dialogSubtitleId = "external:" + id;
            refreshSubtitleRows(source);
            showStatus("Subtitle added. Select Apply & play to use it.");
            main.postDelayed(this::hideStatus, 2200L);
        } catch (RuntimeException invalidDocument) {
            releaseUnreferencedSubtitleDocumentGrants();
            showStatus("This subtitle file could not be added.");
            main.postDelayed(this::hideStatus, 2200L);
        }
    }

    private String documentDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0) {
                    String name = cursor.getString(nameColumn);
                    if (!TextUtils.isEmpty(name)) return name;
                }
            }
        } catch (RuntimeException ignored) { }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? "Subtitle file" : fallback;
    }

    private String subtitleMimeType(String uriValue, String displayName, String contentType) {
        return SubtitleFormat.mimeType(uriValue, contentType, displayName);
    }

    private static String supportedSubtitleContentType(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        String supported = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry == null || entry.getKey() == null
                    || !"Content-Type".equalsIgnoreCase(entry.getKey())) continue;
            String candidate = SubtitleFormat.allowedMime(entry.getValue());
            if (candidate == null || supported != null && !supported.equals(candidate)) return null;
            supported = candidate;
        }
        return supported;
    }

    private void openOnlineSubtitleSearch(PlaybackRequest.Source source) {
        if (request == null || source == null
                || !AccountManager.Companion.isActiveProfile(request.profileId)) {
            showStatus("Subtitle search requires the active Orbit profile.");
            main.postDelayed(this::hideStatus, 2200L);
            return;
        }
        EditText queryInput = new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        queryInput.setHint("Movie or series title");
        queryInput.setText(request.media.title);
        queryInput.setSelection(queryInput.length());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Search online subtitles")
                .setView(queryInput)
                .setPositiveButton("Search", (dialog, which) -> runSubtitleSearch(
                        source, queryInput.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runSubtitleSearch(PlaybackRequest.Source source, String queryText) {
        if (request == null || source == null || !request.sources.contains(source)
                || !AccountManager.Companion.isActiveProfile(request.profileId)) return;
        String query = TextUtils.isEmpty(queryText) ? request.media.title : queryText;
        String preferredLanguage = SubtitlePreferences.languageChoice(this, request.profileId);
        if (SubtitleDefaults.AUTO.equals(preferredLanguage) || SubtitleDefaults.NONE.equals(preferredLanguage)) {
            preferredLanguage = Locale.getDefault().toLanguageTag();
        }
        Integer season = request.episode.season > 0 ? request.episode.season : null;
        Integer episode = request.episode.number > 0 ? request.episode.number : null;
        Integer year = null;
        try { year = Integer.valueOf(request.media.year); } catch (RuntimeException ignored) { }
        AbstractSubtitleEntities.SubtitleSearch search = new AbstractSubtitleEntities.SubtitleSearch(
                query, preferredLanguage, null, null, null, null, episode, season, year);
        showStatus("Searching connected subtitle providers…");
        com.auras.orbit.player.SubtitleSearchGateway.search(request.profileId, search,
                new SubtitleSearchGateway.Callback() {
                    @Override public void onSearchComplete(
                            List<SubtitleSearchGateway.SearchResult> results, int failedProviders,
                            int authenticationRequiredProviders,
                            int authenticationFailedProviders) {
                        main.post(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                showOnlineSubtitleResults(source, results, failedProviders,
                                        authenticationRequiredProviders, authenticationFailedProviders);
                            }
                        });
                    }
                    @Override public void onSubtitleLoaded(String providerId,
                            AbstractSubtitleEntities.SubtitleEntity entity,
                            List<SubtitleResource.SingleSubtitleResource> resources, boolean failed,
                            boolean authenticationFailed) {
                        // Search results are loaded only after the user selects one; this callback
                        // is intentionally handled by loadOnlineSubtitleResult below.
                    }
                });
    }

    private void showOnlineSubtitleResults(PlaybackRequest.Source source,
            List<SubtitleSearchGateway.SearchResult> results, int failedProviders,
            int authenticationRequiredProviders, int authenticationFailedProviders) {
        hideStatus();
        if (request == null || source == null || !request.sources.contains(source)
                || !AccountManager.Companion.isActiveProfile(request.profileId)) return;
        if (results == null || results.isEmpty()) {
            String message = authenticationRequiredProviders > 0
                    ? "Sign in to the subtitle provider(s) in Settings → Connections, then search again."
                    : authenticationFailedProviders > 0
                    ? "Saved subtitle credentials were rejected. Sign in again in Settings → Connections."
                    : failedProviders > 0
                    ? "Subtitle providers could not complete the search. Check Connections and try again."
                    : "No online subtitle matches were found.";
            showStatus(message);
            main.postDelayed(this::hideStatus, 3000L);
            return;
        }
        String[] labels = new String[results.size()];
        for (int index = 0; index < results.size(); index++) {
            SubtitleSearchGateway.SearchResult result = results.get(index);
            String language = safeSubtitleLanguage(result.getEntity().getLang());
            labels[index] = safeResultLabel(result.getEntity().getName()) + "  ·  "
                    + (language.isEmpty() ? "Language not specified" : language) + "  ·  "
                    + safeResultLabel(result.getProviderName());
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Subtitle matches" + (failedProviders > 0
                        ? " · " + failedProviders + " provider(s) unavailable" : "")
                        + (authenticationRequiredProviders > 0
                        ? " · sign-in required for " + authenticationRequiredProviders + " provider(s)" : "")
                        + (authenticationFailedProviders > 0
                        ? " · credentials rejected for " + authenticationFailedProviders + " provider(s)" : ""))
                .setItems(labels, (dialog, which) -> loadOnlineSubtitleResult(source, results.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void loadOnlineSubtitleResult(PlaybackRequest.Source source,
            SubtitleSearchGateway.SearchResult result) {
        if (request == null || source == null || result == null
                || !AccountManager.Companion.isActiveProfile(request.profileId)) return;
        showStatus("Loading subtitle files…");
        SubtitleSearchGateway.load(request.profileId, result.getProviderId(), result.getEntity(),
                new SubtitleSearchGateway.Callback() {
                    @Override public void onSearchComplete(List<SubtitleSearchGateway.SearchResult> ignored,
                            int failedProviders, int authenticationRequiredProviders,
                            int authenticationFailedProviders) { }
                    @Override public void onSubtitleLoaded(String providerId,
                            AbstractSubtitleEntities.SubtitleEntity entity,
                            List<SubtitleResource.SingleSubtitleResource> resources, boolean failed,
                            boolean authenticationFailed) {
                        main.post(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                showOnlineSubtitleResources(source, entity, resources, failed,
                                        authenticationFailed);
                            }
                        });
                    }
                });
    }

    private void showOnlineSubtitleResources(PlaybackRequest.Source source,
            AbstractSubtitleEntities.SubtitleEntity entity,
            List<SubtitleResource.SingleSubtitleResource> resources, boolean failed,
            boolean authenticationFailed) {
        hideStatus();
        if (request == null || source == null || !request.sources.contains(source)
                || !AccountManager.Companion.isActiveProfile(request.profileId)) return;
        List<SubtitleResource.SingleSubtitleResource> offered = resources == null
                ? Collections.emptyList() : resources;
        ArrayList<PlaybackRequest.Subtitle> additions = new ArrayList<>();
        int unsupported = 0;
        int unsafe = 0;
        int capped = 0;
        int slots = Math.max(0, 30 - source.subtitles.size());
        int limit = Math.min(offered.size(), MAX_ONLINE_SUBTITLE_RESOURCES);
        for (int index = 0; index < limit; index++) {
            SubtitleResource.SingleSubtitleResource resource = offered.get(index);
            if (!isSafeSubtitleResource(resource)) { unsafe++; continue; }
            String effectiveName = TextUtils.isEmpty(resource.getName())
                    ? entity.getName() : resource.getName();
            String mimeType = subtitleMimeType(resource.getUrl(), effectiveName,
                    supportedSubtitleContentType(resource.getHeaders()));
            if (mimeType == null) { unsupported++; continue; }
            if (additions.size() >= slots) { capped++; continue; }
            String language = safeSubtitleLanguage(entity.getLang());
            String id = "online-subtitle-" + UUID.randomUUID();
            try {
                additions.add(new PlaybackRequest.Subtitle(
                        id, safeResultLabel(effectiveName), language, resource.getUrl(),
                        mimeType, 0, resource.getHeaders()));
            } catch (RuntimeException invalidResource) {
                unsafe++;
            }
        }
        if (offered.size() > limit) capped += offered.size() - limit;
        if (additions.isEmpty()) {
            String message = authenticationFailed
                    ? "Saved subtitle credentials were rejected. Sign in again in Settings → Connections."
                    : failed ? "This provider could not load the selected subtitle."
                    : unsafe > 0 ? "Unsafe subtitle links were ignored."
                    : unsupported > 0 ? "Subtitle format unavailable for " + unsupported
                            + " file(s). Supported formats are VTT, SRT, TTML/XML, SSA, and ASS."
                    : capped > 0 ? "No subtitle tracks added: the track limit is reached."
                    : "No downloadable subtitle files were returned.";
            if (unsupported > 0 || capped > 0) {
                message += " Skipped " + unsupported + " unsupported, " + capped + " over the limit.";
            }
            showStatus(message);
            main.postDelayed(this::hideStatus, 3200L);
            return;
        }
        String selectedId = additions.get(0).id;
        try {
            int previousCount = source.subtitles.size();
            source.addSubtitles(additions);
            int added = source.subtitles.size() - previousCount;
            if (added <= 0) {
                showStatus("This link already has the maximum number of subtitles.");
                main.postDelayed(this::hideStatus, 2200L);
                return;
            }
            dialogSubtitleId = "external:" + selectedId;
            refreshSubtitleRows(source);
            showStatus("Added " + added + " subtitle track(s). Select Apply & play to use the first."
                    + (unsupported + unsafe + capped > 0
                    ? " Skipped " + unsupported + " unsupported, " + unsafe + " unsafe, "
                            + capped + " over the limit." : ""));
            main.postDelayed(this::hideStatus, 3400L);
        } catch (RuntimeException invalidResource) {
            showStatus("This subtitle result could not be added safely.");
            main.postDelayed(this::hideStatus, 2400L);
        }
    }

    private boolean isSafeSubtitleResource(SubtitleResource.SingleSubtitleResource resource) {
        if (resource == null || TextUtils.isEmpty(resource.getUrl())) return false;
        Uri uri = Uri.parse(resource.getUrl());
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return Origin.parse(resource.getUrl()) != null
                    && !isUnsafeCleartextMediaUri(resource.getUrl(), resource.getHeaders());
        }
        if (!"file".equalsIgnoreCase(scheme) || resource.getOrigin() != SubtitleOrigin.DOWNLOADED_FILE) {
            return false;
        }
        try {
            String cache = getCacheDir().getCanonicalPath() + java.io.File.separator;
            String path = new java.io.File(uri.getPath()).getCanonicalPath();
            return path.startsWith(cache);
        } catch (IOException | RuntimeException invalidFile) {
            return false;
        }
    }

    private void refreshSubtitleRows(PlaybackRequest.Source source) {
        if (providerSubtitleRows != null && source == dialogSource) {
            fillSubtitleRows(providerSubtitleRows, source, providerSelectionCaption);
            if (providerSelectionCaption != null) {
                providerSelectionCaption.setText(sourceCaption(source) + subtitleCaption(dialogSubtitleId));
            }
        }
    }

    private static String safeResultLabel(String value) {
        String label = safeLabel(value);
        return label.length() > 120 ? label.substring(0, 117) + "…" : label;
    }

    private static String safeSubtitleLanguage(String value) {
        if (TextUtils.isEmpty(value) || value.length() > 32) return "";
        String candidate = value.trim().replace('_', '-');
        if (candidate.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
            Locale locale = Locale.forLanguageTag(candidate);
            return locale.getLanguage().isEmpty() ? "" : locale.toLanguageTag();
        }
        for (Locale locale : Locale.getAvailableLocales()) {
            if (!locale.getLanguage().isEmpty()
                    && (locale.getDisplayLanguage(Locale.ENGLISH).equalsIgnoreCase(candidate)
                    || locale.getDisplayLanguage(locale).equalsIgnoreCase(candidate))) {
                return locale.getLanguage();
            }
        }
        return "";
    }

    private void focusSubtitleChoices(LinearLayout rows) {
        View offChoice = null;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (!(child instanceof Button) || !child.isEnabled()) continue;
            if ("subtitle-off".equals(child.getTag())) {
                offChoice = child;
                continue;
            }
            if (!"subtitle-action".equals(child.getTag())) {
                child.requestFocus();
                return;
            }
        }
        if (offChoice != null) offChoice.requestFocus();
    }

    private boolean hasSubtitleId(PlaybackRequest.Source source, String id) {
        for (PlaybackRequest.Subtitle subtitle : source.subtitles) if (subtitle.id.equals(id)) return true;
        return false;
    }

    private boolean hasLoadedSubtitleTrack(String choiceId) {
        if (player == null) return false;
        for (TrackChoice choice : getTrackChoices(C.TRACK_TYPE_TEXT)) {
            if (choice.choiceId.equals(choiceId)) return true;
        }
        return false;
    }

    private PlaybackRequest.AudioTrack findAudioTrack(PlaybackRequest.Source source, String id) {
        if (source == null || id == null || id.isEmpty()) return null;
        for (PlaybackRequest.AudioTrack track : source.audioTracks) {
            if (track.id.equals(id)) return track;
        }
        return null;
    }

    private boolean isUnsafeCleartextMediaUri(String uri, Map<String, String> headers) {
        Origin origin = Origin.parse(uri);
        return origin != null && "http".equals(origin.scheme)
                && ((headers != null && !headers.isEmpty()) || hasCleartextQuery(uri));
    }

    private void resetAudioSelectionOverrides() {
        if (player == null) return;
        nativeAudioTrackSelected = false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build());
    }

    /** Applies a selected external file as the sole audio track while retaining the video source. */
    private void applyPendingAudioTrackSelection(boolean ready) {
        if (player == null) return;
        if (pendingExternalAudioTrackSelection) {
            Tracks.Group external = null;
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() == C.TRACK_TYPE_AUDIO) external = group;
            }
            int selectedTrack = -1;
            if (external != null) {
                for (int i = 0; i < external.length; i++) {
                    if (external.isTrackSupported(i)) { selectedTrack = i; break; }
                }
            }
            if (external != null && selectedTrack >= 0) {
                TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
                for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                    if (group.getType() == C.TRACK_TYPE_AUDIO
                            && group.getMediaTrackGroup() != external.getMediaTrackGroup()) {
                        builder.addOverride(new TrackSelectionOverride(
                                group.getMediaTrackGroup(), Collections.emptyList()));
                    }
                }
                builder.addOverride(new TrackSelectionOverride(
                        external.getMediaTrackGroup(), Collections.singletonList(selectedTrack)));
                player.setTrackSelectionParameters(builder.build());
                externalAudioTrackGroup = external;
                pendingExternalAudioTrackSelection = false;
            } else if (ready) {
                selectedExternalAudioTrackId = "";
                externalAudioTrackGroup = null;
                pendingExternalAudioTrackSelection = false;
                showStatus("External audio is unavailable; continuing with video audio.");
                loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                return;
            }
        }
        if (pendingNativeAudioTrackSelection && !pendingNativeAudioGroupId.isEmpty()) {
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_AUDIO
                        || !pendingNativeAudioGroupId.equals(group.getMediaTrackGroup().id)
                        || pendingNativeAudioTrackIndex < 0
                        || pendingNativeAudioTrackIndex >= group.length
                        || !group.isTrackSupported(pendingNativeAudioTrackIndex)) continue;
                TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .addOverride(new TrackSelectionOverride(group.getMediaTrackGroup(),
                                Collections.singletonList(pendingNativeAudioTrackIndex)));
                player.setTrackSelectionParameters(builder.build());
                pendingNativeAudioTrackSelection = false;
                pendingNativeAudioGroupId = "";
                pendingNativeAudioTrackIndex = -1;
                break;
            }
        }
    }

    private void openAudioTracksDialog() {
        main.removeCallbacks(hideControlsTask);
        List<TrackChoice> audio = getTrackChoices(C.TRACK_TYPE_AUDIO);
        ArrayList<PlaybackRequest.AudioTrack> externalAudio = new ArrayList<>();
        if (currentSource != null) {
            for (PlaybackRequest.AudioTrack track : currentSource.audioTracks) {
                if (isUnsafeCleartextMediaUri(track.uri, track.headers)) continue;
                externalAudio.add(track);
            }
        }
        ArrayList<String> options = new ArrayList<>();
        ArrayList<TrackChoice> choices = new ArrayList<>();
        options.add("Automatic");
        for (TrackChoice choice : audio) {
            choices.add(choice);
            options.add(choice.name + (choice.detail.isEmpty() ? "" : " · " + choice.detail));
        }
        int selected = 0;
        if (!selectedExternalAudioTrackId.isEmpty()) {
            for (int i = 0; i < externalAudio.size(); i++) {
                if (selectedExternalAudioTrackId.equals(externalAudio.get(i).id)) {
                    selected = choices.size() + i + 1;
                    break;
                }
            }
        } else {
            for (int i = 0; i < choices.size(); i++) if (choices.get(i).selected) selected = i + 1;
        }
        for (int i = 0; i < externalAudio.size(); i++) {
            options.add("External audio track " + (i + 1));
        }
        if (choices.isEmpty() && externalAudio.isEmpty()) {
            options.add("No alternate audio tracks reported by this source");
        }
        final int[] pending = {selected};
        android.app.AlertDialog trackDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Tracks · Audio")
                .setSingleChoiceItems(options.toArray(new String[0]), selected, (dialog, which) -> pending[0] = which)
                .setPositiveButton("Apply", (dialog, which) -> {
                    if (pending[0] == 0) {
                        if (!selectedExternalAudioTrackId.isEmpty()) {
                            selectedExternalAudioTrackId = "";
                            externalAudioTrackGroup = null;
                            pendingExternalAudioTrackSelection = false;
                            pendingNativeAudioTrackSelection = false;
                            pendingNativeAudioGroupId = "";
                            pendingNativeAudioTrackIndex = -1;
                            nativeAudioTrackSelected = false;
                            resetAudioSelectionOverrides();
                            loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                        } else {
                            nativeAudioTrackSelected = false;
                            TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
                            player.setTrackSelectionParameters(builder.build());
                        }
                    } else if (pending[0] <= choices.size()) {
                        TrackChoice choice = choices.get(pending[0] - 1);
                        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
                        if (!selectedExternalAudioTrackId.isEmpty()) {
                            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                                if (group.getType() == C.TRACK_TYPE_AUDIO
                                        && group.getMediaTrackGroup() != choice.group.getMediaTrackGroup()) {
                                    builder.addOverride(new TrackSelectionOverride(
                                            group.getMediaTrackGroup(), Collections.emptyList()));
                                }
                            }
                            pendingNativeAudioGroupId = choice.group.getMediaTrackGroup().id;
                            pendingNativeAudioTrackIndex = choice.trackIndex;
                            pendingNativeAudioTrackSelection = !pendingNativeAudioGroupId.isEmpty();
                            nativeAudioTrackSelected = true;
                            selectedExternalAudioTrackId = "";
                            externalAudioTrackGroup = null;
                            pendingExternalAudioTrackSelection = false;
                            builder.addOverride(new TrackSelectionOverride(
                                    choice.group.getMediaTrackGroup(), Collections.singletonList(choice.trackIndex)));
                            player.setTrackSelectionParameters(builder.build());
                            loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                        } else {
                            nativeAudioTrackSelected = true;
                            builder.addOverride(new TrackSelectionOverride(choice.group.getMediaTrackGroup(),
                                    Collections.singletonList(choice.trackIndex)));
                            player.setTrackSelectionParameters(builder.build());
                        }
                    } else if (pending[0] <= choices.size() + externalAudio.size()) {
                        PlaybackRequest.AudioTrack track = externalAudio.get(pending[0] - choices.size() - 1);
                        if (!track.id.equals(selectedExternalAudioTrackId)) {
                            selectedExternalAudioTrackId = track.id;
                            externalAudioTrackGroup = null;
                            pendingExternalAudioTrackSelection = true;
                            pendingNativeAudioTrackSelection = false;
                            pendingNativeAudioGroupId = "";
                            pendingNativeAudioTrackIndex = -1;
                            nativeAudioTrackSelected = false;
                            loadSource(currentSource, Math.max(0L, player.getCurrentPosition()), true);
                        }
                    }
                    setControlsVisible(false);
                })
                .setNegativeButton("Close", (dialog, which) -> setControlsVisible(true))
                .create();
        audioTracksDialog = trackDialog;
        trackDialog.setOnDismissListener(d -> {
            if (audioTracksDialog == trackDialog) audioTracksDialog = null;
            scheduleControlsHide();
        });
        trackDialog.show();
    }

    private List<TrackChoice> getTrackChoices(int trackType) {
        ArrayList<TrackChoice> result = new ArrayList<>();
        if (player == null) return result;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != trackType) continue;
            if (trackType == C.TRACK_TYPE_AUDIO && externalAudioTrackGroup != null
                    && group.getMediaTrackGroup() == externalAudioTrackGroup.getMediaTrackGroup()) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                String name = !isEmpty(format.label) ? safeLabel(format.label)
                        : (!isEmpty(format.language) ? format.language : trackType == C.TRACK_TYPE_TEXT ? "Subtitle" : "Audio track");
                String detail = trackType == C.TRACK_TYPE_AUDIO
                        ? audioDetail(format) : (!isEmpty(format.language) ? format.language : "");
                String id = !isEmpty(format.id) ? format.id : name + ":" + i;
                String choiceId;
                if (trackType == C.TRACK_TYPE_TEXT) {
                    String externalId = findExternalSubtitleId(id, format.label);
                    choiceId = externalId == null ? "track:" + id : "external:" + externalId;
                } else {
                    choiceId = "audio:" + id;
                }
                result.add(new TrackChoice(choiceId, name, detail, group, i, group.isTrackSelected(i)));
            }
        }
        return result;
    }

    private void applySubtitleChoice(String choiceId) {
        manualSubtitleChoiceMade = true;
        automaticSubtitleSelectionApplied = true;
        if (choiceId == null || choiceId.isEmpty()) {
            if (!pendingSubtitleId.isEmpty()) selectedSubtitleId = pendingSubtitleId;
            pendingSubtitleId = "";
            subtitlesEnabled = false;
            TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            player.setTrackSelectionParameters(builder.build());
            updateCaptionsButton();
            updateSubtitleCaptionInfo();
            return;
        }
        selectedSubtitleId = choiceId;
        pendingSubtitleId = choiceId;
        subtitlesEnabled = false;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        updateCaptionsButton();
        updateSubtitleCaptionInfo();
        selectSubtitleAfterLoad(selectedSubtitleId);
    }

    private void applySubtitleDefaultsIfNeeded() {
        if (player == null || request == null || currentSource == null
                || player.getPlaybackState() != Player.STATE_READY
                || automaticSubtitleSelectionApplied || manualSubtitleChoiceMade
                || !pendingSubtitleId.isEmpty()) return;

        String languageChoice = SubtitlePreferences.languageChoice(this, request.profileId);
        if (SubtitleDefaults.NONE.equals(languageChoice)) {
            automaticSubtitleSelectionApplied = true;
            return;
        }
        List<TrackChoice> choices = getTrackChoices(C.TRACK_TYPE_TEXT);
        if (choices.isEmpty()) return;
        String preferredLanguage = SubtitleDefaults.AUTO.equals(languageChoice)
                ? Locale.getDefault().toLanguageTag() : languageChoice;
        ArrayList<String> languages = new ArrayList<>(choices.size());
        for (TrackChoice choice : choices) languages.add(choice.detail);
        int selectedIndex = SubtitleDefaults.findMatchingTrack(preferredLanguage, languages);
        automaticSubtitleSelectionApplied = true;
        if (selectedIndex >= 0) {
            selectSubtitleAfterLoad(choices.get(selectedIndex).choiceId);
        }
    }

    private boolean hasAvailableSubtitles(PlaybackRequest.Source source) {
        if (source == null) return false;
        if (!source.subtitles.isEmpty()) return true;
        return source == currentSource && !getTrackChoices(C.TRACK_TYPE_TEXT).isEmpty();
    }

    private void toggleSubtitles() {
        if (player == null || currentSource == null) return;
        if (subtitlesEnabled || !pendingSubtitleId.isEmpty()) {
            applySubtitleChoice("");
            return;
        }
        if (!selectedSubtitleId.isEmpty()) {
            selectSubtitleAfterLoad(selectedSubtitleId);
            updateCaptionsButton();
            return;
        }
        if (!hasAvailableSubtitles(currentSource)) {
            showStatus("No captions available.");
            main.postDelayed(this::hideStatus, 2200L);
            return;
        }
        showStatus("Choose a subtitle in Sources first.");
        main.postDelayed(this::hideStatus, 2200L);
    }

    private String findExternalSubtitleId(String formatId, String formatLabel) {
        if (currentSource == null) return null;
        for (PlaybackRequest.Subtitle subtitle : currentSource.subtitles) {
            if ((!isEmpty(formatId) && formatId.equals(subtitle.id))
                    || (!isEmpty(formatLabel) && formatLabel.equals(subtitle.label))) return subtitle.id;
        }
        return null;
    }

    private void selectSubtitleAfterLoad(String choiceId) {
        if (player == null || choiceId == null || choiceId.isEmpty()) return;
        if (player.getPlaybackState() != Player.STATE_READY) {
            selectedSubtitleId = choiceId;
            pendingSubtitleId = choiceId;
            subtitlesEnabled = false;
            updateCaptionsButton();
            updateSubtitleCaptionInfo();
            return;
        }
        String expected = choiceId.startsWith("external:") ? choiceId.substring("external:".length())
                : choiceId.startsWith("track:") ? choiceId.substring("track:".length()) : choiceId;
        TrackChoice selected = null;
        for (TrackChoice choice : getTrackChoices(C.TRACK_TYPE_TEXT)) {
            if (choice.choiceId.equals(choiceId) || choice.choiceId.endsWith(expected)) {
                selected = choice;
                break;
            }
        }
        pendingSubtitleId = "";
        if (selected == null) {
            selectedSubtitleId = "";
            subtitlesEnabled = false;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            updateCaptionsButton();
            updateSubtitleCaptionInfo();
            if (statusView != null && root != null) {
                showStatus("That subtitle is unavailable for this link.");
                main.postDelayed(this::hideStatus, 2200L);
            }
            return;
        }
        selectedSubtitleId = selected.choiceId;
        subtitlesEnabled = true;
        if (selectedSubtitleId.startsWith("external:")) {
            showUnrecognizedProviderSubtitleIfNeeded(
                    currentSource == null ? "" : currentSource.id,
                    selectedSubtitleId.substring("external:".length()));
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .addOverride(new TrackSelectionOverride(selected.group.getMediaTrackGroup(),
                        Collections.singletonList(selected.trackIndex))).build());
        updateCaptionsButton();
        updateSubtitleCaptionInfo();
    }

    private void activatePrompt() {
        if (request == null || player == null || activePromptId.isEmpty()) return;
        if (activePromptId.startsWith("skip:")) {
            String id = activePromptId.substring("skip:".length());
            for (PlaybackRequest.SkipSegment segment : request.skipSegments) if (segment.id.equals(id)) {
                player.seekTo(segment.endMs);
                saveProgress();
                sendObserverProgress();
                activePromptId = "";
                updateTimeAndPrompts();
                return;
            }
        } else if (activePromptId.equals("next") && !request.queue.isEmpty()) {
            onCurrentItemEnded(true);
        }
    }

    private void updateTimeAndPrompts() {
        if (player == null || request == null) return;
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        if (duration < 0L) duration = 0L;
        if (!seeking) seekBar.setProgress(duration > 0L
                ? (int) Math.min(10_000L, position * 10_000L / duration) : 0);
        currentTimeView.setText(formatTime(position));
        durationView.setText(duration > 0L ? formatTime(duration) : "--:--");

        PlaybackRequest.SkipSegment prompt = null;
        for (PlaybackRequest.SkipSegment segment : request.skipSegments) {
            if (position >= segment.startMs && position < segment.endMs) {
                prompt = segment;
                break;
            }
        }
        if (prompt != null) {
            activePromptId = "skip:" + prompt.id;
            promptButton.setText("↷   " + safeLabel(prompt.label));
            promptButton.setVisibility(View.VISIBLE);
        } else if (!request.queue.isEmpty() && duration > 0L && position >= duration - 60_000L) {
            activePromptId = "next";
            promptButton.setText("↷   Next · " + safeLabel(request.queue.get(0).episode.title.isEmpty()
                    ? request.queue.get(0).media.title : request.queue.get(0).episode.title));
            promptButton.setVisibility(View.VISIBLE);
        } else {
            activePromptId = "";
            promptButton.setVisibility(View.GONE);
        }
    }

    private void seekBy(long amountMs) {
        if (player == null) return;
        long duration = player.getDuration();
        long next = Math.max(0L, player.getCurrentPosition() + amountMs);
        if (duration > 0L) next = Math.min(next, duration);
        player.seekTo(next);
        saveProgress();
        sendObserverProgress();
        updateTimeAndPrompts();
        setControlsVisible(true);
    }

    private void replay() {
        if (player == null) return;
        player.seekTo(0L);
        player.play();
        activePromptId = "";
        saveProgress();
        sendObserverProgress();
        setControlsVisible(true);
    }

    private Button createSourcesButton() {
        Button sourceButton = button("≋  Sources · " + request.sources.size(), v -> openSourcesDialog());
        stylePlayerActionButton(sourceButton);
        sourcesButton = sourceButton;
        return sourceButton;
    }

    private Button createCaptionsButton() {
        captionsButton = button("", v -> toggleSubtitles());
        stylePlayerActionButton(captionsButton);
        updateCaptionsButton();
        return captionsButton;
    }

    private void updateCaptionsButton() {
        if (captionsButton == null) return;
        captionsButton.setText(subtitlesEnabled ? "CC On" : "CC Off");
        captionsButton.setContentDescription(subtitlesEnabled ? "Captions on" : "Captions off");
        captionsButton.setSelected(subtitlesEnabled);
        captionsButton.setTextColor(subtitlesEnabled ? TEAL : TEXT);
    }

    private Button createTracksButton() {
        Button tracksButton = button("♫  Tracks", v -> openAudioTracksDialog());
        stylePlayerActionButton(tracksButton);
        return tracksButton;
    }

    private void stylePlayerIconButton(Button button) {
        int size = television ? 32 : 54;
        button.setMinWidth(dp(size));
        button.setMinHeight(dp(size));
        if (!television) button.setTextSize(18);
        button.setPadding(dp(2), dp(2), dp(2), dp(2));
        button.setBackground(playerIconRipple());
        button.setOnFocusChangeListener((view, focused) -> {
            button.setTextColor(focused ? TEAL : TEXT);
        });
    }

    private FrameLayout playerNavigationButton(String description, View.OnClickListener action) {
        FrameLayout control = new FrameLayout(this);
        control.setContentDescription(description);
        control.setBackground(playerIconRipple());
        control.setClickable(true);
        control.setFocusable(true);
        control.setOnClickListener(action);
        return control;
    }

    private ImageView playerNavigationGlyph(Drawable glyph, int width, int height) {
        ImageView image = new ImageView(this);
        image.setImageDrawable(glyph);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setFocusable(false);
        image.setClickable(false);
        image.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        return image;
    }

    private RippleDrawable playerIconRipple() {
        return new RippleDrawable(ColorStateList.valueOf(0x80FFFFFF), null, oval(Color.WHITE));
    }

    private void stylePlayerActionButton(Button button) {
        button.setMinHeight(dp(television ? 33 : 54));
        button.setPadding(dp(10), dp(1), dp(10), dp(1));
    }

    private LinearLayout.LayoutParams playerIconLayoutParams(int horizontalMargin) {
        int size = dp(television ? 32 : 54);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(horizontalMargin);
        params.rightMargin = dp(horizontalMargin);
        return params;
    }

    private LinearLayout.LayoutParams playerActionLayoutParams(int horizontalMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(television ? 33 : 54));
        params.leftMargin = dp(horizontalMargin);
        params.rightMargin = dp(horizontalMargin);
        return params;
    }

    private LinearLayout.LayoutParams fixedTransportLayout(int diameter, int horizontalMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(diameter), dp(diameter));
        params.leftMargin = dp(horizontalMargin);
        params.rightMargin = dp(horizontalMargin);
        return params;
    }

    private void openPlaybackSettingsDialog() {
        if (player == null || playerView == null) return;
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        main.removeCallbacks(hideControlsTask);
        Dialog dialog = new Dialog(this);
        settingsDialog = dialog;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(20), dp(18));
        panel.setBackground(roundRect(SURFACE, dp(television ? 0 : 17)));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("Playback settings", television ? 21 : 18, TEXT, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        Button close = button("×", v -> dialog.dismiss());
        close.setContentDescription("Close playback settings");
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(header);
        TextView note = text("Adjust playback speed, subtitle timing, and screen fit.", 12, MUTED, false);
        panel.addView(note, marginParams(-1, -2, 0, dp(5), 0, dp(14)));

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout speedCopy = new LinearLayout(this);
        speedCopy.setOrientation(LinearLayout.VERTICAL);
        TextView speedLabel = text("Playback speed", 14, TEXT, true);
        speedCopy.addView(speedLabel);
        settingsSpeedHint = text(playbackSpeedHintLabel(), 10, MUTED, false);
        speedCopy.addView(settingsSpeedHint);
        speedRow.addView(speedCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        settingsSpeedButton = button(currentPlaybackSpeedLabel(), v -> showPlaybackSpeedChoices());
        applySettingsChoiceButton(settingsSpeedButton, "Playback speed",
                currentPlaybackSpeedLabel(), "player_settings_speed");
        speedRow.addView(settingsSpeedButton, new LinearLayout.LayoutParams(dp(112), dp(46)));
        panel.addView(speedRow, marginParams(-1, -2, 0, dp(4), 0, dp(8)));

        LinearLayout fitRow = new LinearLayout(this);
        fitRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout fitCopy = new LinearLayout(this);
        fitCopy.setOrientation(LinearLayout.VERTICAL);
        TextView fitLabel = text("Screen fit", 14, TEXT, true);
        fitCopy.addView(fitLabel);
        fitCopy.addView(text("Video framing", 10, MUTED, false));
        fitRow.addView(fitCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        settingsFitButton = button(screenFitLabel(), v -> showScreenFitChoices());
        applySettingsChoiceButton(settingsFitButton, "Screen fit",
                screenFitLabel(), "player_settings_fit");
        fitRow.addView(settingsFitButton, new LinearLayout.LayoutParams(dp(112), dp(46)));
        panel.addView(fitRow, marginParams(-1, -2, 0, dp(4), 0, 0));

        LinearLayout delayRow = new LinearLayout(this);
        delayRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout delayCopy = new LinearLayout(this);
        delayCopy.setOrientation(LinearLayout.VERTICAL);
        delayCopy.addView(text("Subtitle delay", 14, TEXT, true));
        settingsSubtitleDelayHint = text(subtitleDelayHintLabel(), 10, MUTED, false);
        delayCopy.addView(settingsSubtitleDelayHint);
        delayRow.addView(delayCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        Button decreaseDelay = button("−0.25s", v -> adjustSubtitleDelay(-250L));
        decreaseDelay.setContentDescription("Move subtitles earlier by 0.25 seconds");
        delayRow.addView(decreaseDelay, new LinearLayout.LayoutParams(dp(84), dp(46)));
        settingsSubtitleDelayButton = button("+0.25s", v -> adjustSubtitleDelay(250L));
        settingsSubtitleDelayButton.setContentDescription("Move subtitles later by 0.25 seconds");
        delayRow.addView(settingsSubtitleDelayButton, marginLayout(4, 0));
        Button resetDelay = button("Reset", v -> setSubtitleDelay(0L));
        resetDelay.setContentDescription("Reset subtitle delay");
        delayRow.addView(resetDelay, new LinearLayout.LayoutParams(dp(64), dp(46)));
        panel.addView(delayRow, marginParams(-1, -2, 0, dp(10), 0, 0));

        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> {
            if (settingsDialog == dialog) settingsDialog = null;
            settingsSpeedButton = null;
            settingsFitButton = null;
            settingsSubtitleDelayButton = null;
            settingsSpeedHint = null;
            settingsSubtitleDelayHint = null;
            scheduleControlsHide();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            applyImmersiveSystemBars(window);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.72f;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            if (television) {
                lp.width = Math.min(dp(410), Math.round(screenWidth * 0.42f));
                lp.height = WindowManager.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.RIGHT | Gravity.TOP;
                lp.x = 0;
                lp.y = 0;
            } else {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                lp.width = screenWidth - dp(28);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.x = 0;
                lp.y = dp(13);
            }
            window.setAttributes(lp);
            window.setLayout(lp.width, lp.height);
        }
        settingsSpeedButton.requestFocus();
    }

    private String subtitleDelayHintLabel() {
        if (subtitleDelayMs == 0L) return "At video timing";
        String sign = subtitleDelayMs > 0L ? "+" : "−";
        return sign + String.format(Locale.ROOT, "%.2f seconds", Math.abs(subtitleDelayMs) / 1000f);
    }

    private void adjustSubtitleDelay(long deltaMs) {
        setSubtitleDelay(Math.max(-10_000L, Math.min(10_000L, subtitleDelayMs + deltaMs)));
    }

    private void setSubtitleDelay(long delayMs) {
        long normalized = SubtitleTimelineShift.normalizeDelayMs(delayMs);
        if (normalized == subtitleDelayMs) return;
        boolean resumePlayback = player != null && player.isPlaying();
        long resumePositionMs = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
        String selected = subtitlesEnabled ? selectedSubtitleId : "";
        subtitleDelayMs = normalized;
        if (settingsSubtitleDelayHint != null) {
            settingsSubtitleDelayHint.setText(subtitleDelayHintLabel());
        }
        if (selectedSubtitleId.startsWith("external:") && currentSource != null
                && !hasSubtitleId(currentSource, selectedSubtitleId.substring("external:".length()))) {
            selected = "";
        }
        if (player != null && currentSource != null && player.getPlaybackState() == Player.STATE_READY) {
            if (!selected.isEmpty()) pendingSubtitleId = selected;
            loadSource(currentSource, resumePositionMs, resumePlayback);
            showStatus("Subtitle timing updated.");
            main.postDelayed(this::hideStatus, 1600L);
        }
    }

    private void showPlaybackSpeedChoices() {
        final String[] labels = {"1×", "1.25×", "1.5×", "0.75×"};
        final float[] values = {1f, 1.25f, 1.5f, 0.75f};
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - playbackSpeed) < 0.001f) selected = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Playback speed")
                .setSingleChoiceItems(labels, selected, (choiceDialog, which) -> {
                    playbackSpeed = values[which];
                    player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(playbackSpeed, 1f));
                    if (settingsSpeedButton != null) applySettingsChoiceButton(settingsSpeedButton,
                            "Playback speed", labels[which], "player_settings_speed");
                    if (settingsSpeedHint != null) settingsSpeedHint.setText(playbackSpeedHintLabel());
                    choiceDialog.dismiss();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showScreenFitChoices() {
        final String[] labels = {"Fit", "Fill", "Zoom"};
        final int[] modes = {AspectRatioFrameLayout.RESIZE_MODE_FIT,
                AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM};
        int selected = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == screenFitMode) selected = i;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Screen fit")
                .setSingleChoiceItems(labels, selected, (choiceDialog, which) -> {
                    screenFitMode = modes[which];
                    playerView.setResizeMode(screenFitMode);
                    if (settingsFitButton != null) applySettingsChoiceButton(settingsFitButton,
                            "Screen fit", labels[which], "player_settings_fit");
                    choiceDialog.dismiss();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void applySettingsChoiceButton(Button choice, String setting, String value, String tag) {
        choice.setText(value);
        choice.setTag(tag);
        choice.setContentDescription(setting + ", " + value + ". Change "
                + setting.toLowerCase(Locale.ROOT) + ".");
        Drawable chevron = getDrawable(R.drawable.orbit_chevron_down);
        if (chevron != null) {
            chevron = chevron.mutate();
            chevron.setTint(CHEVRON_COLOR);
            chevron.setBounds(0, 0, dp(12), dp(12));
            choice.setCompoundDrawablesRelative(null, null, chevron, null);
            choice.setCompoundDrawablePadding(dp(7));
        }
    }

    private String currentPlaybackSpeedLabel() {
        if (Math.abs(playbackSpeed - 0.75f) < 0.001f) return "0.75×";
        if (Math.abs(playbackSpeed - 1.25f) < 0.001f) return "1.25×";
        if (Math.abs(playbackSpeed - 1.5f) < 0.001f) return "1.5×";
        return "1×";
    }

    private String playbackSpeedHintLabel() {
        return Math.abs(playbackSpeed - 1f) < 0.001f
                ? "Normal speed" : currentPlaybackSpeedLabel() + " speed";
    }

    private String screenFitLabel() {
        if (screenFitMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) return "Fill";
        if (screenFitMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) return "Zoom";
        return "Fit";
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        setControlsVisible(true);
    }

    private void updatePlayPauseButton(boolean isPlaying) {
        if (playPauseButton == null) return;
        playPauseButton.setText(isPlaying ? "Ⅱ" : "▶");
        playPauseButton.setContentDescription(isPlaying ? "Pause playback" : "Resume playback");
        playPauseButton.setSelected(isPlaying);
    }

    private void setControlsVisible(boolean visible) {
        if (root == null || topBar == null) return;
        controlsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        controlsScrim.setVisibility(visibility);
        topBar.setVisibility(visibility);
        bottomBar.setVisibility(visibility);
        centerControls.setVisibility(visible && player != null && !player.isPlaying() ? View.VISIBLE : visibility);
        infoView.setVisibility(visible && !television ? View.VISIBLE : View.GONE);
        pauseMetaView.setVisibility(visible && player != null && !player.isPlaying() ? View.VISIBLE : View.GONE);
        if (visible) scheduleControlsHide(); else main.removeCallbacks(hideControlsTask);
    }

    private void scheduleControlsHide() {
        main.removeCallbacks(hideControlsTask);
        if (player != null && player.isPlaying() && !hasOpenDialog()) {
            main.postDelayed(hideControlsTask, CONTROL_TIMEOUT_MS);
        }
    }

    private boolean hasOpenDialog() {
        return (sourcesDialog != null && sourcesDialog.isShowing())
                || (audioTracksDialog != null && audioTracksDialog.isShowing())
                || (settingsDialog != null && settingsDialog.isShowing());
    }

    private void saveProgress() {
        saveProgress(-1L);
    }

    private void saveProgressAt(long positionMs) {
        saveProgress(Math.max(0L, positionMs));
    }

    private void saveProgress(long positionMsOverride) {
        if (player == null || request == null || libraryStore == null
                || "LIVE".equalsIgnoreCase(request.media.mediaType)) return;
        long duration = Math.max(0L, player.getDuration());
        long position = positionMsOverride >= 0L ? positionMsOverride : Math.max(0L, player.getCurrentPosition());
        try {
            SavedTitle title = new SavedTitle(request.media.stableId, request.media.providerId,
                    request.media.title, request.media.mediaType, "", request.media.posterUri,
                    request.media.year, request.media.summary, request.media.creator);
            String resumeArtworkRef = request.episode.artworkRef;
            if (TextUtils.isEmpty(resumeArtworkRef)
                    && PlaybackRequest.isSafeLibraryArtworkReference(
                            request.media.resumeArtworkRef)) {
                resumeArtworkRef = request.media.resumeArtworkRef;
            }
            if (TextUtils.isEmpty(resumeArtworkRef)
                    && PlaybackRequest.isSafeLibraryArtworkReference(request.media.posterUri)) {
                resumeArtworkRef = request.media.posterUri;
            }
            boolean recorded = libraryStore.recordPlaybackProgress(request.profileId, title, position, duration,
                    request.episode.id, request.episode.title, request.episode.season,
                    request.episode.number, resumeArtworkRef);
            if (recorded && "orbit-torrent".equals(request.media.providerId)
                    && duration > 0L && position >= duration
                    && libraryStore.getSavedTitle(request.profileId, request.media.stableId) == null) {
                TorrentLibraryStore.remove(this, request.profileId, request.media.stableId);
            }
        } catch (RuntimeException ignored) {
            // A malformed or unavailable local record must not take the player down.
        }
    }

    private void finishPlayback(StopReason reason) {
        if (activityStopNotified) {
            if (!isFinishing()) {
                finish();
            }
            return;
        }
        saveProgress();
        sendObserverProgress();
        notifyObserverStopped(reason);
        activityStopNotified = true;
        if (request != null) unregisterPlaybackObserver(request.observerKey);
        if (player != null) player.pause();
        dismissSourcesDialog();
        releaseTorrentPlaybackSession();
        finish();
    }

    private void releaseTorrentPlaybackSession() {
        if (torrentSessionReleased || request == null || request.media == null
                || !("magnetz".equals(request.media.providerId)
                    || "orbit-torrent".equals(request.media.providerId))) return;
        String stableId = request.media.stableId;
        String prefix = "torrent-btih-";
        if (stableId == null || !stableId.startsWith(prefix)) return;
        String torrentIdentity = stableId.substring(prefix.length());
        if (torrentIdentity.length() < 40) return;
        String infoHash = torrentIdentity.substring(0, 40);
        if (!infoHash.matches("(?i)[0-9a-f]{40}")
                || (torrentIdentity.length() > 40 && torrentIdentity.charAt(40) != '-')) return;
        torrentSessionReleased = true;
        try {
            OrbitTorrentEngine.releaseByInfoHash(this, infoHash);
        } catch (RuntimeException ignored) {
            // Best-effort cleanup must not prevent player exit.
        }
    }

    private void dismissSourcesDialog() {
        if (sourcesDialog != null) {
            Dialog dialog = sourcesDialog;
            sourcesDialog = null;
            providerSourceRows = null;
            providerSourcePager = null;
            providerSubtitleRows = null;
            providerSelectionCaption = null;
            providerSourceHeading = null;
            if (dialog.isShowing()) dialog.dismiss();
        }
        if (audioTracksDialog != null) {
            Dialog dialog = audioTracksDialog;
            audioTracksDialog = null;
            if (dialog.isShowing()) dialog.dismiss();
        }
        if (settingsDialog != null) {
            Dialog dialog = settingsDialog;
            settingsDialog = null;
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    private void notifyObserverReady() { notifyObserver(observer -> observer.onPlaybackReady(snapshot())); }
    private void notifyObserverStarted() { notifyObserver(observer -> observer.onPlaybackStarted(snapshot())); }
    private void notifyObserverProgress() { sendObserverProgress(); }
    private void notifyObserverError() { notifyObserver(observer -> observer.onPlaybackError(snapshot())); }
    private void notifyObserverStopped(StopReason reason) {
        if (request == null || request.observerKey.isEmpty()) return;
        PlaybackObserver observer = OBSERVERS.get(request.observerKey);
        if (observer == null) return;
        try { observer.onPlaybackStopped(snapshot(), reason); } catch (RuntimeException ignored) { }
    }
    private void sendObserverProgress() { notifyObserver(observer -> observer.onPlaybackProgress(snapshot())); }
    private void notifyObserver(ObserverCall call) {
        if (request == null || request.observerKey.isEmpty()) return;
        PlaybackObserver observer = OBSERVERS.get(request.observerKey);
        if (observer == null) return;
        try { call.call(observer); } catch (RuntimeException ignored) { }
    }
    private PlaybackSnapshot snapshot() {
        return new PlaybackSnapshot(request.observerKey, request,
                player == null ? 0L : player.getCurrentPosition(),
                player == null ? 0L : Math.max(0L, player.getDuration()),
                player != null && player.isPlaying());
    }

    private interface ObserverCall { void call(PlaybackObserver observer); }

    private void showStatus(String message) {
        if (statusView == null) return;
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }

    private void reportUnrecognizedProviderSubtitle(String sourceId, String subtitleId) {
        if (TextUtils.isEmpty(sourceId) || TextUtils.isEmpty(subtitleId)) return;
        main.post(() -> {
            String key = sourceId + "\n" + subtitleId;
            unrecognizedProviderSubtitleKeys.add(key);
            if (currentSource == null || !sourceId.equals(currentSource.id)) return;
            if ((subtitlesEnabled
                    && TextUtils.equals(selectedSubtitleId, "external:" + subtitleId))
                    || TextUtils.equals(pendingSubtitleId, "external:" + subtitleId)) {
                showStatus("The selected subtitle format could not be identified.");
                main.postDelayed(this::hideStatus, 3500L);
            }
        });
    }

    private void showUnrecognizedProviderSubtitleIfNeeded(String sourceId, String subtitleId) {
        if (TextUtils.isEmpty(sourceId) || TextUtils.isEmpty(subtitleId)) return;
        if (!unrecognizedProviderSubtitleKeys.contains(sourceId + "\n" + subtitleId)) return;
        showStatus("The selected subtitle format could not be identified.");
        main.postDelayed(this::hideStatus, 3500L);
    }

    private void hideStatus() { if (statusView != null) statusView.setVisibility(View.GONE); }

    private void updateTitleAndEpisode() {
        if (titleView != null) titleView.setText(request.media.title);
        if (episodeView != null) {
            String caption = episodeCaption();
            episodeView.setText(caption);
            episodeView.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private String episodeCaption() {
        if (request.episode.id.isEmpty() && request.episode.title.isEmpty()
                && request.episode.season < 1 && request.episode.number < 1) return "";
        StringBuilder out = new StringBuilder();
        if (request.episode.season > 0) out.append('S').append(request.episode.season);
        if (request.episode.number > 0) {
            if (out.length() > 0) out.append(" · ");
            out.append('E').append(request.episode.number);
        }
        if (!request.episode.title.isEmpty()) {
            if (out.length() > 0) out.append(" — ");
            out.append(request.episode.title);
        }
        return out.toString();
    }

    private String sourceCaption(PlaybackRequest.Source source) {
        return safeLabel(source.label) + (source.mimeType.isEmpty() ? "" : " · " + source.mimeType);
    }

    private String subtitleCaption(String subtitleId) {
        if (subtitleId == null || subtitleId.isEmpty()) return " · Subtitles off";
        if (subtitleId.startsWith("external:")) {
            String id = subtitleId.substring("external:".length());
            for (PlaybackRequest.Subtitle subtitle : dialogSource == null ? currentSource.subtitles : dialogSource.subtitles) {
                if (subtitle.id.equals(id)) return " · " + safeLabel(subtitle.label);
            }
        }
        for (TrackChoice choice : getTrackChoices(C.TRACK_TYPE_TEXT)) if (choice.choiceId.equals(subtitleId)) {
            return " · " + choice.name;
        }
        return " · Subtitle selected";
    }

    private void updateSubtitleCaptionInfo() {
        if (infoView == null || currentSource == null) return;
        infoView.setText(sourceCaption(currentSource)
                + subtitleCaption(subtitlesEnabled ? selectedSubtitleId : ""));
    }

    private String audioDetail(Format format) {
        ArrayList<String> details = new ArrayList<>();
        if (!isEmpty(format.language) && !format.language.equalsIgnoreCase(format.label)) details.add(format.language);
        if (format.channelCount > 0) details.add(format.channelCount == 2 ? "Stereo" : format.channelCount + " channels");
        if (!isEmpty(format.sampleMimeType)) {
            String mime = format.sampleMimeType;
            int slash = mime.lastIndexOf('/');
            details.add(slash >= 0 && slash < mime.length() - 1 ? mime.substring(slash + 1).toUpperCase() : mime);
        }
        return join(details, " · ");
    }

    private static String safeLabel(String value) {
        if (value == null || value.trim().isEmpty()) return "Source";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch)) clean.append(ch);
        }
        String label = clean.toString().trim();
        if (label.isEmpty()) return "Source";
        String lower = label.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("://") || lower.startsWith("www.")) return "Source";
        return label;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) if (value != null && !value.isEmpty()) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static String formatTime(long value) {
        long totalSeconds = Math.max(0L, value / 1000L);
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(java.util.Locale.ROOT, "%d:%02d", totalSeconds / 60L, seconds);
    }

    private static boolean isEmpty(String value) { return value == null || value.isEmpty(); }

    private boolean isTelevisionDevice() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        return mode == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(television ? 14 : 12);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(roundRect(SURFACE, dp(5)));
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(roundRect(focused ? SURFACE_HI : SURFACE, dp(5)));
            button.setTextColor(focused ? TEAL : TEXT);
        });
        button.setOnClickListener(action);
        return button;
    }

    private void styleTransportButton(TextView button, int diameter, boolean primary) {
        button.setTextSize(television ? (primary ? 25 : 18) : (primary ? 40 : 27));
        button.setMinWidth(dp(diameter));
        button.setMinHeight(dp(diameter));
        button.setMaxWidth(dp(diameter));
        button.setMaxHeight(dp(diameter));
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setBackground(oval(primary ? 0xFFF2F3F0 : 0xCC171B1F));
        button.setTextColor(primary ? 0xFF101316 : TEXT);
        button.setOnFocusChangeListener((view, focused) -> {
            if (television && focused) {
                view.setBackground(oval(primary ? Color.WHITE : 0xFF343B40));
                button.setTextColor(primary ? 0xFF101316 : Color.WHITE);
                view.setScaleX(1.13f);
                view.setScaleY(1.13f);
            } else {
                view.setBackground(oval(primary ? 0xFFF2F3F0
                        : (focused ? SURFACE_HI : 0xCC171B1F)));
                button.setTextColor(primary ? 0xFF101316 : (focused ? TEAL : TEXT));
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
        });
    }

    private void setSkipTransportLabel(Button button, String symbol, String contentDescription) {
        SpannableString label = new SpannableString(symbol + "\n10");
        int numberStart = symbol.length() + 1;
        label.setSpan(new RelativeSizeSpan(0.46f), numberStart, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new StyleSpan(Typeface.BOLD), numberStart, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(label);
        button.setContentDescription(contentDescription);
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setShape(GradientDrawable.OVAL);
        return shape;
    }

    private LinearLayout.LayoutParams marginLayout(int horizontal, int vertical) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(horizontal); params.rightMargin = dp(horizontal);
        params.topMargin = dp(vertical); params.bottomMargin = dp(vertical);
        return params;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.leftMargin = dp(left); params.topMargin = dp(top); params.rightMargin = dp(right); params.bottomMargin = dp(bottom);
        return params;
    }

    private static Map<String, Map<String, String>> subtitleHeadersByResource(
            PlaybackRequest.Source source) {
        if (source == null || source.subtitles.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, Map<String, String>> headersByUri = new LinkedHashMap<>();
        Set<String> conflictingUris = new HashSet<>();
        for (PlaybackRequest.Subtitle subtitle : source.subtitles) {
            if (subtitle == null || subtitle.headers.isEmpty()) continue;
            Map<String, String> previous = headersByUri.putIfAbsent(subtitle.uri, subtitle.headers);
            if (previous != null && !previous.equals(subtitle.headers)) {
                conflictingUris.add(subtitle.uri);
            }
        }
        for (String uri : conflictingUris) headersByUri.remove(uri);
        return headersByUri.isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(headersByUri);
    }

    private static Set<String> subtitleResourceUris(PlaybackRequest.Source source) {
        if (source == null || source.subtitles.isEmpty()) return Collections.emptySet();
        HashSet<String> uris = new HashSet<>();
        for (PlaybackRequest.Subtitle subtitle : source.subtitles) {
            if (subtitle != null) uris.add(subtitle.uri);
        }
        return uris.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(uris);
    }

    /**
     * Per-source HTTP factory that confines video headers to the video origin and subtitle headers
     * to their exact subtitle URI. Redirects are handled manually so sensitive headers are never
     * sent to an unchecked origin.
     */
    private static final class OriginBoundHttpDataSourceFactory implements HttpDataSource.Factory {
        private final Origin sourceOrigin;
        private final boolean loopbackTorrentSource;
        private final Map<String, Map<String, String>> resourceHeadersByUri;
        private final Set<String> subtitleResourceUris;
        private final OkHttpClient httpClient;
        private final HttpDataSource.RequestProperties defaultHeaders =
                new HttpDataSource.RequestProperties();

        OriginBoundHttpDataSourceFactory(String sourceUri, Map<String, String> headers,
                                         Interceptor providerInterceptor) {
            this(sourceUri, headers, providerInterceptor,
                    Collections.emptyMap(), Collections.emptySet());
        }

        OriginBoundHttpDataSourceFactory(String sourceUri, Map<String, String> headers,
                                         Interceptor providerInterceptor,
                                         Map<String, Map<String, String>> resourceHeadersByUri,
                                         Set<String> subtitleResourceUris) {
            sourceOrigin = Origin.parse(sourceUri);
            this.resourceHeadersByUri = immutableResourceHeaders(resourceHeadersByUri);
            this.subtitleResourceUris = subtitleResourceUris == null || subtitleResourceUris.isEmpty()
                    ? Collections.emptySet() : Collections.unmodifiableSet(
                            new HashSet<>(subtitleResourceUris));
            loopbackTorrentSource = isLoopbackTorrentStreamUrl(sourceUri)
                    && isValidTorrentAuthHeaders(headers)
                    && OrbitTorrentEngine.isTrustedStreamRequest(sourceUri, headers);
            // The custom DNS changes only socket destinations; TLS keeps the request URL hostname.
            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                    .dns(PlaybackDnsPinning.newDns(loopbackTorrentSource))
                    .proxy(Proxy.NO_PROXY)
                    .connectionPool(new ConnectionPool())
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(loopbackTorrentSource ? TORRENT_STREAM_READ_TIMEOUT_SECONDS : 8,
                            TimeUnit.SECONDS);
            if (providerInterceptor != null) {
                clientBuilder.addInterceptor(chain -> {
                    Request request = chain.request();
                    ProviderRequestPolicy policy = request.tag(ProviderRequestPolicy.class);
                    if (policy == null || !policy.allowProviderInterceptor) {
                        return chain.proceed(request);
                    }
                    return providerInterceptor.intercept(chain);
                });
            }
            clientBuilder.addNetworkInterceptor(chain -> {
                Request request = chain.request();
                ProviderRequestPolicy policy = request.tag(ProviderRequestPolicy.class);
                Origin requestOrigin = Origin.parse(request.url().toString());
                if (policy == null || !policy.origin.sameAs(requestOrigin)) {
                    throw new IOException("The provider interceptor changed the media request origin.");
                }
                if ("http".equals(requestOrigin.scheme) && !policy.loopbackTorrent
                        && (hasCleartextQuery(request.url().toString())
                                || hasUnexpectedCleartextHeader(request, policy))) {
                    throw new IOException(
                            "Orbit cannot use an HTTP link with a query string or provider headers.");
                }
                return chain.proceed(request);
            });
            httpClient = clientBuilder.build();
            setDefaultRequestProperties(headers);
        }

        @Override public OriginBoundHttpDataSource createDataSource() {
            return new OriginBoundHttpDataSource(sourceOrigin, defaultHeaders.getSnapshot(),
                    loopbackTorrentSource, resourceHeadersByUri, subtitleResourceUris, httpClient);
        }

        private static Map<String, Map<String, String>> immutableResourceHeaders(
                Map<String, Map<String, String>> source) {
            if (source == null || source.isEmpty()) return Collections.emptyMap();
            LinkedHashMap<String, Map<String, String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
                copy.put(entry.getKey(), Collections.unmodifiableMap(
                        new LinkedHashMap<>(entry.getValue())));
            }
            return copy.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(copy);
        }

        @Override public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> headers) {
            defaultHeaders.clearAndSet(headers == null ? Collections.emptyMap() : headers);
            return this;
        }
    }

    private static final class OriginBoundHttpDataSource extends BaseDataSource
            implements HttpDataSource {
        private static final int MAX_REDIRECTS = 8;
        private static final String ICY_METADATA_HEADER = "Icy-MetaData";
        private static final String ICY_METADATA_ENABLED = "1";

        private final Origin sourceOrigin;
        private final Map<String, String> defaultHeaders;
        private final boolean loopbackTorrentSource;
        private final Map<String, Map<String, String>> resourceHeadersByUri;
        private final Set<String> subtitleResourceUris;
        private final OkHttpClient httpClient;
        private final HttpDataSource.RequestProperties requestProperties =
                new HttpDataSource.RequestProperties();
        private final Object requestLock = new Object();
        private DataSpec openedDataSpec;
        private Call call;
        private Response response;
        private volatile InputStream inputStream;
        private long requestGeneration;
        private boolean openCancelled;
        private Uri currentUri;
        private Map<String, List<String>> responseHeaders = Collections.emptyMap();
        private long bytesRemaining = C.LENGTH_UNSET;
        private int responseCode = -1;
        private boolean transferStarted;

        OriginBoundHttpDataSource(Origin sourceOrigin, Map<String, String> defaultHeaders,
                                  boolean loopbackTorrentSource,
                                  Map<String, Map<String, String>> resourceHeadersByUri,
                                  Set<String> subtitleResourceUris,
                                  OkHttpClient httpClient) {
            super(true);
            this.sourceOrigin = sourceOrigin;
            this.defaultHeaders = defaultHeaders == null
                    ? Collections.emptyMap() : new LinkedHashMap<>(defaultHeaders);
            this.loopbackTorrentSource = loopbackTorrentSource;
            this.resourceHeadersByUri = resourceHeadersByUri == null
                    ? Collections.emptyMap() : resourceHeadersByUri;
            this.subtitleResourceUris = subtitleResourceUris == null
                    ? Collections.emptySet() : subtitleResourceUris;
            this.httpClient = httpClient;
        }

        private static HttpDataSourceException openFailure(String message, DataSpec dataSpec) {
            return new HttpDataSourceException(message, dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSourceException.TYPE_OPEN);
        }

        @Override public long open(DataSpec dataSpec) throws HttpDataSourceException {
            if (dataSpec == null) throw new IllegalArgumentException("Data specification is required.");
            openedDataSpec = dataSpec;
            final long generation;
            synchronized (requestLock) {
                openCancelled = false;
                generation = ++requestGeneration;
            }
            transferInitializing(dataSpec);
            URL requestUrl;
            Origin requestOrigin;
            try {
                requestUrl = new URL(dataSpec.uri.toString());
                requestOrigin = Origin.parse(requestUrl.toExternalForm());
            } catch (Exception invalidUri) {
                throw openFailure("Invalid media request.", dataSpec);
            }
            if (requestOrigin == null) {
                throw openFailure("Unsupported media request origin.", dataSpec);
            }
            boolean validTorrentRequestHeaders = loopbackTorrentSource
                    && isValidTorrentAuthHeaders(defaultHeaders)
                    && hasOnlyAllowedTorrentDataSpecHeaders(dataSpec)
                    && requestProperties.getSnapshot().isEmpty()
                    && OrbitTorrentEngine.isTrustedStreamRequest(
                            dataSpec.uri.toString(), defaultHeaders);
            boolean mayUseSourceHeaders = sourceOrigin != null && sourceOrigin.sameAs(requestOrigin);
            String requestUri = dataSpec.uri.toString();
            boolean isSubtitleResource = subtitleResourceUris.contains(requestUri);
            Map<String, String> resourceHeaders = resourceHeadersByUri.get(requestUri);
            boolean requestHasProviderHeaders = requestCarriesProviderHeaders(
                    dataSpec, mayUseSourceHeaders, resourceHeaders);
            if ("http".equals(requestOrigin.scheme)
                    && ((!loopbackTorrentSource && requestHasProviderHeaders)
                            || (loopbackTorrentSource && !validTorrentRequestHeaders)
                            || (hasCleartextQuery(dataSpec.uri.toString())
                                    && !loopbackTorrentSource))) {
                throw openFailure(
                        "Orbit cannot use an HTTP link with a query string or provider headers.", dataSpec);
            }
            if (loopbackTorrentSource && (!isLoopbackTorrentStreamUrl(dataSpec.uri.toString())
                    || !OrbitTorrentEngine.isTrustedStreamRequest(
                            dataSpec.uri.toString(), defaultHeaders))) {
                throw openFailure("The local torrent stream request is invalid.", dataSpec);
            }
            Map<String, String> headers = requestHeaders(
                    dataSpec, mayUseSourceHeaders, resourceHeaders);
            String method = dataSpec.getHttpMethodString();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                throw openFailure("Unsupported media request method.", dataSpec);
            }

            int redirects = 0;
            try {
                while (true) {
                    if ("http".equals(requestOrigin.scheme)
                            && ((!loopbackTorrentSource && requestHasProviderHeaders)
                                    || (loopbackTorrentSource && !validTorrentRequestHeaders)
                                    || (hasCleartextQuery(requestUrl.toExternalForm())
                                            && !loopbackTorrentSource))) {
                        throw openFailure(
                                "Orbit cannot use an HTTP link with a query string or provider headers.", dataSpec);
                    }
                    if (loopbackTorrentSource
                            && (!isLoopbackTorrentStreamUrl(requestUrl.toExternalForm())
                                    || !OrbitTorrentEngine.isTrustedStreamRequest(
                                            requestUrl.toExternalForm(), defaultHeaders))) {
                        throw openFailure("The local torrent stream request is invalid.", dataSpec);
                    }
                    Request.Builder requestBuilder = new Request.Builder()
                            .url(requestUrl.toExternalForm())
                            .method(method, null)
                            .tag(ProviderRequestPolicy.class, new ProviderRequestPolicy(
                                    requestOrigin, loopbackTorrentSource && validTorrentRequestHeaders,
                                    mayUseSourceHeaders || !isSubtitleResource, headers.keySet()));
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        requestBuilder.header(header.getKey(), header.getValue());
                    }
                    Call openedCall = httpClient.newCall(requestBuilder.build());
                    if (!publishCall(openedCall, generation)) {
                        throw new IOException("The media request was cancelled.");
                    }
                    Response openedResponse;
                    try {
                        openedResponse = openedCall.execute();
                    } catch (IOException failure) {
                        clearCall(openedCall);
                        throw failure;
                    }
                    if (!publishResponse(openedCall, openedResponse, generation)) {
                        openedResponse.close();
                        throw new IOException("The media request was cancelled.");
                    }
                    int code = openedResponse.code();
                    if (isRedirect(code)) {
                        String location = openedResponse.header("Location");
                        openedResponse.close();
                        if (!clearRedirectResponse(openedCall, openedResponse, generation)) {
                            throw new IOException("The media request was cancelled.");
                        }
                        if (location == null || location.trim().isEmpty()) {
                            throw openFailure("The media server returned an invalid redirect.", dataSpec);
                        }
                        URL redirected = new URL(requestUrl, location.trim());
                        Origin redirectedOrigin = Origin.parse(redirected.toExternalForm());
                        if (redirectedOrigin == null || !requestOrigin.sameAs(redirectedOrigin)) {
                            throw openFailure("Cross-origin media redirects are blocked.", dataSpec);
                        }
                        if (++redirects > MAX_REDIRECTS) {
                            throw openFailure("The media server returned too many redirects.", dataSpec);
                        }
                        requestUrl = redirected;
                        continue;
                    }

                    responseCode = code;
                    responseHeaders = immutableHeaders(openedResponse.headers().toMultimap());
                    if (code < 200 || code >= 300) {
                        throw new InvalidResponseCodeException(code, "", null, responseHeaders,
                                dataSpec, new byte[0]);
                    }
                    currentUri = Uri.parse(openedResponse.request().url().toString());
                    ResponseBody body = openedResponse.body();
                    InputStream responseStream = body == null
                            ? new ByteArrayInputStream(new byte[0]) : body.byteStream();
                    synchronized (requestLock) {
                        if (!isRequestCurrentLocked(openedCall, openedResponse, generation)) {
                            responseStream.close();
                            throw new IOException("The media request was cancelled.");
                        }
                        inputStream = responseStream;
                    }
                    long contentLength = contentLength(openedResponse);
                    long rangeLength = code == HttpURLConnection.HTTP_PARTIAL
                            ? contentRangeLength(openedResponse, dataSpec.position, dataSpec)
                            : C.LENGTH_UNSET;
                    if (rangeLength >= 0) contentLength = rangeLength;
                    if (code == HttpURLConnection.HTTP_OK && dataSpec.position > 0) {
                        skipFully(inputStream, dataSpec.position);
                        if (contentLength >= 0) contentLength = Math.max(0L, contentLength - dataSpec.position);
                    }
                    bytesRemaining = dataSpec.length == C.LENGTH_UNSET ? contentLength
                            : contentLength < 0 ? dataSpec.length : Math.min(dataSpec.length, contentLength);
                    synchronized (requestLock) {
                        if (!isRequestCurrentLocked(openedCall, openedResponse, generation)) {
                            throw new IOException("The media request was cancelled.");
                        }
                        transferStarted(dataSpec);
                        transferStarted = true;
                    }
                    return bytesRemaining;
                }
            } catch (HttpDataSourceException failure) {
                closeQuietly();
                throw failure;
            } catch (IOException failure) {
                closeQuietly();
                throw HttpDataSourceException.createForIOException(failure, dataSpec,
                        HttpDataSourceException.TYPE_OPEN);
            }
        }

        private boolean publishCall(Call candidate, long generation) {
            synchronized (requestLock) {
                if (openCancelled || generation != requestGeneration) return false;
                call = candidate;
                return true;
            }
        }

        private boolean publishResponse(Call candidate, Response candidateResponse, long generation) {
            synchronized (requestLock) {
                if (!isRequestCurrentLocked(candidate, null, generation)) return false;
                response = candidateResponse;
                return true;
            }
        }

        private boolean clearRedirectResponse(Call candidate, Response candidateResponse,
                long generation) {
            synchronized (requestLock) {
                if (response == candidateResponse) response = null;
                if (call == candidate) call = null;
                return !openCancelled && generation == requestGeneration;
            }
        }

        private void clearCall(Call candidate) {
            synchronized (requestLock) {
                if (call == candidate) call = null;
            }
        }

        private boolean isRequestCurrentLocked(Call candidate, Response candidateResponse,
                long generation) {
            return !openCancelled && generation == requestGeneration && call == candidate
                    && (candidateResponse == null || response == candidateResponse);
        }

        private Map<String, String> requestHeaders(DataSpec dataSpec, boolean mayUseSourceHeaders,
                Map<String, String> resourceHeaders) {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            if (mayUseSourceHeaders) {
                for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
                    putHeader(headers, entry.getKey(), entry.getValue());
                }
                for (Map.Entry<String, String> entry : requestProperties.getSnapshot().entrySet()) {
                    putHeader(headers, entry.getKey(), entry.getValue());
                }
                if (dataSpec.httpRequestHeaders != null) {
                    for (Map.Entry<String, String> entry : dataSpec.httpRequestHeaders.entrySet()) {
                        putHeader(headers, entry.getKey(), entry.getValue());
                    }
                }
            }
            if (resourceHeaders != null) {
                for (Map.Entry<String, String> entry : resourceHeaders.entrySet()) {
                    putHeader(headers, entry.getKey(), entry.getValue());
                }
            }
            if (dataSpec.position != 0 || dataSpec.length != C.LENGTH_UNSET) {
                String range = "bytes=" + dataSpec.position + "-";
                if (dataSpec.length != C.LENGTH_UNSET) {
                    range += Long.toString(dataSpec.position + dataSpec.length - 1L);
                }
                putHeader(headers, "Range", range);
            }
            if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
                putHeaderIfAbsent(headers, "Accept-Encoding", "identity");
            }
            return headers;
        }

        private boolean requestCarriesProviderHeaders(DataSpec dataSpec,
                boolean mayUseSourceHeaders, Map<String, String> resourceHeaders) {
            if (mayUseSourceHeaders
                    && (!defaultHeaders.isEmpty() || !requestProperties.getSnapshot().isEmpty())) return true;
            if (resourceHeaders != null && !resourceHeaders.isEmpty()) return true;
            Map<String, String> dataSpecHeaders = dataSpec.httpRequestHeaders;
            if (dataSpecHeaders == null || dataSpecHeaders.isEmpty()) return false;
            if (dataSpecHeaders.size() != 1) return true;
            Map.Entry<String, String> header = dataSpecHeaders.entrySet().iterator().next();
            return header.getKey() == null
                    || !ICY_METADATA_HEADER.equalsIgnoreCase(header.getKey())
                    || !ICY_METADATA_ENABLED.equals(header.getValue());
        }

        /** Media3 adds this harmless default header even to the authenticated loopback stream. */
        private boolean hasOnlyAllowedTorrentDataSpecHeaders(DataSpec dataSpec) {
            Map<String, String> headers = dataSpec.httpRequestHeaders;
            if (headers == null || headers.isEmpty()) return true;
            if (headers.size() != 1) return false;
            Map.Entry<String, String> header = headers.entrySet().iterator().next();
            return header.getKey() != null
                    && ICY_METADATA_HEADER.equalsIgnoreCase(header.getKey())
                    && ICY_METADATA_ENABLED.equals(header.getValue());
        }

        private static void putHeader(Map<String, String> headers, String name, String value) {
            String existing = null;
            for (String key : headers.keySet()) {
                if (key.equalsIgnoreCase(name)) { existing = key; break; }
            }
            if (existing != null) headers.remove(existing);
            headers.put(name, value);
        }

        private static void putHeaderIfAbsent(Map<String, String> headers, String name, String value) {
            for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) return;
            headers.put(name, value);
        }

        private static boolean isRedirect(int code) {
            return code == HttpURLConnection.HTTP_MULT_CHOICE
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308;
        }

        private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> input) {
            if (input == null || input.isEmpty()) return Collections.emptyMap();
            return Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }

        private static long contentLength(Response response) {
            String value = response.header("Content-Length");
            if (value == null) return C.LENGTH_UNSET;
            try { return Math.max(0L, Long.parseLong(value.trim())); }
            catch (NumberFormatException ignored) { return C.LENGTH_UNSET; }
        }

        private static long contentRangeLength(Response response, long requestedPosition,
                DataSpec dataSpec) throws HttpDataSourceException {
            String value = response.header("Content-Range");
            if (value == null) return C.LENGTH_UNSET;
            String range = value.trim();
            int space = range.indexOf(' ');
            int dash = range.indexOf('-', space + 1);
            int slash = range.indexOf('/', dash + 1);
            if (space < 0 || dash < 0 || slash < 0) return C.LENGTH_UNSET;
            try {
                long start = Long.parseLong(range.substring(space + 1, dash).trim());
                long end = Long.parseLong(range.substring(dash + 1, slash).trim());
                if (start != requestedPosition || end < start) {
                    throw openFailure("The media server returned an invalid byte range.", dataSpec);
                }
                return end - start + 1L;
            } catch (NumberFormatException ignored) {
                return C.LENGTH_UNSET;
            }
        }

        private static void skipFully(InputStream stream, long bytes) throws IOException {
            OriginBoundStreamProgress.skipFully(stream, bytes);
        }

        @Override public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
            if (length == 0) return 0;
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
            try {
                int requested = bytesRemaining == C.LENGTH_UNSET ? length
                        : (int) Math.min((long) length, bytesRemaining);
                int read = OriginBoundStreamProgress.read(inputStream, buffer, offset, requested);
                if (read == -1) {
                    if (bytesRemaining != C.LENGTH_UNSET) {
                        throw new EOFException("The media stream ended before the declared length.");
                    }
                    return C.RESULT_END_OF_INPUT;
                }
                if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
                bytesTransferred(read);
                return read;
            } catch (IOException failure) {
                throw HttpDataSourceException.createForIOException(failure, openedDataSpec,
                        HttpDataSourceException.TYPE_READ);
            }
        }

        @Override public Uri getUri() { return currentUri; }

        @Override public Map<String, List<String>> getResponseHeaders() { return responseHeaders; }

        @Override public int getResponseCode() { return responseCode; }

        @Override public void setRequestProperty(String name, String value) {
            requestProperties.set(name, value);
        }

        @Override public void clearRequestProperty(String name) { requestProperties.remove(name); }

        @Override public void clearAllRequestProperties() { requestProperties.clear(); }

        @Override public void close() throws HttpDataSourceException {
            try {
                if (inputStream != null) inputStream.close();
            } catch (IOException failure) {
                throw HttpDataSourceException.createForIOException(failure, openedDataSpec,
                        HttpDataSourceException.TYPE_CLOSE);
            } finally {
                disconnect();
                if (transferStarted) {
                    transferStarted = false;
                    super.transferEnded();
                }
                bytesRemaining = C.LENGTH_UNSET;
                currentUri = null;
                responseHeaders = Collections.emptyMap();
                responseCode = -1;
            }
        }

        private void closeQuietly() {
            try { if (inputStream != null) inputStream.close(); }
            catch (IOException ignored) { }
            disconnect();
            if (transferStarted) {
                transferStarted = false;
                super.transferEnded();
            }
            bytesRemaining = C.LENGTH_UNSET;
            currentUri = null;
            responseHeaders = Collections.emptyMap();
            responseCode = -1;
        }

        private void disconnect() {
            Call activeCall;
            Response activeResponse;
            synchronized (requestLock) {
                openCancelled = true;
                requestGeneration++;
                activeCall = call;
                call = null;
                activeResponse = response;
                response = null;
                inputStream = null;
            }
            if (activeCall != null) activeCall.cancel();
            if (activeResponse != null) {
                try { activeResponse.close(); }
                catch (Exception ignored) { }
            }
        }
    }

    private static final class Origin {
        final String scheme;
        final String host;
        final int port;

        Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        static Origin parse(String rawUrl) {
            if (rawUrl == null || rawUrl.isEmpty()) return null;
            try {
                URI uri = new URI(rawUrl);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (scheme == null || host == null || host.isEmpty() || uri.getUserInfo() != null) return null;
                scheme = scheme.toLowerCase(Locale.ROOT);
                if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
                int port = uri.getPort();
                if (port < 0) port = "https".equals(scheme) ? 443 : 80;
                if (port < 1 || port > 65535) return null;
                return new Origin(scheme, host.toLowerCase(Locale.ROOT), port);
            } catch (URISyntaxException ignored) {
                return null;
            }
        }

        boolean sameAs(Origin other) {
            return other != null && scheme.equals(other.scheme)
                    && host.equals(other.host) && port == other.port;
        }
    }

    /** Request-local origin and header policy carried through provider interceptors. */
    private static final class ProviderRequestPolicy {
        final Origin origin;
        final boolean loopbackTorrent;
        final boolean allowProviderInterceptor;
        final Set<String> originalHeaderNames;

        ProviderRequestPolicy(Origin origin, boolean loopbackTorrent,
                boolean allowProviderInterceptor, Set<String> originalHeaders) {
            this.origin = origin;
            this.loopbackTorrent = loopbackTorrent;
            this.allowProviderInterceptor = allowProviderInterceptor;
            HashSet<String> normalized = new HashSet<>();
            if (originalHeaders != null) {
                for (String name : originalHeaders) {
                    if (name != null) normalized.add(name.toLowerCase(Locale.ROOT));
                }
            }
            this.originalHeaderNames = Collections.unmodifiableSet(normalized);
        }
    }

    private static boolean hasUnexpectedCleartextHeader(Request request,
                                                         ProviderRequestPolicy policy) {
        for (String name : request.headers().names()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (policy.originalHeaderNames.contains(lower)) continue;
            if ("host".equals(lower) || "connection".equals(lower)
                    || "accept-encoding".equals(lower) || "user-agent".equals(lower)) continue;
            return true;
        }
        return false;
    }

    private static boolean hasCleartextQuery(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            return "http".equalsIgnoreCase(uri.getScheme()) && uri.getRawQuery() != null;
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    /** Accepts only TorrServer's loopback stream URL shape; no remote HTTP URL receives this exception. */
    private static boolean isLoopbackTorrentStreamUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.length() > 8192) return false;
        final URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException ignored) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1024 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() == null) return false;

        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/stream/") || rawPath.length() <= 8
                || rawPath.length() > 4096 || rawPath.indexOf('\\') >= 0) return false;
        final String decodedPath;
        try {
            decodedPath = URLDecoder.decode(rawPath.substring("/stream/".length()), "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
            return false;
        }
        if (decodedPath.isEmpty() || decodedPath.startsWith("/") || decodedPath.indexOf('\\') >= 0
                || decodedPath.indexOf('\u0000') >= 0) return false;
        for (String segment : decodedPath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }

        String[] parameters = uri.getRawQuery().split("&", -1);
        if (parameters.length != 3) return false;
        boolean link = false;
        boolean index = false;
        boolean play = false;
        for (String parameter : parameters) {
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                if ("play".equals(parameter)) play = true;
                else return false;
                continue;
            }
            if (equals == 0 || equals == parameter.length() - 1) return false;
            String key = parameter.substring(0, equals);
            String value = parameter.substring(equals + 1);
            if ("link".equals(key)) {
                if (!value.matches("(?i)[0-9a-f]{40}")) return false;
                link = true;
            } else if ("index".equals(key)) {
                if (!value.matches("[0-9]{1,7}")) return false;
                try {
                    if (Integer.parseInt(value) > 1_000_000) return false;
                } catch (NumberFormatException ignored) {
                    return false;
                }
                index = true;
            } else return false;
        }
        return link && index && play;
    }

    private static boolean isValidTorrentAuthHeaders(Map<String, String> headers) {
        if (headers == null || headers.size() != 1) return false;
        Map.Entry<String, String> entry = headers.entrySet().iterator().next();
        return entry.getKey() != null && TORRENT_AUTH_HEADER.equalsIgnoreCase(entry.getKey())
                && entry.getValue() != null && entry.getValue().matches("(?i)[0-9a-f]{64}");
    }

    private final class TrackChoice {
        final String choiceId, name, detail;
        final Tracks.Group group;
        final int trackIndex;
        final boolean selected;
        TrackChoice(String choiceId, String name, String detail, Tracks.Group group, int trackIndex, boolean selected) {
            this.choiceId = choiceId; this.name = name; this.detail = detail;
            this.group = group; this.trackIndex = trackIndex; this.selected = selected;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { togglePlayPause(); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_REWIND) { seekBy(-10_000L); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { seekBy(10_000L); return true; }
            if (!controlsVisible && code == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-10_000L); return true; }
            if (!controlsVisible && code == KeyEvent.KEYCODE_DPAD_RIGHT) { seekBy(10_000L); return true; }
            if (!controlsVisible && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                setControlsVisible(true); return true;
            }
            if (controlsVisible && (code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER)) {
                scheduleControlsHide();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // API 33+ registers a predictive-back callback; this override is the pre-33 fallback.
    @Override @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation") public void onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handleBackNavigation();
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            wasPlayingBeforePause = true;
            player.pause();
        }
        saveProgress();
        sendObserverProgress();
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemChrome();
        if (player != null && wasPlayingBeforePause) {
            wasPlayingBeforePause = false;
            player.play();
        }
    }

    @Override protected void onDestroy() {
        unregisterApi33BackCallback();
        main.removeCallbacks(hideControlsTask);
        main.removeCallbacks(progressTask);
        providerResolutionGeneration++;
        if (providerLinksBatch != null) providerLinksBatch.close();
        providerLinksBatch = null;
        deferredProviderPlayback = null;
        saveProgress();
        if (isFinishing() && !activityStopNotified && request != null) {
            notifyObserverStopped(lastSourceFailed ? StopReason.FAILURE : StopReason.USER_EXIT);
            activityStopNotified = true;
            unregisterPlaybackObserver(request.observerKey);
        }
        if (isFinishing()) releaseUnreferencedSubtitleDocumentGrants();
        if (isFinishing()) releaseTorrentPlaybackSession();
        if (request != null) request.closeProviderHooks();
        activeVerifierHooks = null;
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private void releaseUnreferencedSubtitleDocumentGrants() {
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        if (request != null && request.sources != null) {
            for (PlaybackRequest.Source source : request.sources) {
                if (source == null || source.subtitles == null) continue;
                for (PlaybackRequest.Subtitle subtitle : source.subtitles) {
                    if (subtitle == null || TextUtils.isEmpty(subtitle.uri)) continue;
                    Uri uri = Uri.parse(subtitle.uri);
                    if ("content".equalsIgnoreCase(uri.getScheme())) referenced.add(uri.toString());
                }
            }
        }
        SubtitleDocumentGrantManager.releaseUnreferenced(this, referenced);
    }
}
