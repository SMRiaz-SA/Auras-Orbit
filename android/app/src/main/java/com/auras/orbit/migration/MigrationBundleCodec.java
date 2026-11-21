package com.auras.orbit.migration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Reads only the supported repository field from the pinned CloudStream BackupUtils export shape. */
final class MigrationBundleCodec {
    static final String BACKUP_SOURCE_REVISION = "5024ace2922a148d38df2a816b483b529789cf58";
    static final String REPOSITORIES_KEY = "REPOSITORIES_KEY";
    static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    static final int MAX_REPOSITORIES = 1_000;
    static final int MAX_REPOSITORY_URL_LENGTH = 4_000;

    private static final String[] BACKUP_VAR_FIELDS = {
            "_Bool", "_Int", "_String", "_Float", "_Long", "_StringSet"
    };

    private MigrationBundleCodec() { }

    static Bundle read(InputStream input) throws IOException, MigrationImportService.MigrationException {
        if (input == null) throw invalid("The selected export is unavailable.");
        byte[] bytes = readBounded(input);
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw invalid("The selected export is not valid UTF-8.", error);
        }
        try {
            return parse(new JSONObject(json));
        } catch (JSONException | IllegalArgumentException error) {
            throw invalid("The selected export is not a supported CloudStream backup.", error);
        }
    }

    private static byte[] readBounded(InputStream input)
            throws IOException, MigrationImportService.MigrationException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                int single = input.read();
                if (single == -1) break;
                if (output.size() >= MAX_INPUT_BYTES) {
                    throw invalid("The selected export is larger than the supported limit.");
                }
                output.write(single);
                continue;
            }
            if (output.size() + (long) count > MAX_INPUT_BYTES) {
                throw invalid("The selected export is larger than the supported limit.");
            }
            output.write(buffer, 0, count);
        }
        if (output.size() == 0) throw invalid("The selected export is empty.");
        return output.toByteArray();
    }

    private static Bundle parse(JSONObject root)
            throws JSONException, MigrationImportService.MigrationException {
        requireOnlyKeys(root, "datastore", "settings");
        JSONObject datastore = requiredObject(root, "datastore");
        JSONObject settings = requiredObject(root, "settings");
        validateBackupVars(datastore, "datastore");
        validateBackupVars(settings, "settings");

        JSONObject dataStrings = optionalObject(datastore, "_String", "datastore string map");
        Object encodedRepositories = dataStrings == null ? null : dataStrings.opt(REPOSITORIES_KEY);
        if (encodedRepositories == null || encodedRepositories == JSONObject.NULL) {
            return new Bundle(Collections.emptyList());
        }
        if (!(encodedRepositories instanceof String)) {
            throw invalid("The repository list in the selected export has an unsupported value type.");
        }

        final JSONArray rows;
        try {
            rows = new JSONArray((String) encodedRepositories);
        } catch (JSONException error) {
            throw invalid("The repository list in the selected export is malformed.", error);
        }
        if (rows.length() > MAX_REPOSITORIES) {
            throw invalid("The selected export contains too many repository links.");
        }

        ArrayList<RepositoryCandidate> repositories = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) throw invalid("The repository list contains an invalid entry.");
            requireOnlyKeys(row, "iconUrl", "name", "url", "description");
            requireOptionalString(row, "iconUrl", 65_536);
            requireOptionalString(row, "description", 65_536);
            requiredString(row, "name", 4_000);
            String url = requiredString(row, "url", MAX_REPOSITORY_URL_LENGTH);
            repositories.add(new RepositoryCandidate(url));
        }
        return new Bundle(repositories);
    }

    /** Validates the documented BackupVars member names; only datastore._String.REPOSITORIES_KEY is consumed. */
    private static void validateBackupVars(JSONObject value, String label)
            throws MigrationImportService.MigrationException {
        requireOnlyKeys(value, BACKUP_VAR_FIELDS);
        validateBooleanMap(optionalObject(value, "_Bool", label + " boolean map"));
        validateIntegerMap(optionalObject(value, "_Int", label + " integer map"),
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        validateStringMap(optionalObject(value, "_String", label + " string map"));
        validateFloatMap(optionalObject(value, "_Float", label + " float map"));
        validateIntegerMap(optionalObject(value, "_Long", label + " long map"),
                Long.MIN_VALUE, Long.MAX_VALUE);
        validateStringSetMap(optionalObject(value, "_StringSet", label + " string-set map"));
    }

    private static void validateBooleanMap(JSONObject map) throws MigrationImportService.MigrationException {
        if (map == null) return;
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            Object value = map.opt(keys.next());
            if (!(value instanceof Boolean)) throw invalid("The selected export contains invalid backup data.");
        }
    }

    private static void validateStringMap(JSONObject map) throws MigrationImportService.MigrationException {
        if (map == null) return;
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            Object value = map.opt(keys.next());
            if (!(value instanceof String)) throw invalid("The selected export contains invalid backup data.");
        }
    }

    private static void validateIntegerMap(JSONObject map, long minValue, long maxValue)
            throws MigrationImportService.MigrationException {
        if (map == null) return;
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            Object value = map.opt(keys.next());
            BigInteger integer = exactJsonInteger(value);
            if (integer == null
                    || integer.compareTo(BigInteger.valueOf(minValue)) < 0
                    || integer.compareTo(BigInteger.valueOf(maxValue)) > 0) {
                throw invalid("The selected export contains invalid backup data.");
            }
        }
    }

    private static BigInteger exactJsonInteger(Object value) {
        // Decimal and exponent tokens parse as floating-point values and cannot satisfy Int/Long fields.
        if (value instanceof Integer || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger) return (BigInteger) value;
        return null;
    }

    private static void validateFloatMap(JSONObject map)
            throws MigrationImportService.MigrationException {
        if (map == null) return;
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            Object value = map.opt(keys.next());
            if (!(value instanceof Number)) throw invalid("The selected export contains invalid backup data.");
            double asDouble = ((Number) value).doubleValue();
            if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)
                    || asDouble < -Float.MAX_VALUE || asDouble > Float.MAX_VALUE) {
                throw invalid("The selected export contains invalid backup data.");
            }
        }
    }

    private static void validateStringSetMap(JSONObject map)
            throws MigrationImportService.MigrationException {
        if (map == null) return;
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            Object value = map.opt(keys.next());
            if (value == null || value == JSONObject.NULL) continue;
            if (!(value instanceof JSONArray)) throw invalid("The selected export contains invalid backup data.");
            JSONArray values = (JSONArray) value;
            for (int index = 0; index < values.length(); index++) {
                if (!(values.opt(index) instanceof String)) {
                    throw invalid("The selected export contains invalid backup data.");
                }
            }
        }
    }

    private static JSONObject requiredObject(JSONObject object, String field)
            throws MigrationImportService.MigrationException {
        JSONObject value = object.optJSONObject(field);
        if (value == null) throw invalid("The selected export is missing required backup data.");
        return value;
    }

    private static JSONObject optionalObject(JSONObject object, String field, String label)
            throws MigrationImportService.MigrationException {
        Object raw = object.opt(field);
        if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof JSONObject)) throw invalid("The selected export contains invalid " + label + ".");
        return (JSONObject) raw;
    }

    private static String requiredString(JSONObject object, String field, int max)
            throws MigrationImportService.MigrationException {
        Object raw = object.opt(field);
        if (!(raw instanceof String)) throw invalid("The selected export contains invalid repository data.");
        String value = (String) raw;
        if (value.length() > max || !value.equals(value.trim())) {
            throw invalid("The selected export contains invalid repository data.");
        }
        return value;
    }

    private static void requireOptionalString(JSONObject object, String field, int max)
            throws MigrationImportService.MigrationException {
        Object raw = object.opt(field);
        if (raw == null || raw == JSONObject.NULL) return;
        if (!(raw instanceof String) || ((String) raw).length() > max) {
            throw invalid("The selected export contains invalid repository metadata.");
        }
    }

    private static void requireOnlyKeys(JSONObject object, String... allowed)
            throws MigrationImportService.MigrationException {
        Set<String> accepted = new HashSet<>();
        Collections.addAll(accepted, allowed);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (!accepted.contains(keys.next())) {
                throw invalid("The selected export contains unsupported backup fields.");
            }
        }
    }

    private static MigrationImportService.MigrationException invalid(String message) {
        return new MigrationImportService.MigrationException(message);
    }

    private static MigrationImportService.MigrationException invalid(String message, Throwable cause) {
        return new MigrationImportService.MigrationException(message, cause);
    }

    static final class Bundle {
        final List<RepositoryCandidate> repositories;

        Bundle(List<RepositoryCandidate> repositories) {
            this.repositories = Collections.unmodifiableList(new ArrayList<>(repositories));
        }
    }

    static final class RepositoryCandidate {
        final String url;

        RepositoryCandidate(String url) {
            this.url = url;
        }
    }
}
