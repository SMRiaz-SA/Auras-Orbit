package com.auras.orbit.ui;

final class SettingsAccessibilityDescriptions {
    private SettingsAccessibilityDescriptions() {}

    static String formatAction(String title, String description, String actionLabel) {
        StringBuilder result = new StringBuilder();
        for (String part : new String[] {title, description, actionLabel}) {
            String normalized = part == null ? "" : part.trim();
            if (normalized.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(normalized);
            char last = normalized.charAt(normalized.length() - 1);
            if (last != '.' && last != '!' && last != '?') result.append('.');
        }
        return result.toString();
    }
}
