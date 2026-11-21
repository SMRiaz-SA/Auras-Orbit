package com.auras.orbit.extensions;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.auras.orbit.OrbitApplication;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Source regressions for package-transfer cancellation and failure-safe repository removal. */
@RunWith(AndroidJUnit4.class)
public final class ExtensionOperationSafetyInstrumentationTest {
    private CloudStreamExtensionManager manager;
    private String repositoryUrl;
    private File blockedCatalogPath;
    private BlockingPackageServer packageServer;
    private ExecutorService operationExecutor;

    @After
    public void cleanup() throws Exception {
        if (packageServer != null) packageServer.close();
        if (operationExecutor != null) {
            operationExecutor.shutdownNow();
            operationExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (blockedCatalogPath != null) deleteTree(blockedCatalogPath);
        if (manager != null && repositoryUrl != null) manager.removeRepository(repositoryUrl);
    }

    @Test
    public void failedCatalogDeletionKeepsRepositoryRegisteredAndRemovalCanRetry() throws Exception {
        Context context = targetContext();
        manager = app(context).getCloudStreamExtensionManager();
        assertTrue(manager.isStorageRecoveryReady());
        repositoryUrl = "https://remove-fixture.example.invalid/" + UUID.randomUUID();
        manager.addRepositoryMetadata("", "Removal fixture", repositoryUrl, "");

        File cacheDirectory = new File(context.getFilesDir(), "cloudstream/repositories");
        assertTrue(cacheDirectory.mkdirs() || cacheDirectory.isDirectory());
        blockedCatalogPath = new File(cacheDirectory, sha256(repositoryUrl) + ".json");
        assertTrue(blockedCatalogPath.mkdir());
        File child = new File(blockedCatalogPath, "prevent-delete");
        assertTrue(child.createNewFile());

        assertFalse("cache deletion failure must not report failure after unregistering",
                manager.removeRepository(repositoryUrl));
        assertNotNull("the repository remains registered so removal can be retried",
                manager.findRepository(repositoryUrl));

        assertTrue(child.delete());
        assertTrue(blockedCatalogPath.delete());
        assertTrue("retry removes the still-registered repository", manager.removeRepository(repositoryUrl));
        assertNull(manager.findRepository(repositoryUrl));
        blockedCatalogPath = null;
    }

    @Test
    public void cancelingBlockedPackageTransferDisconnectsAndRemovesStagingBeforeCanceledState() throws Exception {
        Context context = targetContext();
        OrbitApplication application = app(context);
        manager = application.getCloudStreamExtensionManager();
        assertTrue(manager.isStorageRecoveryReady());
        packageServer = new BlockingPackageServer();
        repositoryUrl = "http://127.0.0.1:" + packageServer.getPort() + "/repo/" + UUID.randomUUID();
        manager.addRepositoryMetadata("", "Cancellation fixture", repositoryUrl, "");

        String pluginId = repositoryUrl + "|cancel-fixture";
        CloudStreamExtensionManager.PluginInfo plugin = new CloudStreamExtensionManager.PluginInfo(
                pluginId, repositoryUrl, "Cancellation fixture", "CancelFixture",
                packageServer.getPluginUrl(), "", "", "", null, 1L,
                BlockingPackageServer.BODY_LENGTH, 1);
        File pluginDirectory = new File(context.getFilesDir(), "cloudstream/plugins");
        Set<String> stagingBefore = stagingFiles(pluginDirectory);

        operationExecutor = Executors.newSingleThreadExecutor();
        ExtensionOperationController controller = new ExtensionOperationController(
                operationExecutor, manager, application.getCS3PluginRuntime(),
                application.getLocalProfileStore());
        assertTrue(controller.submit("plugin-install:" + pluginId, "Downloading fixture…",
                controller.installPluginOperation(plugin)));
        assertTrue("the local server began sending the package body",
                packageServer.awaitBodyStarted(8, TimeUnit.SECONDS));

        ExtensionOperationController.Snapshot downloading = awaitPhase(controller,
                ExtensionOperationController.Phase.DOWNLOADING, 5_000L);
        assertNotNull(downloading);
        assertTrue(downloading.canCancel());
        assertTrue(controller.cancel(downloading.id));

        ExtensionOperationController.Snapshot canceled = awaitStatus(controller,
                ExtensionOperationController.Status.CANCELED, 8_000L);
        assertNotNull("cancellation is reported only after the worker returns", canceled);
        assertFalse(canceled.isRunning());
        assertEquals(stagingBefore, stagingFiles(pluginDirectory));
        assertNull(manager.getInstalledPlugin(pluginId));
        assertFalse(context.getSharedPreferences("orbit_cloudstream_extensions_v1", Context.MODE_PRIVATE)
                .contains("plugin_install_transaction_json"));
    }

    private static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static OrbitApplication app(Context context) {
        return (OrbitApplication) context.getApplicationContext();
    }

    private static Set<String> stagingFiles(File directory) {
        HashSet<String> result = new HashSet<>();
        File[] files = directory.listFiles();
        if (files == null) return result;
        for (File file : files) if (file.getName().endsWith(".staging")) result.add(file.getName());
        return result;
    }

    private static ExtensionOperationController.Snapshot awaitPhase(
            ExtensionOperationController controller, ExtensionOperationController.Phase phase,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            ExtensionOperationController.Snapshot snapshot = controller.getSnapshot();
            if (snapshot.phase == phase) return snapshot;
            Thread.sleep(20L);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private static ExtensionOperationController.Snapshot awaitStatus(
            ExtensionOperationController controller, ExtensionOperationController.Status status,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            ExtensionOperationController.Snapshot snapshot = controller.getSnapshot();
            if (snapshot.status == status) return snapshot;
            Thread.sleep(20L);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte item : digest) output.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return output.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static final class BlockingPackageServer implements AutoCloseable {
        private static final int BODY_LENGTH = 1_048_576;
        private final ServerSocket server;
        private final CountDownLatch bodyStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread worker;

        BlockingPackageServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "orbit-blocked-package-fixture");
            worker.setDaemon(true);
            worker.start();
        }

        int getPort() { return server.getLocalPort(); }

        String getPluginUrl() { return "http://127.0.0.1:" + getPort() + "/plugin.cs3"; }

        boolean awaitBodyStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return bodyStarted.await(timeout, unit);
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(15_000);
                BufferedReader request = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = request.readLine()) != null && !line.isEmpty()) { }
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + BODY_LENGTH
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(new byte[32_768]);
                output.flush();
                bodyStarted.countDown();
                release.await(20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The manager intentionally disconnects this socket when cancellation is requested.
            }
        }

        @Override public void close() throws Exception {
            release.countDown();
            server.close();
            worker.join(5_000L);
        }
    }
}
