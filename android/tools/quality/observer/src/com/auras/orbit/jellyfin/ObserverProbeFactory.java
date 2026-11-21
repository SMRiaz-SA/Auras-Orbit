package com.auras.orbit.jellyfin;
import java.util.*;
import com.auras.orbit.jellyfin.JellyfinModels.*;
public final class ObserverProbeFactory {
    public static JellyfinPlaybackObserver create(JellyfinClient client) {
        return new JellyfinPlaybackObserver(client,
          new Connection("https://example.test", "Server", "user", "Test", "server", "device", "token"),
          new MediaItem("item", "Title", "Movie", "", "", "", 0L, 0L, false, 0, 0, 0),
          new PlaybackInfo("session", "", 0L, Collections.emptyList()),
          new PlaybackSource("source", "Original", "mp4", "http", false, true, false, false,
                  "", "", Collections.emptyList()));
    }
}
