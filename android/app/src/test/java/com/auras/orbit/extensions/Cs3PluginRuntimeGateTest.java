package com.auras.orbit.extensions;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class Cs3PluginRuntimeGateTest {
    @Test
    public void ordinaryActivationRequiresValidationOutsideAcceptanceBuild() {
        assertFalse(CS3PluginRuntime.ordinaryPluginRuntimeEnabled(false, false));
        assertTrue(CS3PluginRuntime.ordinaryPluginRuntimeEnabled(false, true));
        assertTrue(CS3PluginRuntime.ordinaryPluginRuntimeEnabled(true, false));
        assertTrue(CS3PluginRuntime.ordinaryPluginRuntimeEnabled(true, true));
    }

    @Test
    public void normalizationFailureDoesNotReturnOriginalArchiveForActivation() throws Exception {
        File invalidArchive = File.createTempFile("cs3-normalization-fail-closed-", ".cs3");
        try {
            CS3PluginRuntime.ActivationPreparation result =
                    CS3PluginRuntime.prepareArchiveForActivation(
                            null, invalidArchive, getClass().getClassLoader());

            assertFalse(result.isPrepared());
            assertNull(result.codeArchive);
            assertNotNull(result.failure);
        } finally {
            assertTrue("temporary invalid archive should be removable", invalidArchive.delete());
        }
    }
}
