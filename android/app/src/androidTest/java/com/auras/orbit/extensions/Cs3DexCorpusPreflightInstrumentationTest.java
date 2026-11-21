package com.auras.orbit.extensions;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Scans the pinned CS3 corpus through DEX parsing only; archive classes are never loaded. */
@RunWith(AndroidJUnit4.class)
public final class Cs3DexCorpusPreflightInstrumentationTest {
    private static final int EXPECTED_ARCHIVE_COUNT = 19;
    private static final String EXPECTED_MANIFEST_SHA256 =
            "EA2550C8A1A2A499E011876C9452D931D16DEC3B992DDDE0E9A90135044C2310";
    private static final String CORPUS_ASSET_DIRECTORY = "cs3-corpus/";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_MANIFEST_LINE_CHARS = 4096;
    private static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_TOTAL_ARCHIVE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_TOTAL_OUTPUT_BYTES = 1024L * 1024L;
    private static final String[] RESULT_HEADER = {
            "archive_index", "dex_count", "dex_bytes", "class_count",
            "missing_host_types", "missing_host_methods", "missing_host_fields",
            "missing_external_types", "missing_external_methods", "missing_external_fields",
            "external_resolution_complete", "error_count", "corrupt_or_limit_errors",
            "no_dex_errors", "missing_manifest_or_plugin_errors", "plugin_base_mismatch_errors",
            "resource_base_mismatch_errors", "resolution_budget_errors",
            "unresolved_external_errors", "other_errors", "plugin_class_load_attempts"
    };
    private static final String[] UNRESOLVED_SYMBOL_HEADER = {
            "archive_index", "category", "symbol"
    };

    @Test
    public void scansPinnedNineteenArchiveCorpusWithoutLoadingPlugins() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        AssetManager assets = testContext.getAssets();
        List<CorpusEntry> entries = readManifest(assets);
        assertEquals("pinned corpus row count", EXPECTED_ARCHIVE_COUNT, entries.size());

        Set<String> pluginClassNames = new HashSet<>();
        for (CorpusEntry entry : entries) pluginClassNames.add(entry.pluginClassName);
        ClassLoader hostLoader = Cs3DexCapabilityPreflight.class.getClassLoader();
        assertTrue("Orbit host class loader is unavailable", hostLoader != null);
        BlockingPluginClassLoader scannerLoader =
                new BlockingPluginClassLoader(hostLoader, pluginClassNames);
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File cacheDirectory = targetContext.getCacheDir().getCanonicalFile();
        if (!cacheDirectory.isDirectory()) cacheDirectory.mkdirs();
        assertTrue("target app cache directory is unavailable; "
                        + cacheDiagnostic(testContext, targetContext, cacheDirectory),
                cacheDirectory.isDirectory());
        File results = new File(cacheDirectory, "cs3-corpus-preflight-results.tsv");
        assertEquals("result file escaped the target app cache directory", cacheDirectory,
                results.getCanonicalFile().getParentFile());
        File unresolvedSymbols = new File(cacheDirectory,
                "cs3-corpus-preflight-unresolved-symbols.tsv");
        assertEquals("unresolved-symbol file escaped the target app cache directory", cacheDirectory,
                unresolvedSymbols.getCanonicalFile().getParentFile());

