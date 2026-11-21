package com.auras.orbit.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SettingsAccessibilityDescriptionTest {
    @Test
    public void actionDescriptionDoesNotDuplicateSentencePunctuation() {
        assertEquals(
                "Active profile. Active profile on this device. Switching profiles reloads that profile's "
                        + "settings and extensions. Change.",
                SettingsAccessibilityDescriptions.formatAction(
                        "Active profile",
                        "Active profile on this device. Switching profiles reloads that profile's settings "
                                + "and extensions.",
                        "Change"));
    }

    @Test
    public void actionDescriptionTrimsAndTerminatesEachPartOnce() {
        assertEquals("Download folder. Choose or change the folder. View.",
                SettingsAccessibilityDescriptions.formatAction(
                        " Download folder ", "Choose or change the folder. ", " View "));
    }
}
