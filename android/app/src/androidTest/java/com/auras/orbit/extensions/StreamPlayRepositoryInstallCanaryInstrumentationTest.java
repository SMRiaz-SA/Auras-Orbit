package com.auras.orbit.extensions;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** One-shot live check of the user's Phisher-to-StreamPlay package-install path. */
@RunWith(AndroidJUnit4.class)
public final class StreamPlayRepositoryInstallCanaryInstrumentationTest {
    private static final String AUTH_ARGUMENT = "orbitInstallStreamPlayFromPhisher";
    private static final String AUTH_VALUE = "authorize-one-shot";
    private static final String PHISHER_REPOSITORY_URL =
            "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json";

    @Test
    public void authorizedInstallCurrentStreamPlayFromPreservedPhisherRepository() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("This live package install requires the exact explicit runner argument.",
                AUTH_VALUE.equals(arguments.getString(AUTH_ARGUMENT)));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertEquals("the live install must use the primary debug app", "com.auras.orbit", context.getPackageName());
        CloudStreamExtensionManager manager = new CloudStreamExtensionManager(context);
        assertTrue("package recovery must finish before install", manager.isStorageRecoveryReady());
        assertNotNull("the standing Phisher repository must remain registered.",
                manager.findRepository(PHISHER_REPOSITORY_URL));

        manager.refreshRepository(PHISHER_REPOSITORY_URL);
        CloudStreamExtensionManager.PluginInfo streamPlay = findStreamPlay(
                manager.getCatalog(PHISHER_REPOSITORY_URL));
        assertNotNull("Phisher must publish the current StreamPlay package.", streamPlay);
        assertTrue("the current StreamPlay version is missing.", streamPlay.version > 0L);
        assertTrue("the package size is missing or invalid.", streamPlay.expectedSize > 0L);

        CloudStreamExtensionManager.InstalledPlugin installed = manager.getInstalledPlugin(streamPlay.id);
        if (installed == null || installed.version != streamPlay.version
                || streamPlay.expectedSha256 != null && !streamPlay.expectedSha256.equals(installed.sha256)) {
            installed = manager.installPlugin(streamPlay);
        }

        assertNotNull("the current StreamPlay package was not saved by the extension manager.", installed);
        assertEquals(streamPlay.id, installed.id);
        assertEquals(streamPlay.version, installed.version);
        File archive = new File(installed.filePath);
        assertTrue("the installed StreamPlay package is missing or has the wrong size.",
                archive.isFile() && archive.length() == streamPlay.expectedSize);
        assertEquals("installed metadata hash does not match the package bytes.",
                installed.sha256, sha256(archive));
        if (streamPlay.expectedSha256 != null) {
            assertEquals("downloaded package does not match Phisher's published hash.",
                    streamPlay.expectedSha256, installed.sha256);
            assertTrue("the package metadata did not retain its published hash.", installed.hashWasPublished);
        }
        Log.i("OrbitPluginInstallCanary", "PASS repository=Phisher plugin=StreamPlay version="
                + installed.version + " sha256=" + installed.sha256 + " path=" + installed.filePath);
    }

    private static CloudStreamExtensionManager.PluginInfo findStreamPlay(
            List<CloudStreamExtensionManager.PluginInfo> plugins) {
        for (CloudStreamExtensionManager.PluginInfo plugin : plugins) {
            if ("StreamPlay".equalsIgnoreCase(plugin.internalName)) return plugin;
        }
        return null;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}