        OutputBudget outputBudget = new OutputBudget(MAX_TOTAL_OUTPUT_BYTES);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new BoundedOutputStream(new FileOutputStream(results, false), outputBudget),
                StandardCharsets.UTF_8));
             BufferedWriter symbolWriter = new BufferedWriter(new OutputStreamWriter(
                     new BoundedOutputStream(new FileOutputStream(unresolvedSymbols, false),
                             outputBudget), StandardCharsets.UTF_8))) {
            writeTsvRow(writer, RESULT_HEADER);
            writeTsvRow(symbolWriter, UNRESOLVED_SYMBOL_HEADER);
            writer.flush();
            symbolWriter.flush();

            for (int index = 0; index < entries.size(); index++) {
                CorpusEntry entry = entries.get(index);
                int archiveIndex = index + 1;
                File archive = extractVerifiedArchive(assets, entry, cacheDirectory, archiveIndex);
                try {
                    int loadAttemptsBefore = scannerLoader.pluginLoadAttempts.get();
                    Cs3DexCapabilityPreflight.Result result = Cs3DexCapabilityPreflight.inspect(
                            archive, entry.pluginClassName, entry.requiresResources, scannerLoader);
                    int archiveLoadAttempts = scannerLoader.pluginLoadAttempts.get() - loadAttemptsBefore;
                    ErrorCategories categories = ErrorCategories.from(result.errors);

                    writeResultRow(writer, archiveIndex, result, categories, archiveLoadAttempts);
                    writeUnresolvedSymbolRows(symbolWriter, archiveIndex, result);
                    writer.flush();
                    symbolWriter.flush();

                    assertTrue("archive DEX parse was corrupt or exceeded parser limits at row "
                                    + archiveIndex + " (" + entry.fileName + "): " + result.errors,
                            categories.corruptOrLimit == 0);
                    assertTrue("archive has no DEX at row " + archiveIndex, result.dexCount > 0
                            && categories.noDex == 0);
                    assertTrue("manifest plugin class is absent at row " + archiveIndex,
                            categories.missingManifestOrPlugin == 0);
                    assertTrue("external resolution was incomplete or exceeded limits at row " + archiveIndex,
                            result.externalResolutionComplete && categories.resolutionBudget == 0);
                    assertTrue("unclassified preflight error category at row " + archiveIndex,
                            categories.other == 0);
                    assertEquals("preflight attempted to load a manifest plugin class at row " + archiveIndex,
                            0, archiveLoadAttempts);
                } finally {
                    assertTrue("temporary archive could not be removed from test cache",
                            !archive.exists() || archive.delete());
                }
            }
        }
        assertEquals("result file escaped the target app cache directory after writing",
                cacheDirectory, results.getCanonicalFile().getParentFile());
        assertEquals("unresolved-symbol file escaped the target app cache directory after writing",
                cacheDirectory, unresolvedSymbols.getCanonicalFile().getParentFile());
        assertTrue("combined corpus result output exceeded its bounded byte limit",
                outputBudget.writtenBytes <= MAX_TOTAL_OUTPUT_BYTES);
        assertEquals("bounded output byte count does not match the saved files",
                outputBudget.writtenBytes, results.length() + unresolvedSymbols.length());
    }

    private static List<CorpusEntry> readManifest(AssetManager assets) throws IOException {
        byte[] bytes = readBounded(assets, CORPUS_ASSET_DIRECTORY + "manifest.tsv", MAX_MANIFEST_BYTES);
        if (!EXPECTED_MANIFEST_SHA256.equals(sha256(bytes))) {
            throw new IOException("corpus manifest digest does not match the pinned artifact");
        }
        String contents = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = contents.split("\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) lineCount--;
        if (lineCount < 1 || lineCount > EXPECTED_ARCHIVE_COUNT + 1) {
            throw new IOException("corpus manifest line count is invalid");
        }
        for (int index = 0; index < lineCount; index++) {
            if (lines[index].length() > MAX_MANIFEST_LINE_CHARS) {
                throw new IOException("corpus manifest line exceeds the read limit");
            }
            if (lines[index].endsWith("\r")) lines[index] =
                    lines[index].substring(0, lines[index].length() - 1);
        }

        String[] header = lines[0].split("\t", -1);
        String[] requiredColumns = {"fileName", "bytes", "sha256", "pluginClassName", "requiresResources"};
        if (header.length < requiredColumns.length) throw new IOException("corpus manifest header is invalid");
        for (int index = 0; index < requiredColumns.length; index++) {
            if (!requiredColumns[index].equals(header[index])) {
                throw new IOException("corpus manifest header is invalid");
            }
        }

        ArrayList<CorpusEntry> result = new ArrayList<>();
        HashSet<String> fileNames = new HashSet<>();
        long totalBytes = 0;
        for (int lineIndex = 1; lineIndex < lineCount; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isEmpty()) throw new IOException("corpus manifest contains a blank row");
            String[] columns = line.split("\t", -1);
            if (columns.length < requiredColumns.length) {
                throw new IOException("corpus manifest row has too few columns");
            }
            String fileName = columns[0];
            if (fileName.length() == 0 || fileName.length() > 255 || fileName.contains("/")
                    || fileName.contains("\\") || ".".equals(fileName) || "..".equals(fileName)
                    || !fileName.endsWith(".cs3") || !fileNames.add(fileName)) {
                throw new IOException("corpus manifest archive name is invalid");
            }
            long archiveBytes;
            try { archiveBytes = Long.parseLong(columns[1]); }
            catch (NumberFormatException invalid) { throw new IOException("corpus manifest size is invalid"); }
            if (archiveBytes <= 0 || archiveBytes > MAX_ARCHIVE_BYTES
                    || totalBytes > MAX_TOTAL_ARCHIVE_BYTES - archiveBytes) {
                throw new IOException("corpus archive size exceeds the read limit");
            }
            totalBytes += archiveBytes;
            String sha256 = columns[2].toLowerCase(Locale.US);
            if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("corpus manifest digest is invalid");
            String pluginClassName = columns[3];
            if (!pluginClassName.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                throw new IOException("corpus manifest plugin class name is invalid");
            }
            boolean requiresResources;
            if ("true".equals(columns[4])) requiresResources = true;
            else if ("false".equals(columns[4])) requiresResources = false;
            else throw new IOException("corpus manifest resource flag is invalid");
            result.add(new CorpusEntry(fileName, archiveBytes, sha256,
                    pluginClassName, requiresResources));
        }
        return result;
    }

    private static byte[] readBounded(AssetManager assets, String assetName, int maximumBytes)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream input = assets.open(assetName, AssetManager.ACCESS_STREAMING)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() > maximumBytes - count) {
                    throw new IOException("input exceeds the bounded read size");
                }
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static File extractVerifiedArchive(AssetManager assets, CorpusEntry entry,
                                               File cacheDirectory, int archiveIndex)
            throws IOException {
        File archive = File.createTempFile(String.format(Locale.US, "cs3p%02d", archiveIndex),
                ".cs3", cacheDirectory);
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            archive.delete();
            throw new IOException("SHA-256 is unavailable");
        }
        byte[] buffer = new byte[8192];
        long totalBytes = 0;
        boolean verified = false;
        try {
            try (InputStream input = assets.open(CORPUS_ASSET_DIRECTORY + entry.fileName,
                    AssetManager.ACCESS_STREAMING);
                 FileOutputStream output = new FileOutputStream(archive, false)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (totalBytes > entry.bytes - count || totalBytes > MAX_ARCHIVE_BYTES - count) {
                        throw new IOException("archive asset exceeds its bounded size");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    totalBytes += count;
                }
            }
            if (totalBytes != entry.bytes || !entry.sha256.equals(hex(digest.digest()))) {
                throw new IOException("archive asset size or digest does not match the pinned manifest");
            }
            verified = true;
            return archive;
        } catch (IOException failure) {
            throw new IOException("pinned archive asset could not be extracted and verified");
        } finally {
            if (!verified) archive.delete();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 is unavailable"); }
        digest.update(bytes);
        return hex(digest.digest()).toUpperCase(Locale.US);
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(Locale.US, "%02x", value & 0xff));
        return hex.toString();
    }

    private static void writeResultRow(BufferedWriter writer, int archiveIndex,
                                       Cs3DexCapabilityPreflight.Result result,
                                       ErrorCategories categories, int loadAttempts) throws IOException {
        String[] values = {
                Integer.toString(archiveIndex), Integer.toString(result.dexCount),
                Integer.toString(result.dexBytes), Integer.toString(result.pluginDefinedTypeCount),
                Integer.toString(result.missingHostTypes.size()),
                Integer.toString(result.missingHostMethods.size()),
                Integer.toString(result.missingHostFields.size()),
                Integer.toString(result.missingExternalTypes.size()),
                Integer.toString(result.missingExternalMethods.size()),
                Integer.toString(result.missingExternalFields.size()),
                Boolean.toString(result.externalResolutionComplete),
                Integer.toString(result.errors.size()), Integer.toString(categories.corruptOrLimit),
                Integer.toString(categories.noDex),
                Integer.toString(categories.missingManifestOrPlugin),
                Integer.toString(categories.pluginBaseMismatch),
                Integer.toString(categories.resourceBaseMismatch),
                Integer.toString(categories.resolutionBudget),
                Integer.toString(categories.unresolvedExternal), Integer.toString(categories.other),
                Integer.toString(loadAttempts)
        };
        writeTsvRow(writer, values);
    }

    private static void writeUnresolvedSymbolRows(BufferedWriter writer, int archiveIndex,
                                                   Cs3DexCapabilityPreflight.Result result)
            throws IOException {
        writeSymbolRows(writer, archiveIndex, "missing_host_type", result.missingHostTypes);
        writeSymbolRows(writer, archiveIndex, "missing_host_method", result.missingHostMethods);
        writeSymbolRows(writer, archiveIndex, "missing_host_field", result.missingHostFields);
        writeSymbolRows(writer, archiveIndex, "missing_external_type", result.missingExternalTypes);
        writeSymbolRows(writer, archiveIndex, "missing_external_method", result.missingExternalMethods);
        writeSymbolRows(writer, archiveIndex, "missing_external_field", result.missingExternalFields);
    }

    private static void writeSymbolRows(BufferedWriter writer, int archiveIndex, String category,
                                        Iterable<String> symbols) throws IOException {
        for (String symbol : symbols) {
            writeTsvRow(writer, new String[] {Integer.toString(archiveIndex), category,
                    escapeTsvField(symbol)});
        }
    }

    /** Escapes ISO controls and UTF-16 surrogates so symbol rows remain exact TSV records. */
    private static String escapeTsvField(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') escaped.append("\\\\");
            else if (character == '\t') escaped.append("\\t");
            else if (character == '\r') escaped.append("\\r");
            else if (character == '\n') escaped.append("\\n");
            else if (Character.isISOControl(character)
                    || Character.isSurrogate(character)) {
                escaped.append("\\u");
                String hex = Integer.toHexString(character).toUpperCase(Locale.US);
                for (int pad = hex.length(); pad < 4; pad++) escaped.append('0');
                escaped.append(hex);
            } else escaped.append(character);
        }
        return escaped.toString();
    }

    private static final class OutputBudget {
        final long maximumBytes;
        long writtenBytes;

        OutputBudget(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        void consume(int count) throws IOException {
            if (count < 0 || writtenBytes > maximumBytes - count) {
                throw new IOException("corpus result output exceeded its bounded byte limit");
            }
            writtenBytes += count;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final OutputBudget budget;

        BoundedOutputStream(OutputStream delegate, OutputBudget budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override public void write(int value) throws IOException {
            budget.consume(1);
            delegate.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || length > bytes.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            budget.consume(length);
            delegate.write(bytes, offset, length);
        }

        @Override public void flush() throws IOException { delegate.flush(); }

        @Override public void close() throws IOException { delegate.close(); }
    }

    private static String cacheDiagnostic(Context testContext, Context targetContext,
                                          File cacheDirectory) {
        String type = cacheDirectory.isDirectory() ? "directory"
                : cacheDirectory.isFile() ? "file" : "other";
        return "targetPackage=" + targetContext.getPackageName()
                + " targetCache=" + canonicalPath(cacheDirectory)
                + " exists=" + cacheDirectory.exists()
                + " type=" + type
                + " canWrite=" + cacheDirectory.canWrite()
                + " instrumentationPackage=" + testContext.getPackageName();
    }

    private static String canonicalPath(File path) {
        try { return path.getCanonicalPath(); }
        catch (IOException ignored) { return path.getAbsolutePath(); }
    }

    private static void writeTsvRow(BufferedWriter writer, String[] values) throws IOException {
        for (int index = 0; index < values.length; index++) {
            if (index != 0) writer.write('\t');
            writer.write(values[index]);
        }
        writer.newLine();
    }

    private static final class CorpusEntry {
        final String fileName;
        final long bytes;
        final String sha256;
        final String pluginClassName;
        final boolean requiresResources;

        CorpusEntry(String fileName, long bytes, String sha256,
                    String pluginClassName, boolean requiresResources) {
            this.fileName = fileName;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.pluginClassName = pluginClassName;
            this.requiresResources = requiresResources;
        }
    }

    private static final class BlockingPluginClassLoader extends ClassLoader {
        final Set<String> pluginClassNames;
        final AtomicInteger pluginLoadAttempts = new AtomicInteger();

        BlockingPluginClassLoader(ClassLoader parent, Set<String> pluginClassNames) {
            super(parent);
            this.pluginClassNames = pluginClassNames;
        }

        @Override protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (pluginClassNames.contains(name)) {
                pluginLoadAttempts.incrementAndGet();
                throw new ClassNotFoundException("archive class loading blocked");
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class ErrorCategories {
        int corruptOrLimit;
        int noDex;
        int missingManifestOrPlugin;
        int pluginBaseMismatch;
        int resourceBaseMismatch;
        int resolutionBudget;
        int unresolvedExternal;
        int other;

        static ErrorCategories from(List<String> errors) {
            ErrorCategories result = new ErrorCategories();
            for (String error : errors) {
                if (error.startsWith("archive DEX inspection failed")) result.corruptOrLimit++;
                else if (error.equals("archive contains no classes*.dex entry")) result.noDex++;
                else if (error.equals("manifest plugin class name is missing")
                        || error.equals("manifest plugin class is absent from DEX")) {
                    result.missingManifestOrPlugin++;
                } else if (error.equals("manifest plugin class does not extend Orbit BasePlugin in its DEX hierarchy")) {
                    result.pluginBaseMismatch++;
                } else if (error.equals("resource-bearing manifest requires the Android Plugin base class")) {
                    result.resourceBaseMismatch++;
                } else if (error.startsWith("external symbol resolution exceeded")) {
                    result.resolutionBudget++;
                } else if (error.startsWith("unresolved external dependencies:")) {
                    result.unresolvedExternal++;
                } else result.other++;
            }
            return result;
        }
    }
}
