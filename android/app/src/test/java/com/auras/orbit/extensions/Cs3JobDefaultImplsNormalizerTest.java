package com.auras.orbit.extensions;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public final class Cs3JobDefaultImplsNormalizerTest {
    private static final String FIXTURE_CLASS = "Lfixture/Plugin;";
    private static final String JOB_OWNER = "Lkotlinx/coroutines/Job;";
    private static final String DEFAULT_IMPLS_OWNER = "Lkotlinx/coroutines/Job$DefaultImpls;";
    private static final String CANCEL_DEFAULT = "cancel$default";
    private static final List<String> CANCEL_PARAMETERS = Arrays.asList(
            JOB_OWNER,
            "Ljava/util/concurrent/CancellationException;",
            "I",
            "Ljava/lang/Object;");

    @Test public void rewritesOnlyExactStaticCallOwner() throws Exception {
        byte[] input = fixtureDex(Opcode.INVOKE_STATIC, true);

        Cs3JobDefaultImplsNormalizer.NormalizedDex result =
                Cs3JobDefaultImplsNormalizer.normalizeDexBytes(input, getClass().getClassLoader());
        Set<String> references = methodReferences(result.bytes);

        assertEquals(1, result.rewrittenReferences);
        assertEquals(1, countTarget(references, DEFAULT_IMPLS_OWNER));
        assertEquals(0, countTarget(references, JOB_OWNER));
        assertEquals(1, references.stream().filter(value -> value.startsWith(
                "Ljava/lang/Math;->abs(I)I")).count());
    }

    @Test public void invokeStaticRangeIsRewrittenAndNoopDexIsReturnedUnchanged() throws Exception {
        byte[] rangedInput = fixtureDex(Opcode.INVOKE_STATIC_RANGE, false);
        Cs3JobDefaultImplsNormalizer.NormalizedDex ranged =
                Cs3JobDefaultImplsNormalizer.normalizeDexBytes(rangedInput, getClass().getClassLoader());
        assertEquals(1, ranged.rewrittenReferences);
        assertEquals(1, countTarget(methodReferences(ranged.bytes), DEFAULT_IMPLS_OWNER));

        byte[] noOpInput = fixtureDex(Opcode.RETURN_VOID, false);
        Cs3JobDefaultImplsNormalizer.NormalizedDex noOp =
                Cs3JobDefaultImplsNormalizer.normalizeDexBytes(noOpInput, null);
        assertEquals(0, noOp.rewrittenReferences);
        assertSame(noOpInput, noOp.bytes);
    }

    @Test public void preservesPreexistingDefaultImplsCallsWhenAddingRewrittenCall() throws Exception {
        byte[] input = fixtureDex(Opcode.INVOKE_STATIC, false, true);

        Cs3JobDefaultImplsNormalizer.NormalizedDex result;
        try {
            result = Cs3JobDefaultImplsNormalizer.normalizeDexBytes(
                    input, getClass().getClassLoader());
        } catch (IOException rejected) {
            throw new AssertionError("valid DEX with an existing bridge call was rejected", rejected);
        }

        assertEquals(1, result.rewrittenReferences);
        assertEquals(0, countTargetInvocations(result.bytes, JOB_OWNER));
        assertEquals(2, countTargetInvocations(result.bytes, DEFAULT_IMPLS_OWNER));
    }

    @Test public void rejectsNonStaticExactCallAndMissingHostBridge() throws Exception {
        byte[] nonStaticInput = fixtureDex(Opcode.INVOKE_VIRTUAL, false);
        assertThrows(IOException.class, () -> Cs3JobDefaultImplsNormalizer.normalizeDexBytes(
                nonStaticInput, getClass().getClassLoader()));

        byte[] staticInput = fixtureDex(Opcode.INVOKE_STATIC, false);
        ClassLoader isolated = new ClassLoader(null) { };
        assertThrows(IOException.class, () -> Cs3JobDefaultImplsNormalizer.normalizeDexBytes(
                staticInput, isolated));
    }

    private static byte[] fixtureDex(Opcode opcode, boolean includeUnrelatedCall) throws IOException {
        return fixtureDex(opcode, includeUnrelatedCall, false);
    }

    private static byte[] fixtureDex(Opcode opcode, boolean includeUnrelatedCall,
                                    boolean includeExistingBridgeCall) throws IOException {
        ImmutableMethodImplementation implementation;
        if (opcode == Opcode.RETURN_VOID) {
            implementation = new ImmutableMethodImplementation(0,
                    Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    Collections.emptyList(), Collections.emptyList());
        } else {
            ImmutableMethodReference target = new ImmutableMethodReference(
                    JOB_OWNER, CANCEL_DEFAULT, CANCEL_PARAMETERS, "V");
            Instruction call;
            if (opcode == Opcode.INVOKE_STATIC_RANGE) {
                call = new ImmutableInstruction3rc(opcode, 0, 4, target);
            } else {
                call = new ImmutableInstruction35c(opcode, 4, 0, 1, 2, 3, 0, target);
            }
            java.util.ArrayList<Instruction> instructions = new java.util.ArrayList<>();
            instructions.add(call);
            if (includeUnrelatedCall) {
                ImmutableMethodReference unrelated = new ImmutableMethodReference(
                        "Ljava/lang/Math;", "abs", Collections.singletonList("I"), "I");
                instructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, unrelated));
            }
            if (includeExistingBridgeCall) {
                ImmutableMethodReference existingBridge = new ImmutableMethodReference(
                        DEFAULT_IMPLS_OWNER, CANCEL_DEFAULT, CANCEL_PARAMETERS, "V");
                instructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0, existingBridge));
            }
            instructions.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
            implementation = new ImmutableMethodImplementation(4, instructions,
                    Collections.emptyList(), Collections.emptyList());
        }
        ImmutableMethod method = new ImmutableMethod(FIXTURE_CLASS, "call",
                Collections.emptyList(), "V", AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(),
                Collections.emptySet(), Collections.emptySet(), implementation);
        ImmutableClassDef classDef = new ImmutableClassDef(FIXTURE_CLASS,
                AccessFlags.PUBLIC.getValue(), "Ljava/lang/Object;", Collections.emptyList(), null,
                Collections.emptyList(), Collections.emptyList(), Collections.singletonList(method));
        ImmutableDexFile dexFile = new ImmutableDexFile(Opcodes.forApi(23),
                Collections.singleton(classDef));
        MemoryDataStore output = new MemoryDataStore();
        DexPool.writeTo(output, dexFile);
        return output.getData();
    }

    private static Set<String> methodReferences(byte[] dexBytes) throws IOException {
        DexBackedDexFile dex = DexBackedDexFile.fromInputStream(null,
                new ByteArrayInputStream(dexBytes));
        Set<String> result = new LinkedHashSet<>();
        for (ClassDef classDef : dex.getClasses()) {
            for (com.android.tools.smali.dexlib2.iface.Method method : classDef.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction
                            && ((ReferenceInstruction) instruction).getReference()
                            instanceof MethodReference) {
                        MethodReference reference = (MethodReference)
                                ((ReferenceInstruction) instruction).getReference();
                        result.add(reference.getDefiningClass() + "->" + reference.getName()
                                + "(" + join(reference.getParameterTypes()) + ")"
                                + reference.getReturnType());
                    }
                }
            }
        }
        return result;
    }

    private static int countTarget(Set<String> references, String owner) {
        String prefix = owner + "->" + CANCEL_DEFAULT + "(";
        return (int) references.stream().filter(value -> value.startsWith(prefix)).count();
    }

    private static int countTargetInvocations(byte[] dexBytes, String owner) throws IOException {
        DexBackedDexFile dex = DexBackedDexFile.fromInputStream(null,
                new ByteArrayInputStream(dexBytes));
        int count = 0;
        for (ClassDef classDef : dex.getClasses()) {
            for (com.android.tools.smali.dexlib2.iface.Method method : classDef.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)
                            || !(((ReferenceInstruction) instruction).getReference()
                            instanceof MethodReference)) continue;
                    MethodReference reference = (MethodReference)
                            ((ReferenceInstruction) instruction).getReference();
                    if (owner.equals(reference.getDefiningClass())
                            && CANCEL_DEFAULT.equals(reference.getName())
                            && CANCEL_PARAMETERS.equals(reference.getParameterTypes())
                            && "V".equals(reference.getReturnType())) count++;
                }
            }
        }
        return count;
    }

    private static String join(List<? extends CharSequence> values) {
        StringBuilder result = new StringBuilder();
        for (CharSequence value : values) result.append(value);
        return result.toString();
    }
}
