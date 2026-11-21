package com.auras.orbit.migration;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;

import com.auras.orbit.intent.IncomingRequest;
import com.auras.orbit.intent.RequestValidation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explicit opt-in adapter from a selected CloudStream BackupUtils export to Orbit's normal
 * repository-request review flow. It does not apply profiles, settings, packages, or library rows.
 */
public final class MigrationImportService {
    /** Call only from Orbit's explicit migration opt-in action. */
    public OptInSession beginExplicitOptIn() {
        return new OptInSession();
    }

    /**
     * Wrap a read-granted content URI returned by the app's ACTION_OPEN_DOCUMENT flow.
     * File paths and package/context inspection of another application are unsupported.
     */
    public static UserSelectedExport fromOpenDocumentResult(Context context, Uri uri)
            throws MigrationException {
        if (context == null || uri == null) {
            throw new MigrationException("Choose an export from Orbit's document picker.");
        }
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())
                || context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new MigrationException("The selected document is not available for read-only import.");
        }
        Context appContext = context.getApplicationContext();
        ContentResolver resolver = (appContext == null ? context : appContext).getContentResolver();
        return new UserSelectedExport(resolver, uri);
    }

    private static RepositoryCandidatePreview previewRepository(String url) {
        try {
            IncomingRequest request = createValidatedRepositoryRequest(url);
            RequestValidation validation = request.validate();
            String display = validation.getItems().get(0).getSafeDisplayText();
            return new RepositoryCandidatePreview(request, validation, display, true);
        } catch (MigrationException rejected) {
            return new RepositoryCandidatePreview(null, null,
                    "Repository link rejected; nothing was opened or contacted.", false);
        }
    }

    private static IncomingRequest createValidatedRepositoryRequest(String url)
            throws MigrationException {
        String rawValue = url.regionMatches(true, 0, "cloudstreamrepo://", 0,
                "cloudstreamrepo://".length())
                ? url : "cloudstreamrepo://" + url;
        IncomingRequest request = IncomingRequest.restore(Intent.ACTION_VIEW, "text/uri-list",
                Collections.singletonList(rawValue));
        if (request == null) throw new MigrationException("The repository link could not be reviewed.");
        RequestValidation validation = request.validate();
        List<RequestValidation.Item> items = validation.getItems();
        if (items.size() != 1 || validation.hasRejectedItems()
                || items.get(0).kind != RequestValidation.Kind.REPOSITORY_LINK
                || !items.get(0).requiresConfirmation()) {
            throw new MigrationException("Orbit rejected a repository link during incoming-request validation.");
        }
        return request;
    }

    public static final class OptInSession {
        private MigrationPreview latestPreview;
        private boolean closed;

        private OptInSession() { }

        /** Reads the explicitly selected export and creates a read-only preview. */
        public MigrationPreview preview(UserSelectedExport selectedExport)
                throws IOException, MigrationException {
            requireOpen();
            if (selectedExport == null) throw new MigrationException("Choose an export to preview.");
            try (InputStream input = selectedExport.open()) {
                latestPreview = decode(input);
                return latestPreview;
            }
        }

        /** Deterministic source-fixture entry point; production UI must not expose this method. */
        public MigrationPreview previewDeterministicFixture(byte[] utf8Json)
                throws IOException, MigrationException {
            requireOpen();
            if (utf8Json == null) throw new MigrationException("The migration fixture is missing.");
            try (InputStream input = new ByteArrayInputStream(utf8Json.clone())) {
                latestPreview = decode(input);
                return latestPreview;
            }
        }

        /**
         * Returns a selected repository candidate as Orbit's existing validated request envelope.
         * The returned request has not been fetched, added, or otherwise executed.
         */
        public RepositoryHandoff prepareRepositoryHandoff(MigrationPreview preview, int index)
                throws MigrationException {
            requireLatestPreview(preview);
            if (index < 0 || index >= preview.repositoryCandidates.size()) {
                throw new MigrationException("Choose a repository link from this preview.");
            }
            RepositoryCandidatePreview candidate = preview.repositoryCandidates.get(index);
            if (!candidate.handoffAvailable || candidate.request == null || candidate.validation == null) {
                throw new MigrationException("Orbit rejected this repository link during incoming-request validation.");
            }
            return new RepositoryHandoff(candidate.request, candidate.validation,
                    candidate.safeDisplayText);
        }

        public void cancel() {
            closed = true;
            latestPreview = null;
        }

        private MigrationPreview decode(InputStream input)
                throws IOException, MigrationException {
            MigrationBundleCodec.Bundle bundle = MigrationBundleCodec.read(input);
            ArrayList<RepositoryCandidatePreview> candidates = new ArrayList<>();
            for (MigrationBundleCodec.RepositoryCandidate candidate : bundle.repositories) {
                candidates.add(previewRepository(candidate.url));
            }
            return new MigrationPreview(this, candidates);
        }

        private void requireOpen() throws MigrationException {
            if (closed) throw new MigrationException("The migration session is no longer active.");
        }

        private void requireLatestPreview(MigrationPreview preview) throws MigrationException {
            requireOpen();
            if (preview == null || preview != latestPreview || preview.owner != this) {
                throw new MigrationException("The migration preview is no longer current.");
            }
        }
    }

    /** Read grant limited to a content URI returned by ACTION_OPEN_DOCUMENT. */
    public static final class UserSelectedExport {
        private final ContentResolver resolver;
        private final Uri uri;

        private UserSelectedExport(ContentResolver resolver, Uri uri) {
            this.resolver = resolver;
            this.uri = uri;
        }

        private InputStream open() throws IOException {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("The selected export is unavailable.");
            return input;
        }
    }

    public static final class MigrationPreview {
        private final OptInSession owner;
        private final List<RepositoryCandidatePreview> repositoryCandidates;
        public final List<RepositoryPreview> repositories;

        private MigrationPreview(OptInSession owner, List<RepositoryCandidatePreview> candidates) {
            this.owner = owner;
            this.repositoryCandidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            ArrayList<RepositoryPreview> rows = new ArrayList<>();
            for (RepositoryCandidatePreview candidate : candidates) {
                rows.add(new RepositoryPreview(candidate.safeDisplayText, candidate.handoffAvailable));
            }
            repositories = Collections.unmodifiableList(rows);
        }

        public String getSafeSummary() {
            int acceptedLinks = 0;
            for (RepositoryPreview repository : repositories) {
                if (repository.handoffAvailable) acceptedLinks++;
            }
            return repositories.size() + (repositories.size() == 1
                    ? " repository link" : " repository links")
                    + " discovered; " + acceptedLinks + " passed Orbit's incoming-request validation."
                    + " No repository has been added or contacted. Profile and library data use Orbit's .orbitlib import.";
        }
    }

    public static final class RepositoryPreview {
        public final String safeDisplayText;
        public final boolean handoffAvailable;

        private RepositoryPreview(String safeDisplayText, boolean handoffAvailable) {
            this.safeDisplayText = safeDisplayText;
            this.handoffAvailable = handoffAvailable;
        }
    }

    public static final class RepositoryHandoff {
        private final IncomingRequest request;
        private final RequestValidation validation;
        public final String safeDisplayText;

        private RepositoryHandoff(IncomingRequest request, RequestValidation validation,
                String safeDisplayText) {
            this.request = request;
            this.validation = validation;
            this.safeDisplayText = safeDisplayText;
        }

        /** Pass this envelope into Orbit's existing incoming-request review state. */
        public IncomingRequest getIncomingRequestForReview() {
            return request;
        }

        public RequestValidation getValidationForReview() {
            return validation;
        }
    }

    private static final class RepositoryCandidatePreview {
        final IncomingRequest request;
        final RequestValidation validation;
        final String safeDisplayText;
        final boolean handoffAvailable;

        RepositoryCandidatePreview(IncomingRequest request, RequestValidation validation,
                String safeDisplayText, boolean handoffAvailable) {
            this.request = request;
            this.validation = validation;
            this.safeDisplayText = safeDisplayText;
            this.handoffAvailable = handoffAvailable;
        }
    }

    public static final class MigrationException extends Exception {
        public MigrationException(String message) {
            super(message);
        }

        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
