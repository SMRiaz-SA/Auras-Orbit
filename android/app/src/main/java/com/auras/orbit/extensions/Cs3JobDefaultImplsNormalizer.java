package com.auras.orbit.extensions;

import android.content.Context;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.ReferenceType;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Rewrites one historical CS3 invoke owner without changing the installed package. */
public final class Cs3JobDefaultImplsNormalizer {
    private static final String OLD_OWNER = "Lkotlinx/coroutines/Job;";
    private static final String BRIDGE_OWNER = "Lkotlinx/coroutines/Job$DefaultImpls;";
    private static final String METHOD_NAME = "cancel$default";
    private static final List<String> PARAMETERS = Collections.unmodifiableList(Arrays.asList(
            "Lkotlinx/coroutines/Job;",
            "Ljava/util/concurrent/CancellationException;",
            "I",
            "Ljava/lang/Object;"));
    private static final String RETURN_TYPE = "V";
    private static final String BRIDGE_CLASS = "kotlinx.coroutines.Job$DefaultImpls";
    private static final String JOB_CLASS = "kotlinx.coroutines.Job";
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_DEX_ENTRIES = 16;
    private static final int MAX_DEX_ENTRY_BYTES = 96 * 1024 * 1024;
    private static final int MAX_TOTAL_DEX_BYTES = 192 * 1024 * 1024;
    private static final String CACHE_VERSION = "cs3-job-defaultimpls-v1-";

    private Cs3JobDefaultImplsNormalizer() { }

    /**
     * Returns the original archive when it needs no rewrite; otherwise returns an atomic,
     * content-addressed ZIP containing only verified, normalized DEX entries. The source CS3
     * archive is never modified and remains the resource/filename source for the plugin.
     */
    public static File prepareForClassLoading(Context context, File sourceArchive,
                                              ClassLoader hostLoader) throws IOException {
        if (context == null || sourceArchive == null || !sourceArchive.isFile()) {
            throw new IOException("CS3 archive is unavailable");
        }
        long packageLength = sourceArchive.length();
        if (packageLength <= 0 || packageLength > MAX_PACKAGE_BYTES) {
            throw new IOException("CS3 archive exceeds the compatibility preparation limit");
        }
        String sourceDigest = sha256(sourceArchive);
        LinkedHashMap<String, byte[]> preparedDex = new LinkedHashMap<>();
        int totalDexBytes = 0;
        int rewrittenReferences = 0;
        try (ZipFile zip = new ZipFile(sourceArchive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!isDexName(name)) continue;
                if (preparedDex.containsKey(name) || preparedDex.size() >= MAX_DEX_ENTRIES) {
                    throw new IOException("CS3 DEX entries are duplicated or exceed the limit");
                }
                long declaredSize = entry.getSize();
                if (declaredSize < 112 || declaredSize > MAX_DEX_ENTRY_BYTES) {
                    throw new IOException("CS3 DEX entry has an invalid or excessive size");
                }
                byte[] originalDex;
                try (InputStream input = zip.getInputStream(entry)) {
                    originalDex = readLimited(input, MAX_DEX_ENTRY_BYTES);
                }
                if (originalDex.length != declaredSize) {
                    throw new IOException("CS3 DEX entry size changed while reading");
                }
                totalDexBytes += originalDex.length;
                if (totalDexBytes > MAX_TOTAL_DEX_BYTES) {
                    throw new IOException("CS3 DEX data exceeds the compatibility preparation limit");
                }
                NormalizedDex normalized = normalizeDexBytes(originalDex, hostLoader);
                preparedDex.put(name, normalized.bytes);
                rewrittenReferences += normalized.rewrittenReferences;
            }
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("CS3 DEX compatibility preparation failed", error);
        }
        if (!preparedDex.containsKey("classes.dex")) {
            throw new IOException("CS3 archive has no root classes.dex entry");
        }
        if (!sourceDigest.equals(sha256(sourceArchive))) {
            throw new IOException("CS3 archive changed during compatibility preparation");
        }
        if (rewrittenReferences == 0) return sourceArchive;

