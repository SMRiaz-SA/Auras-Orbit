package com.auras.orbit.extensions;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import dalvik.system.PathClassLoader;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Verifies the normalized old-owner call resolves and cancels a host Job on API 23. */
@RunWith(AndroidJUnit4.class)
public final class Cs3JobDefaultImplsResolutionInstrumentationTest {
    private static final String TAG = "Cs3JobBridgeApi23Test";
    private static final String FIXTURE_TYPE = "Lfixture/JobCancelDefaultOwnerFixture;";
    private static final String FIXTURE_CLASS = "fixture.JobCancelDefaultOwnerFixture";
    private static final String JOB_OWNER = "Lkotlinx/coroutines/Job;";
    private static final String OLD_OWNER_CANCEL_DEFAULT = "cancel$default";

    @Test
    public void normalizedOldOwnerCallCancelsJobOnApi23() throws Exception {
        int sdk = Build.VERSION.SDK_INT;
        Log.i(TAG, "API 23 bridge resolution test device SDK=" + sdk);
        assumeTrue("This linkage gate must run on API 23", sdk == Build.VERSION_CODES.M);

        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File syntheticArchive = null;
        try {
            byte[] oldOwnerDex = createSyntheticApi23Dex();
            Cs3JobDefaultImplsNormalizer.NormalizedDex normalized =
                    Cs3JobDefaultImplsNormalizer.normalizeDexBytes(
                            oldOwnerDex, targetContext.getClassLoader());
            assertEquals("fixture must contain exactly one rewritten old-owner call",
                    1, normalized.rewrittenReferences);

            syntheticArchive = File.createTempFile(
                    "cs3-job-defaultimpls-api23-", ".zip", targetContext.getCacheDir());
            writeDexOnlyArchive(syntheticArchive, normalized.bytes);
            assertTrue("synthetic DEX archive must be read-only before class loading",
                    syntheticArchive.setReadOnly());

            PathClassLoader fixtureLoader = new PathClassLoader(
                    syntheticArchive.getAbsolutePath(), targetContext.getClassLoader());
            Class<?> fixtureClass = Class.forName(FIXTURE_CLASS, true, fixtureLoader);
            Method invokeBridge = fixtureClass.getMethod("cancel", Job.class);
            CompletableJob job = JobKt.Job((Job) null);

            invokeBridge.invoke(null, job);

            assertTrue("normalized DefaultImpls call must cancel the host Job", job.isCancelled());
            assertFalse("cancelled host Job must no longer be active", job.isActive());
        } finally {
            if (syntheticArchive != null && syntheticArchive.exists()) {
                assertTrue("could not delete the exact synthetic DEX ZIP", syntheticArchive.delete());
            }
        }
    }

    @Test
    public void normalizedOldOwnerCallCancelsJobThroughAndroidDesugarBridgeOnApi36() throws Exception {
        int sdk = Build.VERSION.SDK_INT;
        Log.i("Cs3JobDesugarBridgeApi36Test", "Android desugar bridge test device SDK=" + sdk);
        assumeTrue("This linkage gate must run on API 36", sdk == 36);

        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File syntheticArchive = null;
        try {
            byte[] oldOwnerDex = createSyntheticApi23Dex();
            Cs3JobDefaultImplsNormalizer.NormalizedDex normalized =
                    Cs3JobDefaultImplsNormalizer.normalizeDexBytes(
                            oldOwnerDex, targetContext.getClassLoader());
            assertEquals("fixture must contain exactly one rewritten old-owner call",
                    1, normalized.rewrittenReferences);

            syntheticArchive = File.createTempFile(
                    "cs3-job-desugar-bridge-api36-", ".zip", targetContext.getCacheDir());
            writeDexOnlyArchive(syntheticArchive, normalized.bytes);
            assertTrue("synthetic DEX archive must be read-only before class loading",
                    syntheticArchive.setReadOnly());

            PathClassLoader fixtureLoader = new PathClassLoader(
                    syntheticArchive.getAbsolutePath(), targetContext.getClassLoader());
            Class<?> fixtureClass = Class.forName(FIXTURE_CLASS, true, fixtureLoader);
            Method invokeBridge = fixtureClass.getMethod("cancel", Job.class);
            CompletableJob job = JobKt.Job((Job) null);

            invokeBridge.invoke(null, job);

            assertTrue("normalized call must cancel the host Job through the desugared bridge",
                    job.isCancelled());
            assertFalse("cancelled host Job must no longer be active", job.isActive());
        } finally {
            if (syntheticArchive != null && syntheticArchive.exists()) {
                assertTrue("could not delete the exact synthetic DEX ZIP", syntheticArchive.delete());
            }
        }
    }

    private static byte[] createSyntheticApi23Dex() throws Exception {
        ImmutableMethodReference oldOwnerCall = new ImmutableMethodReference(
                JOB_OWNER,
                OLD_OWNER_CANCEL_DEFAULT,
                Arrays.asList(
                        JOB_OWNER,
                        "Ljava/util/concurrent/CancellationException;",
                        "I",
                        "Ljava/lang/Object;"),
                "V");
        ImmutableMethodImplementation implementation = new ImmutableMethodImplementation(
                5,
                Arrays.asList(
                        new ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 4),
                        new ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                        new ImmutableInstruction11n(Opcode.CONST_4, 2, 1),
                        new ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
                        new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4,
                                0, 1, 2, 3, 0, oldOwnerCall),
                        new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                Collections.emptyList(),
                Collections.emptyList());
        ImmutableMethod method = new ImmutableMethod(
                FIXTURE_TYPE,
                "cancel",
                Collections.singletonList(new ImmutableMethodParameter(
                        JOB_OWNER, Collections.emptySet(), "job")),
                "V",
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(),
                Collections.emptySet(),
                Collections.emptySet(),
                implementation);
        ImmutableClassDef fixtureClass = new ImmutableClassDef(
                FIXTURE_TYPE,
                AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(),
                "Ljava/lang/Object;",
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList(method));
        ImmutableDexFile dexFile = new ImmutableDexFile(
                Opcodes.forApi(23), Collections.singleton(fixtureClass));

        MemoryDataStore output = new MemoryDataStore();
        try {
            DexPool.writeTo(output, dexFile);
            return output.getData();
        } finally {
            output.close();
        }
    }

    private static void writeDexOnlyArchive(File archive, byte[] dexBytes) throws Exception {
        try (FileOutputStream fileOutput = new FileOutputStream(archive);
             ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
            zipOutput.putNextEntry(new ZipEntry("classes.dex"));
            zipOutput.write(dexBytes);
            zipOutput.closeEntry();
        }
    }
}
