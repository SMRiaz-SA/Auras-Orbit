package com.auras.orbit.intent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.auras.orbit.BuildConfig;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only, local content source that records any metadata query, type lookup, or open/read. */
public final class IncomingRequestReadTrackingProvider extends ContentProvider {
    public static final String AUTHORITY =
            BuildConfig.APPLICATION_ID + ".test.incomingrequest.readtracking";
    private static final String METHOD_RESET = "reset_read_counts";
    private static final String METHOD_READ_COUNTS = "get_read_counts";
    static final String COUNT_QUERY = "query_count";
    static final String COUNT_TYPE = "type_count";
    static final String COUNT_OPEN = "open_count";

    private static final AtomicInteger QUERY_COUNT = new AtomicInteger();
    private static final AtomicInteger TYPE_COUNT = new AtomicInteger();
    private static final AtomicInteger OPEN_COUNT = new AtomicInteger();

    public static Uri contentUri(String fixturePath) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(fixturePath));
    }

    public static void resetReadCounts(Context instrumentationContext) {
        Bundle result = instrumentationContext.getContentResolver().call(
                AUTHORITY, METHOD_RESET, null, null);
        if (result == null || !result.getBoolean("ok")) {
            throw new AssertionError("Could not reset incoming content read counters");
        }
    }

    public static Bundle readCounts(Context instrumentationContext) {
        Bundle result = instrumentationContext.getContentResolver().call(
                AUTHORITY, METHOD_READ_COUNTS, null, null);
        if (result == null) throw new AssertionError("Could not read incoming content counters");
        return result;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceOrbitCaller();
        if (METHOD_RESET.equals(method)) {
            QUERY_COUNT.set(0);
            TYPE_COUNT.set(0);
            OPEN_COUNT.set(0);
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            return result;
        }
        if (!METHOD_READ_COUNTS.equals(method)) return super.call(method, arg, extras);
        Bundle result = new Bundle();
        result.putInt(COUNT_QUERY, QUERY_COUNT.get());
        result.putInt(COUNT_TYPE, TYPE_COUNT.get());
        result.putInt(COUNT_OPEN, OPEN_COUNT.get());
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        enforceOrbitCaller();
        QUERY_COUNT.incrementAndGet();
        MatrixCursor result = new MatrixCursor(new String[] {
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        });
        result.addRow(new Object[] {"orbit-incoming-fixture.orbitlib", 7L});
        return result;
    }

    @Override public String getType(Uri uri) {
        enforceOrbitCaller();
        TYPE_COUNT.incrementAndGet();
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceOrbitCaller();
        OPEN_COUNT.incrementAndGet();
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixture is read only");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> writeFixture(pipe[1]),
                    "incoming-request-content-fixture");
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Could not open in-memory fixture");
            failure.initCause(error);
            throw failure;
        }
    }

    private static void writeFixture(ParcelFileDescriptor descriptor) {
        try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            output.write("local-in-memory-fixture".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The test only expects this provider to remain unopened.
        } finally {
            try { descriptor.close(); }
            catch (IOException ignored) { }
        }
    }

    private void enforceOrbitCaller() {
        Context context = getContext();
        PackageManager packageManager = context == null ? null : context.getPackageManager();
        String[] packages = packageManager == null
                ? null : packageManager.getPackagesForUid(Binder.getCallingUid());
        if (packages != null) {
            for (String packageName : packages) {
                if (BuildConfig.APPLICATION_ID.equals(packageName)) return;
            }
        }
        throw new SecurityException("Only the Orbit package may access this test content fixture.");
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        enforceOrbitCaller();
        throw new UnsupportedOperationException("Fixture is read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceOrbitCaller();
        throw new UnsupportedOperationException("Fixture is read only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        enforceOrbitCaller();
        throw new UnsupportedOperationException("Fixture is read only");
    }
}
