package com.auras.orbit.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.Test;

public final class SubtitleDocumentGrantPolicyTest {
    @Test public void onlyAcceptsSupportedContentDocumentsForAnExistingSource() {
        assertTrue(SubtitleDocumentGrantPolicy.isAcceptedDocument(
                true, "content://picker/document/subtitle", "application/x-subrip"));
        assertFalse(SubtitleDocumentGrantPolicy.isAcceptedDocument(
                false, "content://picker/document/subtitle", "application/x-subrip"));
        assertFalse(SubtitleDocumentGrantPolicy.isAcceptedDocument(
                true, "content://picker/document/subtitle", "application/pdf"));
        assertFalse(SubtitleDocumentGrantPolicy.isAcceptedDocument(
                true, "https://picker.example/subtitle.srt", "application/x-subrip"));
        assertFalse(SubtitleDocumentGrantPolicy.isAcceptedDocument(
                true, "content:///document/subtitle", "application/x-subrip"));
    }

    @Test public void persistedGrantCanBeReleasedWhenItsUriIsNoLongerReferenced() {
        assertFalse(SubtitleDocumentGrantPolicy.isUnreferenced(
                "content://picker/document/a", new HashSet<>(Arrays.asList(
                        "content://picker/document/a", "content://picker/document/b"))));
        assertTrue(SubtitleDocumentGrantPolicy.isUnreferenced(
                "content://picker/document/a", Collections.singleton("content://picker/document/b")));
    }

    @Test public void subtitleIsNotAddedUnlessAcceptedReadGrantWasPersisted() {
        assertTrue(SubtitleDocumentGrantPolicy.canAddToPlayback(
                true, "content://picker/document/subtitle", "application/x-subrip", true));
        assertFalse(SubtitleDocumentGrantPolicy.canAddToPlayback(
                true, "content://picker/document/subtitle", "application/x-subrip", false));
        assertFalse(SubtitleDocumentGrantPolicy.canAddToPlayback(
                true, "content://picker/document/subtitle", "application/pdf", true));
    }
}
