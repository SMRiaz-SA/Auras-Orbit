package com.auras.orbit.intent;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** Provisional, non-executing envelope for incoming Android handoffs. */
public final class IncomingRequest {
    private static final int MAX_ITEMS = 8;
    private static final int MAX_EXTRA_NODES = 32;
    private static final int MAX_VALUE_LENGTH = RequestValidation.MAX_VALUE_LENGTH;
    private static final String TRUNCATED_VALUE = RequestValidation.TRUNCATED_VALUE;

    private final String action;
    private final String mimeType;
    private final ArrayList<String> rawValues;

    private IncomingRequest(String action, String mimeType, List<String> rawValues) {
        this.action = boundedMetadata(action);
        this.mimeType = boundedMetadata(mimeType);
        this.rawValues = new ArrayList<>();
        if (rawValues != null) {
            for (String value : rawValues) addValue(this.rawValues, value);
        }
    }

    public static IncomingRequest from(Intent intent) {
        if (intent == null) return null;

        ArrayList<String> values = new ArrayList<>();
        try {
            addUri(values, intent.getData());

            CharSequence sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            addText(values, sharedText);

            if (intent.getExtras() != null) {
                addStreamValues(values, readStreamExtra(intent), 0, new int[1]);
            }

            ClipData clipData = intent.getClipData();
            if (clipData != null) {
                int count = Math.min(clipData.getItemCount(), MAX_ITEMS);
                for (int index = 0; index < count; index++) {
                    ClipData.Item item = clipData.getItemAt(index);
                    addUri(values, item.getUri());
                    addText(values, item.getText());
                }
                if (clipData.getItemCount() > count) addValue(values, TRUNCATED_VALUE);
            }
        } catch (RuntimeException malformedPayload) {
            // Keep already-collected bounded data; malformed parcelables are untrusted input.
            addValue(values, TRUNCATED_VALUE);
        }

        if (values.isEmpty()) return null;
        return new IncomingRequest(intent.getAction(), intent.getType(), values);
    }

    private static Object readStreamExtra(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                if (single != null) return single;
                ArrayList<Uri> multiple = intent.getParcelableArrayListExtra(
                        Intent.EXTRA_STREAM, Uri.class);
                if (multiple != null) return multiple;
                Uri[] array = intent.getParcelableArrayExtra(Intent.EXTRA_STREAM, Uri.class);
                if (array != null) return array;
            } catch (RuntimeException malformedTypedExtra) {
                // Fall through to the bounded legacy reader for unusual parcelable containers.
            }
        }
        return readLegacyStreamExtra(extras);
    }

    /** Retains uncommon nested/object-array EXTRA_STREAM forms on older and newer Android. */
    @SuppressWarnings("deprecation")
    private static Object readLegacyStreamExtra(Bundle extras) {
        return extras.get(Intent.EXTRA_STREAM);
    }

    public static IncomingRequest restore(String action, String mimeType, List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) return null;
        return new IncomingRequest(action, mimeType, rawValues);
    }

    private static void addStreamValues(
            ArrayList<String> values, Object streamValue, int depth, int[] visitedNodes) {
        if (values.size() >= MAX_ITEMS || depth > 4 || visitedNodes[0] >= MAX_EXTRA_NODES) {
            addValue(values, TRUNCATED_VALUE);
            return;
        }
        visitedNodes[0]++;
        if (streamValue instanceof Uri) {
            addUri(values, (Uri) streamValue);
        } else if (streamValue instanceof Iterable<?>) {
            for (Object value : (Iterable<?>) streamValue) {
                if (values.size() >= MAX_ITEMS || visitedNodes[0] >= MAX_EXTRA_NODES) {
                    addValue(values, TRUNCATED_VALUE);
                    return;
                }
                addStreamValues(values, value, depth + 1, visitedNodes);
            }
        } else if (streamValue instanceof Object[]) {
            for (Object value : (Object[]) streamValue) {
                if (values.size() >= MAX_ITEMS || visitedNodes[0] >= MAX_EXTRA_NODES) {
                    addValue(values, TRUNCATED_VALUE);
                    return;
                }
                addStreamValues(values, value, depth + 1, visitedNodes);
            }
        }
    }

    private static void addUri(ArrayList<String> values, Uri uri) {
        if (uri != null) addValue(values, uri.toString());
    }

    private static void addText(ArrayList<String> values, CharSequence text) {
        if (text == null || text.length() == 0) return;
        if (text.length() > MAX_VALUE_LENGTH) {
            addValue(values, TRUNCATED_VALUE);
        } else {
            addValue(values, text.toString());
        }
    }

    private static void addValue(ArrayList<String> values, String value) {
        if (value == null || value.isEmpty()) return;
        if (values.size() < MAX_ITEMS) {
            values.add(value.length() > MAX_VALUE_LENGTH ? TRUNCATED_VALUE : value);
        } else if (!TRUNCATED_VALUE.equals(values.get(MAX_ITEMS - 1))) {
            // Reserve one bounded marker so the parser can report an omitted tail safely.
            values.set(MAX_ITEMS - 1, TRUNCATED_VALUE);
        }
    }

    private static String boundedMetadata(String value) {
        if (value == null) return null;
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    public String getAction() {
        return action;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getRawData() {
        // Kept for the existing shell display call site; never expose raw URIs or shared text.
        return validate().getSafeDisplayText();
    }

    public ArrayList<String> getRawValues() {
        return new ArrayList<>(rawValues);
    }

    public RequestValidation validate() {
        return RequestValidation.parse(action, mimeType, rawValues);
    }

    public String getKindLabel() {
        if (Intent.ACTION_VIEW.equals(action)) return "Incoming link";
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) return "Shared content";
        if (mimeType != null) return "Shared content";
        return "Shared request";
    }
}