        verifyHostBridge(hostLoader);
        File cacheDirectory = new File(context.getCodeCacheDir(), "cs3-job-defaultimpls");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Could not create CS3 compatibility cache");
        }
        String canonicalCacheDirectory = cacheDirectory.getCanonicalPath();
        String canonicalCodeCache = context.getCodeCacheDir().getCanonicalPath();
        if (!canonicalCacheDirectory.startsWith(canonicalCodeCache + File.separator)) {
            throw new IOException("CS3 compatibility cache is outside private code cache");
        }
        File destination = new File(cacheDirectory,
                CACHE_VERSION + sourceDigest + ".zip");
        if (isVerifiedCache(destination, preparedDex)) return destination;
        writeCacheAtomically(cacheDirectory, destination, preparedDex);
        if (!isVerifiedCache(destination, preparedDex)) {
            throw new IOException("CS3 compatibility cache failed post-write validation");
        }
        return destination;
    }

    /** Testable byte-level transform; it never reads or loads a plugin archive. */
    static NormalizedDex normalizeDexBytes(byte[] input, ClassLoader hostLoader) throws IOException {
        if (input == null || input.length < 112 || input.length > MAX_DEX_ENTRY_BYTES) {
            throw new IOException("DEX input is invalid or over the normalizer limit");
        }
        DexBackedDexFile dexFile;
        try {
            dexFile = DexBackedDexFile.fromInputStream(null, new ByteArrayInputStream(input));
        } catch (IOException | RuntimeException error) {
            throw new IOException("DEX input could not be parsed", error);
        }
        int oldCallCount = countOwnerInvocations(dexFile, OLD_OWNER, true);
        if (oldCallCount == 0) return new NormalizedDex(input, 0);
        int priorBridgeCallCount = countOwnerInvocations(dexFile, BRIDGE_OWNER, true);
        if (priorBridgeCallCount > Integer.MAX_VALUE - oldCallCount) {
            throw new IOException("bridge invocation count exceeds the normalizer limit");
        }
        int expectedBridgeCallCount = priorBridgeCallCount + oldCallCount;
        verifyHostBridge(hostLoader);

        ImmutableMethodReference bridgeReference = new ImmutableMethodReference(
                BRIDGE_OWNER, METHOD_NAME, PARAMETERS, RETURN_TYPE);
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return new InstructionRewriter(rewriters) {
                    @Override public Instruction rewrite(Instruction instruction) {
                        if (!(instruction instanceof ReferenceInstruction)
                                || ((ReferenceInstruction) instruction).getReferenceType()
                                != ReferenceType.METHOD) {
                            return super.rewrite(instruction);
                        }
                        Object reference = ((ReferenceInstruction) instruction).getReference();
                        if (!(reference instanceof MethodReference)
                                || !isTarget((MethodReference) reference, OLD_OWNER)) {
                            return super.rewrite(instruction);
                        }
                        if (instruction.getOpcode() == Opcode.INVOKE_STATIC
                                && instruction instanceof Instruction35c) {
                            Instruction35c call = (Instruction35c) instruction;
                            return new ImmutableInstruction35c(call.getOpcode(),
                                    call.getRegisterCount(), call.getRegisterC(), call.getRegisterD(),
                                    call.getRegisterE(), call.getRegisterF(), call.getRegisterG(),
                                    bridgeReference);
                        }
                        if (instruction.getOpcode() == Opcode.INVOKE_STATIC_RANGE
                                && instruction instanceof Instruction3rc) {
                            Instruction3rc call = (Instruction3rc) instruction;
                            return new ImmutableInstruction3rc(call.getOpcode(),
                                    call.getStartRegister(), call.getRegisterCount(), bridgeReference);
                        }
                        throw new IllegalArgumentException("target method reference is not an invoke-static call");
                    }
                };
            }
        });
        try {
            MemoryDataStore output = new MemoryDataStore(input.length);
            DexPool.writeTo(output, rewriter.getDexFileRewriter().rewrite(dexFile));
            byte[] rewrittenDex = output.getData();
            if (rewrittenDex.length > MAX_DEX_ENTRY_BYTES) {
                throw new IOException("normalized DEX exceeds the normalizer limit");
            }
            DexBackedDexFile rewritten = DexBackedDexFile.fromInputStream(
                    null, new ByteArrayInputStream(rewrittenDex));
            int remainingOldCalls = countOwnerInvocations(rewritten, OLD_OWNER, false);
            int bridgeCalls = countOwnerInvocations(rewritten, BRIDGE_OWNER, true);
            if (remainingOldCalls != 0 || bridgeCalls != expectedBridgeCallCount) {
                throw new IOException("DEX rewrite did not preserve the exact target call count");
            }
            return new NormalizedDex(rewrittenDex, oldCallCount);
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("DEX compatibility rewrite failed", error);
        }
    }

    private static int countOwnerInvocations(DexBackedDexFile dexFile, String owner,
                                             boolean requireStatic) throws IOException {
        int count = 0;
        try {
            for (ClassDef classDef : dexFile.getClasses()) {
                for (com.android.tools.smali.dexlib2.iface.Method method : classDef.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        if (!(instruction instanceof ReferenceInstruction)) continue;
                        ReferenceInstruction referenceInstruction = (ReferenceInstruction) instruction;
                        if (referenceInstruction.getReferenceType() != ReferenceType.METHOD
                                || !(referenceInstruction.getReference() instanceof MethodReference)) continue;
                        MethodReference reference = (MethodReference) referenceInstruction.getReference();
                        if (!isTarget(reference, owner)) continue;
                        boolean staticInvoke = instruction.getOpcode() == Opcode.INVOKE_STATIC
                                || instruction.getOpcode() == Opcode.INVOKE_STATIC_RANGE;
                        if (requireStatic && !staticInvoke) {
                            throw new IOException("target Job.cancel$default call is not static");
                        }
                        count++;
                    }
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("DEX instruction scan failed", error);
        }
        return count;
    }

    private static boolean isTarget(MethodReference method, String owner) {
        if (!owner.equals(method.getDefiningClass()) || !METHOD_NAME.equals(method.getName())
                || !RETURN_TYPE.equals(method.getReturnType())) return false;
        List<? extends CharSequence> actual = method.getParameterTypes();
        if (actual.size() != PARAMETERS.size()) return false;
        for (int index = 0; index < PARAMETERS.size(); index++) {
            if (!PARAMETERS.get(index).contentEquals(actual.get(index))) return false;
        }
        return true;
    }

    private static void verifyHostBridge(ClassLoader hostLoader) throws IOException {
        if (hostLoader == null) throw new IOException("Orbit host class loader is unavailable");
        try {
            Class<?> job = Class.forName(JOB_CLASS, false, hostLoader);
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, false, hostLoader);
            if (!job.isInterface() || !Modifier.isPublic(bridgeClass.getModifiers())
                    || bridgeClass.getClassLoader() != job.getClassLoader()) {
                throw new IOException("host coroutine bridge has an unexpected owner");
            }
            Method method = bridgeClass.getDeclaredMethod(METHOD_NAME, job,
                    java.util.concurrent.CancellationException.class, int.class, Object.class);
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || method.getReturnType() != void.class) {
                throw new IOException("host coroutine bridge has an unexpected signature");
            }
        } catch (IOException error) {
            throw error;
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IOException("exact Job$DefaultImpls.cancel$default host bridge is unavailable", error);
        } catch (RuntimeException error) {
            throw new IOException("exact Job$DefaultImpls.cancel$default host bridge validation failed", error);
        }
    }

    private static boolean isDexName(String name) {
        return name != null && name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) throw new IOException("Input stream made no progress.");
            if (output.size() > limit - read) throw new IOException("CS3 DEX entry exceeds the normalizer limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) throw new IOException("Input stream made no progress.");
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static boolean isVerifiedCache(File cacheFile, Map<String, byte[]> expectedDex) {
        if (!cacheFile.isFile()) return false;
        Set<String> found = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(cacheFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isDexName(entry.getName()) || !found.add(entry.getName())) return false;
                byte[] expected = expectedDex.get(entry.getName());
                if (expected == null) return false;
                byte[] actual;
                try (InputStream input = zip.getInputStream(entry)) {
                    actual = readLimited(input, MAX_DEX_ENTRY_BYTES);
                }
                if (!Arrays.equals(expected, actual)) return false;
            }
            return found.equals(expectedDex.keySet());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void writeCacheAtomically(File directory, File destination,
                                             Map<String, byte[]> dexEntries) throws IOException {
        File temporary = File.createTempFile("cs3-normalized-", ".tmp", directory);
        boolean committed = false;
        try {
            try (FileOutputStream fileOutput = new FileOutputStream(temporary);
                 ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
                for (Map.Entry<String, byte[]> item : dexEntries.entrySet()) {
                    ZipEntry entry = new ZipEntry(item.getKey());
                    entry.setTime(0L);
                    zipOutput.putNextEntry(entry);
                    zipOutput.write(item.getValue());
                    zipOutput.closeEntry();
                }
                zipOutput.finish();
                fileOutput.getFD().sync();
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Could not atomically commit CS3 compatibility cache");
            }
            committed = true;
        } finally {
            if (!committed && temporary.exists()) temporary.delete();
        }
    }

    static final class NormalizedDex {
        final byte[] bytes;
        final int rewrittenReferences;

        NormalizedDex(byte[] bytes, int rewrittenReferences) {
            this.bytes = bytes;
            this.rewrittenReferences = rewrittenReferences;
        }
    }
}
