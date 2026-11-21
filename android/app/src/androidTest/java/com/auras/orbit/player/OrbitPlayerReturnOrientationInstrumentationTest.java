package com.auras.orbit.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.ui.OrbitShellActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for restoring the phone shell orientation after playback. */
@RunWith(AndroidJUnit4.class)
public final class OrbitPlayerReturnOrientationInstrumentationTest {
    @Test public void backFromPhonePlayerRestoresShellOrientation() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor playerMonitor = instrumentation.addMonitor(
                OrbitPlayerActivity.class.getName(), null, false);
        Intent shellIntent = new Intent(instrumentation.getTargetContext(), OrbitShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        OrbitShellActivity shell = (OrbitShellActivity) instrumentation.startActivitySync(shellIntent);
        Activity player = null;
        try {
            instrumentation.runOnMainSync(() -> shell.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            awaitOrientation(shell, Configuration.ORIENTATION_PORTRAIT);
            instrumentation.runOnMainSync(() -> shell.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
            awaitOrientation(shell, Configuration.ORIENTATION_PORTRAIT);
            assertEquals("the shell remains sensor-managed before playback",
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, shell.getRequestedOrientation());

            instrumentation.runOnMainSync(() -> shell.startActivity(
                    new Intent(shell, OrbitPlayerActivity.class)));
            player = instrumentation.waitForMonitorWithTimeout(playerMonitor, 5000L);
            assertNotNull("phone player opens", player);
            assertEquals("player enters landscape for playback",
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                    player.getRequestedOrientation());

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitShellPortrait(shell);
            assertEquals("the shell returns in portrait",
                    Configuration.ORIENTATION_PORTRAIT,
                    shell.getResources().getConfiguration().orientation);
        } finally {
            instrumentation.removeMonitor(playerMonitor);
            if (player != null && !player.isFinishing()) {
                instrumentation.runOnMainSync(player::finish);
            }
            instrumentation.runOnMainSync(shell::finish);
        }
    }

    private static void awaitOrientation(OrbitShellActivity shell, int orientation) {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (shell.getResources().getConfiguration().orientation == orientation) return;
            SystemClock.sleep(50L);
        }
        assertTrue("device reaches requested orientation " + orientation,
                shell.getResources().getConfiguration().orientation == orientation);
    }

    private static void awaitShellPortrait(OrbitShellActivity shell) {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (shell.hasWindowFocus()
                    && shell.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                return;
            }
            SystemClock.sleep(50L);
        }
        assertTrue("portrait shell resumes after player exits", shell.hasWindowFocus()
                && shell.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
    }
}
