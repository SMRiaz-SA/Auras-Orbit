package com.auras.orbit.extensions;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Temporary exact-symbol diagnostic for the installed, published YTS CS3 package. */
@RunWith(AndroidJUnit4.class)
public final class YtsPreflightDiagnosticTest {
    @Test
    public void reportsTheExactMissingExternalMethod() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = new File(context.getCacheDir(), "yts-preflight.cs3");
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(
                        "run-as com.auras.orbit.cs3acceptance cat files/cloudstream/plugins/"
                                + "plugin-7c9e69e41f1f6f67c537.cs3");
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             FileOutputStream output = new FileOutputStream(archive)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        assertTrue("Installed YTS archive must be readable", archive.isFile() && archive.length() > 0);
        Cs3DexCapabilityPreflight.Result result = Cs3DexCapabilityPreflight.inspect(
                archive, "com.yts.cs3.YtsPlugin", false, context.getClassLoader());
        Log.i("YTS_PREFLIGHT", "missingExternalMethods=" + result.missingExternalMethods);
        Log.i("YTS_PREFLIGHT", "missingExternalTypes=" + result.missingExternalTypes);
        Class<?> serializer = Class.forName("kotlinx.serialization.internal.GeneratedSerializer",
                false, context.getClassLoader());
        StringBuilder methods = new StringBuilder();
        for (Method method : serializer.getDeclaredMethods()) methods.append(method.toString()).append(" | ");
        Log.i("YTS_PREFLIGHT", "runtimeSerializerMethods=" + methods);
        assertFalse("The package should reproduce the reported missing method",
                result.missingExternalMethods.isEmpty());
    }
}
