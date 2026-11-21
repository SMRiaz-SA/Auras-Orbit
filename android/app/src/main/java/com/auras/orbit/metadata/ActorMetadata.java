package com.auras.orbit.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-independent TMDB person details for an Auras cast page. */
public final class ActorMetadata {
    /** Fail explicitly for pathological metadata rather than allocating from untrusted size(). */
    public static final int MAX_CREDITS = 20_000;
    public final String name;
    /** TMDB relative profile path, never an arbitrary image URL. */
    public final String profilePath;
    public final String biography;
    public final String birthday;
    public final String deathday;
    public final String placeOfBirth;
    public final List<Credit> credits;

    public ActorMetadata(String name, String profilePath, String biography, String birthday,
            String deathday, String placeOfBirth, List<Credit> credits) {
        this.name = bounded(name, 160);
        this.profilePath = bounded(profilePath, 512);
        this.biography = bounded(biography, 4_000);
        this.birthday = bounded(birthday, 80);
        this.deathday = bounded(deathday, 80);
        this.placeOfBirth = bounded(placeOfBirth, 240);
        this.credits = immutableCredits(credits);
    }

    private static List<Credit> immutableCredits(List<Credit> values) {
        if (values == null) return Collections.emptyList();
        int count = values.size();
        if (count < 0 || count > MAX_CREDITS) {
            throw new IllegalArgumentException("Actor filmography exceeds the supported credit limit");
        }
        if (count == 0) return Collections.emptyList();
        ArrayList<Credit> result = new ArrayList<>(count);
        // TMDB results are normally ordinary arrays. Index by the validated count so a
        // malformed List cannot expose an infinite iterator after passing the size check.
        for (int index = 0; index < count; index++) {
            Credit value = values.get(index);
            if (value == null || value.title.isEmpty()) continue;
            result.add(value);
        }
        return result.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(result);
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null) return "";
        String cleaned = value.trim();
        return cleaned.length() <= maximumLength
                ? cleaned : cleaned.substring(0, maximumLength);
    }

    public static final class Credit {
        public final String title;
        public final String year;
        public final String mediaType;
        public final String role;
        /** TMDB relative poster path, never an arbitrary image URL. */
        public final String posterPath;

        public Credit(String title, String year, String mediaType, String role) {
            this(title, year, mediaType, role, "");
        }

        public Credit(String title, String year, String mediaType, String role,
                String posterPath) {
            this.title = bounded(title, 240);
            this.year = bounded(year, 16);
            this.mediaType = "TV".equalsIgnoreCase(mediaType) ? "TV" : "Movie";
            this.role = bounded(role, 240);
            this.posterPath = bounded(posterPath, 512);
        }
    }
}
