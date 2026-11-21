package com.auras.orbit.player;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Bounded subtitle format detection for selected provider tracks. */
public final class SubtitleContentSniffer {
    private static final int MAX_PREFIX_BYTES = 2048;
    private static final String MIME_VTT = "text/vtt";
    private static final String MIME_SUBRIP = "application/x-subrip";
    private static final String MIME_TTML = "application/ttml+xml";
    private static final String MIME_SSA = "text/x-ssa";
    private static final Pattern VTT_HEADER = Pattern.compile(
            "^WEBVTT(?:[ \\t].*|\\r?\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TTML_ROOT = Pattern.compile(
            "^(?:<\\?xml\\b[^?]*\\?>\\s*)?(?:<!--.*?-->\\s*)*"
                    + "<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?tt(?:\\s|>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SSA_SCRIPT_INFO = Pattern.compile(
            "(?im)^\\s*\\[Script Info\\]\\s*$");
    private static final Pattern SSA_EVENTS = Pattern.compile("(?im)^\\s*\\[Events\\]\\s*$");
    private static final Pattern SUBRIP_TIMING = Pattern.compile(
            "(?:(?:\\d{1,3}:)?\\d{2}:\\d{2}[,.]\\d{1,3})\\s+-->\\s+"
                    + "(?:(?:\\d{1,3}:)?\\d{2}:\\d{2}[,.]\\d{1,3})(?:\\s+.*)?");

    private SubtitleContentSniffer() { }

    public static boolean shouldUseSubripFallback(String sampleMimeType, String codecs,
            byte[] data, int offset, int length) {
        return declaresWebvtt(sampleMimeType, codecs)
                && MIME_SUBRIP.equals(sniffMimeType(data, offset, length));
    }

    public static boolean declaresWebvtt(String sampleMimeType, String codecs) {
        if (MIME_VTT.equalsIgnoreCase(sampleMimeType)) return true;
        if (codecs == null) return false;
        for (String codec : codecs.split(",")) {
            if (MIME_VTT.equalsIgnoreCase(codec.trim())) return true;
        }
        return false;
    }

    /** Returns the MIME type identified in at most the first 2,048 bytes, or null if unclear. */
    public static String sniffMimeType(byte[] data, int offset, int length) {
        if (!isValidSlice(data, offset, length)) return null;
        String prefix = normalizedPrefix(data, offset, length);
        if (prefix.isEmpty()) return null;
        if (VTT_HEADER.matcher(prefix).find()) return MIME_VTT;
        if (TTML_ROOT.matcher(prefix).find()) return MIME_TTML;
        if (SSA_SCRIPT_INFO.matcher(prefix).find() && SSA_EVENTS.matcher(prefix).find()) {
            return MIME_SSA;
        }
        if (looksLikeSubrip(prefix)) return MIME_SUBRIP;
        return null;
    }

    private static boolean looksLikeSubrip(byte[] data, int offset, int length) {
        if (!isValidSlice(data, offset, length)) return false;
        return looksLikeSubrip(normalizedPrefix(data, offset, length));
    }

    private static boolean looksLikeSubrip(String normalized) {
        if (normalized.isEmpty()) return false;
        if (VTT_HEADER.matcher(normalized).find()) return false;
        String[] lines = normalized.split("\\r\\n|\\n|\\r", -1);
        String indexLine = null;
        int timingIndex = -1;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isEmpty()) continue;
            if (indexLine == null) {
                indexLine = line;
                if (line.length() > 9 || !line.matches("[0-9]+")) return false;
            } else {
                timingIndex = lineIndex;
                if (!SUBRIP_TIMING.matcher(line).matches()) return false;
                break;
            }
        }
        return indexLine != null && timingIndex >= 0;
    }

    private static boolean isValidSlice(byte[] data, int offset, int length) {
        return data != null && offset >= 0 && length > 0
                && (long) offset + (long) length <= data.length;
    }

    private static String normalizedPrefix(byte[] data, int offset, int length) {
        String prefix = new String(data, offset, Math.min(length, MAX_PREFIX_BYTES),
                StandardCharsets.UTF_8);
        int start = 0;
        while (start < prefix.length()) {
            char ch = prefix.charAt(start);
            int type = Character.getType(ch);
            if (ch == '\uFEFF' || Character.isWhitespace(ch) || Character.isISOControl(ch)
                    || type == Character.FORMAT) {
                start++;
            } else {
                break;
            }
        }
        return prefix.substring(start);
    }
}
