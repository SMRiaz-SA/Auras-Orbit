package com.auras.orbit.extensions;

import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises static DEX closure parsing with synthetic symbol tables only. */
@RunWith(AndroidJUnit4.class)
public final class Cs3DexCapabilityPreflightInstrumentationTest {
    private static final String PLUGIN_CLASS_NAME =
            "com.auras.orbit.extensions.fixture.ArchivePlugin";
    private static final String PLUGIN_DESCRIPTOR =
            "Lcom/auras/orbit/extensions/fixture/ArchivePlugin;";
    private static final String BASE_PLUGIN_DESCRIPTOR =
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;";
    private static final String ARCHIVE_CHILD_DESCRIPTOR =
            "Lcom/auras/orbit/extensions/fixture/ArchiveChild;";
    private static final AtomicInteger SENTINEL_INITIALIZATION_COUNT = new AtomicInteger();
    private final List<File> archives = new ArrayList<>();

    @After
    public void deleteSyntheticArchives() {
        for (File archive : archives) archive.delete();
        archives.clear();
    }

    @Test
    public void resolvesExternalTypesAndExactMembersWithoutInitialization() throws Exception {
        SENTINEL_INITIALIZATION_COUNT.set(0);
        DexFixture dex = new DexFixture();
        String dependency = descriptor(ResolvableDependency.class);
        String sentinel = descriptor(InitializationSentinel.class);
        dex.addType(dependency);
        dex.addType(sentinel);
        dex.addType("Landroid/content/Context;");
        dex.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "echo", "Ljava/lang/String;",
                Collections.singletonList("Ljava/lang/String;"), 0x6e);
        dex.addMethodUse(PLUGIN_DESCRIPTOR, sentinel, "touch", "V", Collections.emptyList(), 0x6e);
        dex.addFieldUse(PLUGIN_DESCRIPTOR, dependency, "VALUE", "Ljava/lang/String;", 0x60);
        dex.addMethodUse(PLUGIN_DESCRIPTOR, "Landroid/content/Context;", "getPackageName",
                "Ljava/lang/String;", Collections.emptyList(), 0x6e);
        String interfaceType = descriptor(ResolvableInterface.class);
        dex.addMethodUse(PLUGIN_DESCRIPTOR, interfaceType, "interfaceCall",
                "Ljava/lang/String;", Collections.emptyList(), 0x72);
        String logType = "Lcom/lagradost/api/Log;";
        List<String> logParameters = new ArrayList<>();
        logParameters.add("Ljava/lang/String;");
        logParameters.add("Ljava/lang/String;");
        dex.addFieldUse(PLUGIN_DESCRIPTOR, logType, "INSTANCE", logType, 0x62);
        for (String method : new String[] {"d", "i", "w", "e"}) {
            dex.addMethodUse(PLUGIN_DESCRIPTOR, logType, method, "V", logParameters, 0x6e);
        }

        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);
        Cs3DexCapabilityPreflight.Result result = inspection.result;

        assertTrue(result.referencedExternalTypes.contains(dependency));
        assertTrue(result.referencedExternalTypes.contains(sentinel));
        assertTrue(result.referencedExternalTypes.contains("Landroid/content/Context;"));
        assertTrue(result.referencedExternalMethods.contains(
                dependency + "->echo(Ljava/lang/String;)Ljava/lang/String;"));
        assertTrue(result.referencedExternalMethods.contains(
                "Landroid/content/Context;->getPackageName()Ljava/lang/String;"));
        assertTrue(result.referencedExternalMethods.contains(
                interfaceType + "->interfaceCall()Ljava/lang/String;"));
        assertTrue(result.referencedExternalTypes.contains(logType));
        assertTrue(result.referencedExternalFields.contains(logType + "->INSTANCE:" + logType));
        for (String method : new String[] {"d", "i", "w", "e"}) {
            assertTrue(result.referencedExternalMethods.contains(
                    logType + "->" + method + "(Ljava/lang/String;Ljava/lang/String;)V"));
        }
        assertTrue(result.referencedExternalFields.contains(dependency + "->VALUE:Ljava/lang/String;"));
        assertTrue(result.missingExternalTypes.isEmpty());
        assertTrue(result.missingExternalMethods.isEmpty());
        assertTrue(result.missingExternalFields.isEmpty());
        assertTrue("all host and external symbols in this fixture resolve",
                result.isExactHostSurfacePresent());
        assertTrue("the host lookup must not initialize a referenced class",
                SENTINEL_INITIALIZATION_COUNT.get() == 0);
        assertEquals("the manifest class must remain a DEX-only symbol", 0,
                inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void resolvesDexArrayCloneUseButFailsClosedForOtherArrayMethods() throws Exception {
        String arrayOwner = "[Ljava/lang/Object;";
        String arrayClone = arrayOwner + "->clone()Ljava/lang/Object;";
        DexFixture cloneDex = new DexFixture();
        cloneDex.addMethodUseWithEncoding(PLUGIN_DESCRIPTOR, arrayOwner, "clone",
                "Ljava/lang/Object;", Collections.emptyList(), 0x6e, 1, 0);
        cloneDex.build();
        assertEquals("DEX 35c clone must encode A=1 and G=0 as code unit 0x106e",
                0x106e, cloneDex.firstEncodedInvokeCodeUnit);

        Inspection cloneInspection = inspect(cloneDex, PLUGIN_CLASS_NAME);
        assertTrue(cloneInspection.result.referencedExternalMethods.contains(arrayClone));
        assertFalse(cloneInspection.result.missingExternalMethods.contains(arrayClone));
        assertTrue(cloneInspection.result.externalResolutionComplete);
        assertEquals("array owner handling must not request the archive class from the host loader", 0,
                cloneInspection.loader.pluginClassLoadAttempts.get());

        String unsupportedArrayMethod = arrayOwner + "->getClass()Ljava/lang/Class;";
        DexFixture unsupportedDex = new DexFixture();
        unsupportedDex.addMethodUse(PLUGIN_DESCRIPTOR, arrayOwner, "getClass", "Ljava/lang/Class;",
                Collections.emptyList(), 0x6e);
        Inspection unsupportedInspection = inspect(unsupportedDex, PLUGIN_CLASS_NAME);
        assertFalse(unsupportedInspection.result.referencedExternalMethods.contains(unsupportedArrayMethod));
        assertTrue(unsupportedInspection.result.errors.toString().contains(
                "archive DEX inspection failed"));
        assertFalse(unsupportedInspection.result.isExactHostSurfacePresent());
        assertEquals(0, unsupportedInspection.loader.pluginClassLoadAttempts.get());

        DexFixture invalidInvokeDex = new DexFixture();
        invalidInvokeDex.addMethodUse(PLUGIN_DESCRIPTOR, arrayOwner, "clone", "Ljava/lang/Object;",
                Collections.emptyList(), 0x71); // array clone is not a static invoke
        Inspection invalidInvokeInspection = inspect(invalidInvokeDex, PLUGIN_CLASS_NAME);
        assertTrue(invalidInvokeInspection.result.missingExternalMethods.contains(arrayClone));
        assertFalse(invalidInvokeInspection.result.isExactHostSurfacePresent());
        assertEquals(0, invalidInvokeInspection.loader.pluginClassLoadAttempts.get());

        String objectClone = "Ljava/lang/Object;->clone()Ljava/lang/Object;";
        String arrayType = "[Ljava/lang/Object;";
        DexFixture objectCloneValid = new DexFixture();
        objectCloneValid.addObjectCloneSequence(PLUGIN_DESCRIPTOR, descriptor(ResolvableDependency.class),
                "ARRAY", arrayType, 2, 2);
        Inspection objectCloneValidInspection = inspect(objectCloneValid, PLUGIN_CLASS_NAME);
        assertTrue(objectCloneValidInspection.result.referencedExternalMethods.contains(objectClone));
        assertFalse(objectCloneValidInspection.result.missingExternalMethods.contains(objectClone));
        assertTrue(objectCloneValidInspection.result.isExactHostSurfacePresent());

        DexFixture objectCloneNonArray = new DexFixture();
        objectCloneNonArray.addObjectCloneSequence(PLUGIN_DESCRIPTOR,
                descriptor(ResolvableDependency.class), "VALUE", "Ljava/lang/String;", 2, 2);
        Inspection objectCloneNonArrayInspection = inspect(objectCloneNonArray, PLUGIN_CLASS_NAME);
        assertTrue("Object.clone on a non-array sget-object fails closed",
                objectCloneNonArrayInspection.result.missingExternalMethods.contains(objectClone));

        DexFixture objectCloneWrongRegister = new DexFixture();
        objectCloneWrongRegister.addObjectCloneSequence(PLUGIN_DESCRIPTOR,
                descriptor(ResolvableDependency.class), "ARRAY", arrayType, 2, 3);
        Inspection objectCloneWrongRegisterInspection = inspect(objectCloneWrongRegister,
                PLUGIN_CLASS_NAME);
        assertTrue("Object.clone receiver must match the immediately preceding sget-object register",
                objectCloneWrongRegisterInspection.result.missingExternalMethods.contains(objectClone));
        assertEquals(0, objectCloneValidInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, objectCloneNonArrayInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, objectCloneWrongRegisterInspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void validatesInvoke35cAndRangeWordCountsRegistersAndWidePairs() throws Exception {
        String arrayOwner = "[Ljava/lang/Object;";
        String arrayClone = arrayOwner + "->clone()Ljava/lang/Object;";

        DexFixture malformed35cCount = new DexFixture();
        malformed35cCount.addMethodUseWithEncoding(PLUGIN_DESCRIPTOR, arrayOwner, "clone",
                "Ljava/lang/Object;", Collections.emptyList(), 0x6e, 0, 0);
        malformed35cCount.build();
        assertEquals("malformed DEX 35c clone fixture must encode A=0",
                0x006e, malformed35cCount.firstEncodedInvokeCodeUnit);
        assertInvokeShapeRejected(malformed35cCount);

        DexFixture outOfRange35c = new DexFixture();
        outOfRange35c.addMethodUseWithEncoding(PLUGIN_DESCRIPTOR, arrayOwner, "clone",
                "Ljava/lang/Object;", Collections.emptyList(), 0x6e, 1, 8);
        assertInvokeShapeRejected(outOfRange35c);

        DexFixture malformedRangeCount = new DexFixture();
        malformedRangeCount.addMethodUseWithEncoding(PLUGIN_DESCRIPTOR, arrayOwner, "clone",
                "Ljava/lang/Object;", Collections.emptyList(), 0x74, 2, 0);
        assertInvokeShapeRejected(malformedRangeCount);

        DexFixture outOfRangeRange = new DexFixture();
        outOfRangeRange.addMethodUseWithEncoding(PLUGIN_DESCRIPTOR, arrayOwner, "clone",
                "Ljava/lang/Object;", Collections.emptyList(), 0x74, 1, 8);
        assertInvokeShapeRejected(outOfRangeRange);

        String dependency = descriptor(ResolvableDependency.class);
        List<String> wideParameters = new ArrayList<>();
        wideParameters.add("J");
        wideParameters.add("D");
        String wideReference = dependency + "->wideArguments(JD)V";
        DexFixture wide = new DexFixture();
        wide.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "wideArguments", "V",
                wideParameters, 0x6e); // 35c: receiver + two consecutive wide pairs.
        wide.addMethodUseWithEncoding(PLUGIN_DESCRIPTOR, dependency, "wideArguments", "V",
                wideParameters, 0x74, 5, 3); // 3rc: words v3..v7.
        wide.build();
        assertEquals("DEX 35c wide fixture must encode A=5 and G=4",
                0x546e, wide.firstEncodedInvokeCodeUnit);
        Inspection wideInspection = inspect(wide, PLUGIN_CLASS_NAME);
        assertTrue(wideInspection.result.referencedExternalMethods.contains(wideReference));
        assertFalse(wideInspection.result.missingExternalMethods.contains(wideReference));
        assertTrue(wideInspection.result.externalResolutionComplete);
        assertTrue(wideInspection.result.isExactHostSurfacePresent());
        assertEquals(0, wideInspection.loader.pluginClassLoadAttempts.get());

        DexFixture nonconsecutiveWidePair = new DexFixture();
        nonconsecutiveWidePair.addMethodUseWithRegisterList(PLUGIN_DESCRIPTOR, dependency,
                "wideArguments", "V", wideParameters, 0x6e,
                new int[] {0, 1, 3, 4, 5}); // long words v1/v3 are not a consecutive pair.
        nonconsecutiveWidePair.build();
        assertEquals("nonconsecutive wide-pair fixture must still encode a valid 35c count",
                0x556e, nonconsecutiveWidePair.firstEncodedInvokeCodeUnit);
        assertInvokeShapeRejected(nonconsecutiveWidePair);
    }

    private void assertInvokeShapeRejected(DexFixture fixture) throws Exception {
        Inspection inspection = inspect(fixture, PLUGIN_CLASS_NAME);
        assertTrue("malformed invoke shape must fail DEX inspection closed",
                inspection.result.errors.toString().contains("archive DEX inspection failed"));
        assertFalse(inspection.result.isExactHostSurfacePresent());
        assertEquals("failed invoke validation must not load archive classes", 0,
                inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void reportsMissingExternalTypeAndExactMemberCategories() throws Exception {
        DexFixture dex = new DexFixture();
        String dependency = descriptor(ResolvableDependency.class);
        String missingType = "Lcom/example/unavailable/NotPresent;";
        dex.addType(dependency);
        dex.addTypeUse(PLUGIN_DESCRIPTOR, missingType, 0x22); // new-instance runtime type use.
        dex.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "doesNotExist", "V",
                Collections.emptyList(), 0x71);
        dex.addFieldUse(PLUGIN_DESCRIPTOR, dependency, "NO_FIELD", "I", 0x60);

        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);
        Cs3DexCapabilityPreflight.Result result = inspection.result;

        assertTrue(result.missingExternalTypes.contains(missingType));
        assertTrue(result.missingExternalMethods.contains(dependency + "->doesNotExist()V"));
        assertTrue(result.missingExternalFields.contains(dependency + "->NO_FIELD:I"));
        assertFalse("unresolved external closure must fail the compatibility gate",
                result.isExactHostSurfacePresent());
        assertTrue("runtime-facing diagnostics must contain category counts, not DEX identifiers",
                result.errors.toString().contains("types=1 methods=1 fields=1"));
        assertFalse(result.errors.toString().contains(missingType));
        assertFalse(result.errors.toString().contains("doesNotExist"));
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void metadataOnlyTypesAreIgnoredButRuntimeTypeUsesRemainInClosure() throws Exception {
        String metadataType = "Lcom/example/metadata/OnlyAnnotation;";
        String buildAnnotationValueType = "Lcom/example/metadata/OnlyBuildValue;";
        DexFixture metadataOnly = new DexFixture();
        metadataOnly.addClassAnnotation(PLUGIN_DESCRIPTOR,
                new AnnotationSpec(metadataType, 0)
                        .add("typeValue", AnnotationValue.type(buildAnnotationValueType)));
        Inspection metadataInspection = inspect(metadataOnly, PLUGIN_CLASS_NAME);
        assertFalse(metadataInspection.result.referencedExternalTypes.contains(metadataType));
        assertFalse(metadataInspection.result.missingExternalTypes.contains(metadataType));
        assertFalse(metadataInspection.result.referencedExternalTypes.contains(buildAnnotationValueType));
        assertFalse(metadataInspection.result.missingExternalTypes.contains(buildAnnotationValueType));
        assertTrue(metadataInspection.result.isExactHostSurfacePresent());

        String runtimeMissingType = "Lcom/example/runtime/NotPresent;";
        DexFixture instantiated = new DexFixture();
        instantiated.addTypeUse(PLUGIN_DESCRIPTOR, runtimeMissingType, 0x22); // new-instance type operand.
        Inspection instantiatedInspection = inspect(instantiated, PLUGIN_CLASS_NAME);
        assertTrue(instantiatedInspection.result.referencedExternalTypes.contains(runtimeMissingType));
        assertTrue(instantiatedInspection.result.missingExternalTypes.contains(runtimeMissingType));

        DexFixture descriptorUse = new DexFixture();
        descriptorUse.addMethodUse(PLUGIN_DESCRIPTOR, descriptor(ResolvableDependency.class),
                "acceptsMissingType", "V", Collections.singletonList(runtimeMissingType), 0x71);
        Inspection descriptorInspection = inspect(descriptorUse, PLUGIN_CLASS_NAME);
        assertTrue(descriptorInspection.result.referencedExternalTypes.contains(runtimeMissingType));
        assertTrue(descriptorInspection.result.missingExternalTypes.contains(runtimeMissingType));
        assertEquals(0, metadataInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, instantiatedInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, descriptorInspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void runtimeAndSystemAnnotationValuesJoinExternalClosure() throws Exception {
        String annotationType = descriptor(ResolvableFixtureAnnotation.class);
        String nestedAnnotationType = descriptor(ResolvableNestedAnnotation.class);
        String enumType = descriptor(ResolvableEnum.class);
        String classLiteralType = "Lcom/example/annotation/ClassLiteral;";
        String nestedClassType = "Lcom/example/annotation/NestedClassLiteral;";
        String arrayClassType = "[Lcom/example/annotation/ArrayClassLiteral;";
        String methodTypeType = "Lcom/example/annotation/MethodTypeParameter;";
        String systemAnnotationType = "Lcom/example/annotation/SystemOnly;";
        String systemClassType = "Lcom/example/annotation/SystemClassLiteral;";

        AnnotationSpec nested = new AnnotationSpec(nestedAnnotationType, 1)
                .add("nestedClass", AnnotationValue.type(nestedClassType));
        AnnotationSpec runtime = new AnnotationSpec(annotationType, 1)
                .add("classValue", AnnotationValue.type(classLiteralType))
                .add("enumValue", AnnotationValue.enumValue(enumType, "VALUE", enumType))
                .add("nestedValue", AnnotationValue.nested(nested))
                .add("arrayValue", AnnotationValue.array(Arrays.asList(
                        AnnotationValue.type(arrayClassType),
                        AnnotationValue.array(Collections.singletonList(
                                AnnotationValue.type("Ljava/lang/String;"))))))
                .add("methodTypeValue", AnnotationValue.methodType("V",
                        Collections.singletonList(methodTypeType)));

        DexFixture dex = new DexFixture();
        dex.addClassAnnotation(PLUGIN_DESCRIPTOR, runtime);
        dex.addFieldAnnotation(PLUGIN_DESCRIPTOR, "annotatedField", "I",
                new AnnotationSpec(systemAnnotationType, 2)
                        .add("typeValue", AnnotationValue.type(systemClassType)));
        dex.addMethodAnnotation(PLUGIN_DESCRIPTOR, "annotatedMethod", "V",
                Collections.singletonList("Ljava/lang/String;"),
                new AnnotationSpec(annotationType, 1));
        dex.addParameterAnnotation(PLUGIN_DESCRIPTOR, "annotatedMethod", "V",
                Collections.singletonList("Ljava/lang/String;"), 0,
                new AnnotationSpec(nestedAnnotationType, 1));

        byte[] dexBytes = dex.build();
        Inspection inspection = inspectDex(dexBytes, PLUGIN_CLASS_NAME);
        for (String missingType : Arrays.asList(classLiteralType, nestedClassType,
                arrayClassType, methodTypeType, systemClassType)) {
            assertTrue("runtime/system annotation dependency should be referenced: " + missingType
                            + "; refs=" + inspection.result.referencedExternalTypes
                            + "; errors=" + inspection.result.errors,
                    inspection.result.referencedExternalTypes.contains(missingType));
            assertTrue("runtime/system annotation dependency should be unresolved: " + missingType,
                    inspection.result.missingExternalTypes.contains(missingType));
        }
        assertFalse("system annotation type is interpreted by ART, not loaded as an app class",
                inspection.result.referencedExternalTypes.contains(systemAnnotationType));
        assertFalse(inspection.result.missingExternalTypes.contains(systemAnnotationType));
        assertTrue(inspection.result.referencedExternalTypes.contains(annotationType));
        assertTrue(inspection.result.referencedExternalTypes.contains(nestedAnnotationType));
        String enumField = enumType + "->VALUE:" + enumType;
        assertTrue(inspection.result.referencedExternalFields.contains(enumField));
        assertFalse(inspection.result.missingExternalFields.contains(enumField));
        assertFalse(inspection.result.isExactHostSurfacePresent());
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void runtimeAnnotationMemberValuesJoinExactMemberClosure() throws Exception {
        DexFixture dex = new DexFixture();
        AnnotationSpec annotation = new AnnotationSpec(
                descriptor(ResolvableFixtureAnnotation.class), 1)
                .add("unsupportedField", AnnotationValue.field(
                        descriptor(ResolvableDependency.class), "instanceField", "I"));
        dex.addClassAnnotation(PLUGIN_DESCRIPTOR, annotation);
        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);
        String fieldReference = descriptor(ResolvableDependency.class) + "->instanceField:I";
        assertTrue("DEX annotation field reference should be checked exactly",
                inspection.result.referencedExternalFields.contains(fieldReference));
        assertFalse("the referenced field exists on the parent host class loader",
                inspection.result.missingExternalFields.contains(fieldReference));
        assertFalse("valid DEX annotation member metadata should not invalidate the archive",
                inspection.result.errors.toString().contains("archive DEX inspection failed"));
        assertTrue(inspection.result.isExactHostSurfacePresent());
        assertFalse(inspection.result.errors.toString().contains("instanceField"));
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void parameterAnnotationListsMayOmitTrailingUnannotatedParameters() throws Exception {
        DexFixture dex = new DexFixture();
        dex.addParameterAnnotation(PLUGIN_DESCRIPTOR, "annotatedMethod", "V",
                Arrays.asList("Ljava/lang/String;", "Ljava/lang/String;"), 0,
                new AnnotationSpec(descriptor(ResolvableFixtureAnnotation.class), 1));
        byte[] dexBytes = dex.build();
        setParameterAnnotationListSize(dexBytes, dex, 1);

        Inspection inspection = inspectDex(dexBytes, PLUGIN_CLASS_NAME);
        assertFalse("a shorter parameter annotation list is valid DEX metadata",
                inspection.result.errors.toString().contains("archive DEX inspection failed"));
        assertTrue(inspection.result.referencedExternalTypes.contains(
                descriptor(ResolvableFixtureAnnotation.class)));
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void parameterAnnotationListsCannotExceedMethodPrototype() throws Exception {
        DexFixture dex = new DexFixture();
        dex.addParameterAnnotation(PLUGIN_DESCRIPTOR, "annotatedMethod", "V",
                Collections.singletonList("Ljava/lang/String;"), 0,
                new AnnotationSpec(descriptor(ResolvableFixtureAnnotation.class), 1));
        byte[] dexBytes = dex.build();
        setParameterAnnotationListSize(dexBytes, dex, 2);

        Inspection inspection = inspectDex(dexBytes, PLUGIN_CLASS_NAME);
        assertFalse(inspection.result.isExactHostSurfacePresent());
        assertTrue(inspection.result.errors.toString().contains("archive DEX inspection failed"));
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    private static void setParameterAnnotationListSize(byte[] dexBytes, DexFixture dex,
                                                       int parameterSets) {
        ByteBuffer buffer = ByteBuffer.wrap(dexBytes).order(ByteOrder.LITTLE_ENDIAN);
        int listOffset = buffer.getInt(dex.pluginParameterAnnotationListPointerOffset);
        assertTrue("parameter annotation list offset must point into DEX data", listOffset > 0);
        buffer.putInt(listOffset, parameterSets);
    }

    @Test
    public void malformedAnnotationDirectoryBoundsFailClosed() throws Exception {
        DexFixture dex = new DexFixture();
        dex.addClassAnnotation(PLUGIN_DESCRIPTOR,
                new AnnotationSpec(descriptor(ResolvableFixtureAnnotation.class), 1));
        byte[] malformed = dex.build();
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(dex.pluginAnnotationDirectoryPointerOffset, 0x7ffffffc);
        Inspection inspection = inspectDex(malformed, PLUGIN_CLASS_NAME);
        assertFalse(inspection.result.isExactHostSurfacePresent());
        assertTrue(inspection.result.errors.toString().contains("archive DEX inspection failed"));
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());

        DexFixture malformedParameterList = new DexFixture();
        malformedParameterList.addParameterAnnotation(PLUGIN_DESCRIPTOR, "annotatedMethod", "V",
                Collections.singletonList("Ljava/lang/String;"), 0,
                new AnnotationSpec(descriptor(ResolvableFixtureAnnotation.class), 1));
        byte[] malformedParameterDex = malformedParameterList.build();
        ByteBuffer.wrap(malformedParameterDex).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(malformedParameterList.pluginParameterAnnotationListPointerOffset,
                        0x7ffffffc);
        Inspection parameterInspection = inspectDex(malformedParameterDex, PLUGIN_CLASS_NAME);
        assertFalse(parameterInspection.result.isExactHostSurfacePresent());
        assertTrue(parameterInspection.result.errors.toString().contains(
                "archive DEX inspection failed"));
        assertEquals(0, parameterInspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void excludesPluginOwnedSymbolsAndFailsClosedOnArchiveTypesInExternalMembers() throws Exception {
        DexFixture dex = new DexFixture();
        String dependency = descriptor(ResolvableDependency.class);
        dex.addType(dependency);
        dex.addMethod(PLUGIN_DESCRIPTOR, "pluginOnly", "V", Collections.emptyList());
        dex.addField(PLUGIN_DESCRIPTOR, "pluginOnlyField", "I");
        dex.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "takesArchiveType", "V",
                Collections.singletonList(PLUGIN_DESCRIPTOR), 0x71);
        dex.addFieldUse(PLUGIN_DESCRIPTOR, dependency, "archiveType", PLUGIN_DESCRIPTOR, 0x52);

        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);
        Cs3DexCapabilityPreflight.Result result = inspection.result;

        assertEquals(1, result.pluginDefinedTypeCount);
        assertFalse(result.referencedExternalTypes.contains(PLUGIN_DESCRIPTOR));
        assertFalse(result.referencedExternalMethods.contains(PLUGIN_DESCRIPTOR + "->pluginOnly()V"));
        assertFalse(result.referencedExternalFields.contains(PLUGIN_DESCRIPTOR + "->pluginOnlyField:I"));
        assertFalse(result.missingExternalMethods.contains(PLUGIN_DESCRIPTOR + "->pluginOnly()V"));
        assertFalse(result.missingExternalFields.contains(PLUGIN_DESCRIPTOR + "->pluginOnlyField:I"));
        assertTrue("external members whose signatures need archive classes are unresolved",
                result.missingExternalMethods.contains(
                        dependency + "->takesArchiveType(" + PLUGIN_DESCRIPTOR + ")V"));
        assertTrue(result.missingExternalFields.contains(
                dependency + "->archiveType:" + PLUGIN_DESCRIPTOR));
        assertEquals("no archive-defined class may be requested from the host loader", 0,
                inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void malformedDexFailureUsesSanitizedErrorText() throws Exception {
        File archive = writeArchive(new byte[112]);
        archives.add(archive);
        RecordingClassLoader loader = new RecordingClassLoader(
                Cs3DexCapabilityPreflightInstrumentationTest.class.getClassLoader(), PLUGIN_CLASS_NAME);
        try {
            Cs3DexCapabilityPreflight.Result result = Cs3DexCapabilityPreflight.inspect(
                    archive, PLUGIN_CLASS_NAME, false, loader);
            assertFalse(result.isExactHostSurfacePresent());
            assertTrue(result.errors.toString().contains("archive DEX inspection failed"));
            assertFalse(result.errors.toString().contains(PLUGIN_CLASS_NAME));
            assertEquals(0, loader.pluginClassLoadAttempts.get());
        } finally {
            archive.delete();
        }
    }

    @Test
    public void memberIdsWithoutRecognizedCodeUsesFailClosed() throws Exception {
        String dependency = descriptor(ResolvableDependency.class);
        DexFixture dex = new DexFixture();
        dex.addMethod(dependency, "instanceCall", "V", Collections.emptyList());
        dex.addField(dependency, "instanceField", "I");

        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);

        assertTrue(inspection.result.missingExternalMethods.contains(
                dependency + "->instanceCall()V"));
        assertTrue(inspection.result.missingExternalFields.contains(
                dependency + "->instanceField:I"));
        assertFalse(inspection.result.isExactHostSurfacePresent());
    }

    @Test
    public void staticInterfaceMethodsMustUseTheirDeclaringInterfaceOwner() throws Exception {
        Assume.assumeTrue("Comparator static methods require Android API 24+",
                Build.VERSION.SDK_INT >= 24);
        String declaringInterface = descriptor(Comparator.class);
        String childInterface = descriptor(ChildResolvableInterface.class);

        DexFixture direct = new DexFixture();
        direct.addMethodUse(PLUGIN_DESCRIPTOR, declaringInterface, "naturalOrder",
                declaringInterface,
                Collections.emptyList(), 0x71);
        Inspection directInspection = inspect(direct, PLUGIN_CLASS_NAME);
        assertFalse("a static interface call resolves through its declaring interface",
                directInspection.result.missingExternalMethods.contains(
                        declaringInterface + "->naturalOrder()" + declaringInterface));

        DexFixture inherited = new DexFixture();
        inherited.addMethodUse(PLUGIN_DESCRIPTOR, childInterface, "naturalOrder",
                declaringInterface,
                Collections.emptyList(), 0x71);
        Inspection inheritedInspection = inspect(inherited, PLUGIN_CLASS_NAME);
        assertTrue("a static interface method is not inherited by a child interface",
                inheritedInspection.result.missingExternalMethods.contains(
                        childInterface + "->naturalOrder()" + declaringInterface));
        assertFalse(inheritedInspection.result.isExactHostSurfacePresent());
    }

    @Test
    public void modifiedUtf8MetadataStringDoesNotInvalidateDexScan() throws Exception {
        DexFixture dex = new DexFixture();
        dex.setSourceFileName("Archive\u0000Plugin.java");
        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);

        assertTrue("DEX modified UTF-8 metadata should decode without exposing it in diagnostics",
                inspection.result.errors.isEmpty());
        assertTrue(inspection.result.isExactHostSurfacePresent());
    }

    @Test
    public void rejectsMismatchedInvokeAndFieldModesAndUnprovenProtectedAccess() throws Exception {
        String dependency = descriptor(ResolvableDependency.class);
        DexFixture mismatched = new DexFixture();
        mismatched.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "instanceCall", "V",
                Collections.emptyList(), 0x71); // invoke-static against an instance method
        mismatched.addFieldUse(PLUGIN_DESCRIPTOR, dependency, "instanceField", "I", 0x60); // sget against instance field
        Inspection mismatchInspection = inspect(mismatched, PLUGIN_CLASS_NAME);
        assertTrue(mismatchInspection.result.missingExternalMethods.contains(
                dependency + "->instanceCall()V"));
        assertTrue(mismatchInspection.result.missingExternalFields.contains(
                dependency + "->instanceField:I"));

        DexFixture illegalKinds = new DexFixture();
        illegalKinds.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "instanceCall", "V",
                Collections.emptyList(), 0x70); // invoke-direct cannot call a public virtual method
        illegalKinds.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "instanceCall", "V",
                Collections.emptyList(), 0x6f); // invoke-super requires a proven superclass owner
        illegalKinds.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "echo", "Ljava/lang/String;",
                Collections.singletonList("Ljava/lang/String;"), 0x72); // invoke-interface needs an interface owner
        illegalKinds.addMethodUse(PLUGIN_DESCRIPTOR, dependency, "<init>", "Ljava/lang/String;",
                Collections.emptyList(), 0x70); // constructors must return void
        Inspection illegalKindInspection = inspect(illegalKinds, PLUGIN_CLASS_NAME);
        assertTrue(illegalKindInspection.result.missingExternalMethods.contains(
                dependency + "->instanceCall()V"));
        assertTrue(illegalKindInspection.result.missingExternalMethods.contains(
                dependency + "->echo(Ljava/lang/String;)Ljava/lang/String;"));
        assertTrue(illegalKindInspection.result.missingExternalMethods.contains(
                dependency + "-><init>()Ljava/lang/String;"));

        String protectedType = descriptor(ProtectedDependency.class);
        DexFixture unrelated = new DexFixture();
        unrelated.addMethodUse(PLUGIN_DESCRIPTOR, protectedType, "protectedStatic", "V",
                Collections.emptyList(), 0x71);
        unrelated.addMethodUse(PLUGIN_DESCRIPTOR, protectedType, "protectedInstance", "V",
                Collections.emptyList(), 0x6f);
        Inspection unrelatedInspection = inspect(unrelated, PLUGIN_CLASS_NAME);
        assertTrue("protected access from an unrelated archive class is unresolved",
                unrelatedInspection.result.missingExternalMethods.contains(
                        protectedType + "->protectedStatic()V"));
        assertTrue("invoke-super must also prove the caller hierarchy",
                unrelatedInspection.result.missingExternalMethods.contains(
                        protectedType + "->protectedInstance()V"));

        DexFixture unrelatedPublicSuper = new DexFixture();
        unrelatedPublicSuper.addMethodUse(PLUGIN_DESCRIPTOR, protectedType, "publicInstance",
                "V", Collections.emptyList(), 0x6f);
        Inspection unrelatedPublicSuperInspection = inspect(unrelatedPublicSuper, PLUGIN_CLASS_NAME);
        assertTrue("public invoke-super still requires the symbolic owner in the caller hierarchy",
                unrelatedPublicSuperInspection.result.missingExternalMethods.contains(
                        protectedType + "->publicInstance()V"));

        DexFixture subclass = new DexFixture();
        subclass.addClassDef(ARCHIVE_CHILD_DESCRIPTOR, protectedType);
        subclass.addMethodUse(ARCHIVE_CHILD_DESCRIPTOR, protectedType, "publicInstance", "V",
                Collections.emptyList(), 0x6f);
        subclass.addMethodUse(ARCHIVE_CHILD_DESCRIPTOR, protectedType, "protectedStatic", "V",
                Collections.emptyList(), 0x71);
        subclass.addMethodUse(ARCHIVE_CHILD_DESCRIPTOR, protectedType, "protectedInstance", "V",
                Collections.emptyList(), 0x6f);
        Inspection subclassInspection = inspect(subclass, PLUGIN_CLASS_NAME);
        assertFalse("a protected static call from a proven subclass is legal",
                subclassInspection.result.missingExternalMethods.contains(
                        protectedType + "->protectedStatic()V"));
        assertFalse("a public invoke-super call from a proven subclass is legal",
                subclassInspection.result.missingExternalMethods.contains(
                        protectedType + "->publicInstance()V"));
        assertFalse("invoke-super uses the proven subclass receiver",
                subclassInspection.result.missingExternalMethods.contains(
                        protectedType + "->protectedInstance()V"));
    }

    @Test
    public void resolvesProtectedConstructorOnlyFromDirectSubclassInitializer() throws Exception {
        String protectedType = descriptor(ProtectedDependency.class);
        String constructor = protectedType + "-><init>()V";

        DexFixture valid = new DexFixture();
        valid.addClassDef(ARCHIVE_CHILD_DESCRIPTOR, protectedType);
        valid.addMethodUseFromCallerMethod(ARCHIVE_CHILD_DESCRIPTOR, "<init>", false,
                protectedType, "<init>", "V", Collections.emptyList(), 0x70, 7);
        Inspection validInspection = inspect(valid, PLUGIN_CLASS_NAME);
        assertTrue(validInspection.result.referencedExternalMethods.contains(constructor));
        assertFalse(validInspection.result.missingExternalMethods.contains(constructor));

        DexFixture unrelatedDirectSuperclass = new DexFixture();
        unrelatedDirectSuperclass.addClassDef(ARCHIVE_CHILD_DESCRIPTOR, "Ljava/lang/Object;");
        unrelatedDirectSuperclass.addMethodUseFromCallerMethod(ARCHIVE_CHILD_DESCRIPTOR,
                "<init>", false, protectedType, "<init>", "V", Collections.emptyList(), 0x70, 7);
        Inspection unrelatedInspection = inspect(unrelatedDirectSuperclass, PLUGIN_CLASS_NAME);
        assertTrue("protected constructor owner must be the caller's direct DEX superclass",
                unrelatedInspection.result.missingExternalMethods.contains(constructor));

        DexFixture nonConstructorCaller = new DexFixture();
        nonConstructorCaller.addClassDef(ARCHIVE_CHILD_DESCRIPTOR, protectedType);
        nonConstructorCaller.addMethodUseFromCallerMethod(ARCHIVE_CHILD_DESCRIPTOR, "helper",
                false, protectedType, "<init>", "V", Collections.emptyList(), 0x70, 7);
        Inspection nonConstructorInspection = inspect(nonConstructorCaller, PLUGIN_CLASS_NAME);
        assertTrue("protected constructor calls outside an initializer remain unresolved",
                nonConstructorInspection.result.missingExternalMethods.contains(constructor));

        DexFixture wrongReceiver = new DexFixture();
        wrongReceiver.addClassDef(ARCHIVE_CHILD_DESCRIPTOR, protectedType);
        wrongReceiver.addMethodUseFromCallerMethod(ARCHIVE_CHILD_DESCRIPTOR, "<init>", false,
                protectedType, "<init>", "V", Collections.emptyList(), 0x70, 0);
        Inspection wrongReceiverInspection = inspect(wrongReceiver, PLUGIN_CLASS_NAME);
        assertTrue("protected superclass constructor receiver must be caller this (v7)",
                wrongReceiverInspection.result.missingExternalMethods.contains(constructor));
        assertEquals(0, validInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, unrelatedInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, nonConstructorInspection.loader.pluginClassLoadAttempts.get());
        assertEquals(0, wrongReceiverInspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void resolvesConstMethodTypePrototypeRuntimeTypes() throws Exception {
        String missingType = "Lcom/example/runtime/ConstMethodTypeArgument;";
        DexFixture dex = new DexFixture();
        dex.setDexVersion("039");
        dex.addConstMethodTypeUse(PLUGIN_DESCRIPTOR, "V", Collections.singletonList(missingType));
        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);
        assertTrue(inspection.result.referencedExternalTypes.contains(missingType));
        assertTrue(inspection.result.missingExternalTypes.contains(missingType));
        assertFalse("const-method-type prototype types must participate in runtime closure",
                inspection.result.isExactHostSurfacePresent());
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    @Test
    public void typedCatchHandlerTypesParticipateInExternalClosure() throws Exception {
        String missingType = "Lcom/example/runtime/CatchHandlerType;";
        DexFixture dex = new DexFixture();
        dex.addTypedCatchUse(PLUGIN_DESCRIPTOR, missingType);
        Inspection inspection = inspect(dex, PLUGIN_CLASS_NAME);
        assertTrue(inspection.result.referencedExternalTypes.contains(missingType));
        assertTrue(inspection.result.missingExternalTypes.contains(missingType));
        assertFalse("typed catch types must participate in runtime closure",
                inspection.result.isExactHostSurfacePresent());
        assertEquals(0, inspection.loader.pluginClassLoadAttempts.get());
    }

    private Inspection inspect(DexFixture fixture, String pluginClassName) throws Exception {
        return inspectDex(fixture.build(), pluginClassName);
    }

    private Inspection inspectDex(byte[] dex, String pluginClassName) throws Exception {
        File archive = writeArchive(dex);
        archives.add(archive);
        RecordingClassLoader loader = new RecordingClassLoader(
                Cs3DexCapabilityPreflightInstrumentationTest.class.getClassLoader(), pluginClassName);
        Cs3DexCapabilityPreflight.Result result = Cs3DexCapabilityPreflight.inspect(
                archive, pluginClassName, false, loader);
        return new Inspection(archive, loader, result);
    }

    private static File writeArchive(byte[] dex) throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File archive = File.createTempFile("cs3-preflight-", ".zip", context.getCacheDir());
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(dex);
            zip.closeEntry();
        }
        return archive;
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    public static final class ResolvableDependency {
        public static final String VALUE = "static test symbol";
        public static final Object[] ARRAY = new Object[0];
        public int instanceField;

        public String echo(String value) { return value; }
        public void instanceCall() { }
        public void wideArguments(long value, double other) { }
    }

    public interface ResolvableInterface {
        String interfaceCall();
    }

    public interface ChildResolvableInterface extends Comparator<Object> {
    }

    public static class ProtectedDependency {
        protected ProtectedDependency() { }
        protected static void protectedStatic() { }
        protected void protectedInstance() { }
        public void publicInstance() { }
    }

    public @interface ResolvableFixtureAnnotation {
        Class<?> classValue();
        ResolvableEnum enumValue();
        ResolvableNestedAnnotation nestedValue();
        Class<?>[] arrayValue();
        Class<?> methodTypeValue();
    }

    public @interface ResolvableNestedAnnotation {
        Class<?> nestedClass();
    }

    public enum ResolvableEnum { VALUE }

    public static final class InitializationSentinel {
        static { SENTINEL_INITIALIZATION_COUNT.incrementAndGet(); }

        public void touch() { }
    }

    private static final class Inspection {
        final File archive;
        final RecordingClassLoader loader;
        final Cs3DexCapabilityPreflight.Result result;

        Inspection(File archive, RecordingClassLoader loader,
                   Cs3DexCapabilityPreflight.Result result) {
            this.archive = archive;
            this.loader = loader;
            this.result = result;
        }
    }

    private static final class RecordingClassLoader extends ClassLoader {
        final AtomicInteger pluginClassLoadAttempts = new AtomicInteger();
        private final String pluginClassName;

        RecordingClassLoader(ClassLoader parent, String pluginClassName) {
            super(parent);
            this.pluginClassName = pluginClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (pluginClassName.equals(name)) {
                pluginClassLoadAttempts.incrementAndGet();
                throw new ClassNotFoundException("archive class loading is forbidden");
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class DexFixture {
        private final LinkedHashSet<String> strings = new LinkedHashSet<>();
        private final LinkedHashSet<String> types = new LinkedHashSet<>();
        private final LinkedHashMap<String, Proto> prototypes = new LinkedHashMap<>();
        private final ArrayList<FieldId> fields = new ArrayList<>();
        private final ArrayList<MethodId> methods = new ArrayList<>();
        private final LinkedHashMap<String, ClassDef> classDefinitions = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<AnnotationSpec>> classAnnotations = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<AnnotationSpec>> fieldAnnotations = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<AnnotationSpec>> methodAnnotations = new LinkedHashMap<>();
        private final LinkedHashMap<String, LinkedHashMap<Integer, List<AnnotationSpec>>>
                parameterAnnotations = new LinkedHashMap<>();
        private final LinkedHashMap<String, FieldId> definedFields = new LinkedHashMap<>();
        private final LinkedHashMap<String, MethodId> definedMethods = new LinkedHashMap<>();
        private final ArrayList<CodeMethod> codeMethods = new ArrayList<>();
        private String sourceFileName = "ArchivePlugin.java";
        private String dexVersion = "035";
        private int firstEncodedInvokeCodeUnit = -1;
        private int pluginAnnotationDirectoryPointerOffset = -1;
        private int pluginParameterAnnotationListPointerOffset = -1;

        DexFixture() {
            addClassDef(PLUGIN_DESCRIPTOR, BASE_PLUGIN_DESCRIPTOR);
            strings.add(sourceFileName);
        }

        void setSourceFileName(String value) {
            strings.remove(sourceFileName);
            sourceFileName = value;
            strings.add(sourceFileName);
        }

        void setDexVersion(String value) {
            if (value == null || !value.matches("0[0-9]{2}")) {
                throw new IllegalArgumentException("fixture DEX version must be three decimal digits");
            }
            dexVersion = value;
        }

        void addType(String descriptor) {
            types.add(descriptor);
            strings.add(descriptor);
        }

        void addClassDef(String descriptor, String parentDescriptor) {
            addType(descriptor);
            if (parentDescriptor != null) addType(parentDescriptor);
            classDefinitions.put(descriptor, new ClassDef(descriptor, parentDescriptor));
        }

        void addClassAnnotation(String annotatedClass, AnnotationSpec annotation) {
            if (!classDefinitions.containsKey(annotatedClass)) {
                throw new IllegalArgumentException("annotation fixture class must have a class_def");
            }
            registerAnnotation(annotation);
            classAnnotations.computeIfAbsent(annotatedClass, key -> new ArrayList<>()).add(annotation);
        }

        void addFieldAnnotation(String owner, String name, String fieldType,
                                AnnotationSpec annotation) {
            FieldId field = ensureField(owner, name, fieldType);
            registerAnnotation(annotation);
            fieldAnnotations.computeIfAbsent(field.key(), key -> new ArrayList<>()).add(annotation);
        }

        void addMethodAnnotation(String owner, String name, String returnType,
                                 List<String> parameters, AnnotationSpec annotation) {
            MethodId method = ensureMethod(owner, name, returnType, parameters);
            ensureFixtureCodeBody(method);
            registerAnnotation(annotation);
            methodAnnotations.computeIfAbsent(method.key(), key -> new ArrayList<>()).add(annotation);
        }

        void addParameterAnnotation(String owner, String name, String returnType,
                                    List<String> parameters, int parameterIndex,
                                    AnnotationSpec annotation) {
            MethodId method = ensureMethod(owner, name, returnType, parameters);
            ensureFixtureCodeBody(method);
            if (parameterIndex < 0 || parameterIndex >= parameters.size()) {
                throw new IllegalArgumentException("parameter fixture index is out of bounds");
            }
            registerAnnotation(annotation);
            parameterAnnotations.computeIfAbsent(method.key(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(parameterIndex, key -> new ArrayList<>()).add(annotation);
        }

        private void ensureFixtureCodeBody(MethodId method) {
            if (findCodeMethod(method) != null) return;
            codeMethods.add(new CodeMethod(method, null, null, null, null, -1, 0,
                    0, false, -1, 0, null));
        }

        private void registerAnnotation(AnnotationSpec annotation) {
            addType(annotation.typeDescriptor);
            for (AnnotationElement element : annotation.elements) {
                strings.add(element.name);
                registerAnnotationValue(element.value);
            }
        }

        private void registerAnnotationValue(AnnotationValue value) {
            if (value.descriptor != null) addType(value.descriptor);
            if (value.enumField != null) {
                ensureField(value.enumField.owner, value.enumField.name, value.enumField.fieldType);
            }
            if (value.methodType != null) {
                addPrototype(value.methodType.returnType, value.methodType.parameters);
            }
            if (value.nestedAnnotation != null) registerAnnotation(value.nestedAnnotation);
            for (AnnotationValue child : value.arrayValues) registerAnnotationValue(child);
        }

        void addField(String owner, String name, String fieldType) {
            addType(owner);
            addType(fieldType);
            strings.add(name);
            FieldId field = new FieldId(owner, name, fieldType);
            if (!containsField(field)) fields.add(field);
            if (classDefinitions.containsKey(owner)) definedFields.put(field.key(), field);
        }

        void addMethod(String owner, String name, String returnType, List<String> parameters) {
            addType(owner);
            addPrototype(returnType, parameters);
            strings.add(name);
            MethodId method = new MethodId(owner, name, methodDescriptor(returnType, parameters));
            if (!containsMethod(method)) methods.add(method);
            if (classDefinitions.containsKey(owner)) definedMethods.put(method.key(), method);
        }

        private void addPrototype(String returnType, List<String> parameters) {
            addType(returnType);
            for (String parameter : parameters) addType(parameter);
            String descriptor = methodDescriptor(returnType, parameters);
            prototypes.put(descriptor, new Proto(returnType, new ArrayList<>(parameters)));
        }

        void addMethodUse(String callerClass, String owner, String name, String returnType,
                          List<String> parameters, int opcode) {
            addMethodUseWithEncoding(callerClass, owner, name, returnType, parameters, opcode,
                    -1, (opcode == 0x6f || opcode == 0x75) ? 7 : 0);
        }

        void addMethodUseWithEncoding(String callerClass, String owner, String name,
                                      String returnType, List<String> parameters, int opcode,
                                      int encodedWordCount, int firstRegister) {
            MethodId target = ensureMethod(owner, name, returnType, parameters);
            MethodId caller = ensureMethod(callerClass,
                    "$preflightUse" + codeMethods.size(), "V", Collections.emptyList());
            boolean callerStatic = opcode != 0x6f && opcode != 0x75;
            codeMethods.add(new CodeMethod(caller, target, null, null, null, -1, 0,
                    opcode, callerStatic, encodedWordCount, firstRegister, null));
        }

        void addMethodUseWithRegisterList(String callerClass, String owner, String name,
                                          String returnType, List<String> parameters, int opcode,
                                          int[] argumentRegisters) {
            if (opcode < 0x6e || opcode > 0x72 || argumentRegisters == null) {
                throw new IllegalArgumentException("explicit register list requires a 35c invoke");
            }
            MethodId target = ensureMethod(owner, name, returnType, parameters);
            MethodId caller = ensureMethod(callerClass,
                    "$preflightUse" + codeMethods.size(), "V", Collections.emptyList());
            boolean callerStatic = opcode != 0x6f;
            codeMethods.add(new CodeMethod(caller, target, null, null, null, -1, 0,
                    opcode, callerStatic, argumentRegisters.length, 0, argumentRegisters));
        }

        void addMethodUseFromCallerMethod(String callerClass, String callerMethodName,
                                          boolean callerStatic, String owner, String name,
                                          String returnType, List<String> parameters, int opcode) {
            addMethodUseFromCallerMethod(callerClass, callerMethodName, callerStatic, owner,
                    name, returnType, parameters, opcode,
                    (opcode == 0x6f || opcode == 0x75) ? 7 : 0);
        }

        void addMethodUseFromCallerMethod(String callerClass, String callerMethodName,
                                          boolean callerStatic, String owner, String name,
                                          String returnType, List<String> parameters, int opcode,
                                          int firstRegister) {
            MethodId target = ensureMethod(owner, name, returnType, parameters);
            MethodId caller = ensureMethod(callerClass, callerMethodName, "V",
                    Collections.emptyList());
            codeMethods.add(new CodeMethod(caller, target, null, null, null, -1, 0,
                    opcode, callerStatic, -1, firstRegister, null));
        }

        void addConstMethodTypeUse(String callerClass, String returnType,
                                   List<String> parameters) {
            addPrototype(returnType, parameters);
            MethodId caller = ensureMethod(callerClass,
                    "$preflightConstMethodType" + codeMethods.size(), "V", Collections.emptyList());
            CodeMethod code = new CodeMethod(caller, null, null, null, null, -1, 0,
                    0xff, true, -1, 0, null);
            code.protoTarget = methodDescriptor(returnType, parameters);
            codeMethods.add(code);
        }

        void addTypedCatchUse(String callerClass, String catchTypeDescriptor) {
            addType(catchTypeDescriptor);
            MethodId caller = ensureMethod(callerClass,
                    "$preflightTypedCatch" + codeMethods.size(), "V", Collections.emptyList());
            CodeMethod code = new CodeMethod(caller, null, null, null, null, -1, 0,
                    0, true, -1, 0, null);
            code.catchTypeDescriptor = catchTypeDescriptor;
            codeMethods.add(code);
        }

        void addFieldUse(String callerClass, String owner, String name, String fieldType,
                         int opcode) {
            FieldId target = ensureField(owner, name, fieldType);
            MethodId caller = ensureMethod(callerClass,
                    "$preflightUse" + codeMethods.size(), "V", Collections.emptyList());
            codeMethods.add(new CodeMethod(caller, null, target, null, null, -1, 0,
                    opcode, true, -1, 0, null));
        }

        void addTypeUse(String callerClass, String typeDescriptor, int opcode) {
            addType(typeDescriptor);
            MethodId caller = ensureMethod(callerClass,
                    "$preflightUse" + codeMethods.size(), "V", Collections.emptyList());
            codeMethods.add(new CodeMethod(caller, null, null, typeDescriptor, null, -1, 0,
                    opcode, true, -1, 0, null));
        }

        void addObjectCloneSequence(String callerClass, String fieldOwner, String fieldName,
                                    String fieldType, int fieldDestinationRegister,
                                    int receiverRegister) {
            FieldId arrayField = ensureField(fieldOwner, fieldName, fieldType);
            MethodId clone = ensureMethod("Ljava/lang/Object;", "clone",
                    "Ljava/lang/Object;", Collections.emptyList());
            MethodId caller = ensureMethod(callerClass,
                    "$preflightObjectClone" + codeMethods.size(), "V", Collections.emptyList());
            codeMethods.add(new CodeMethod(caller, clone, null, null, arrayField, 0x62,
                    fieldDestinationRegister, 0x6e, true, 1, receiverRegister,
                    new int[] {receiverRegister}));
        }

        private MethodId ensureMethod(String owner, String name, String returnType,
                                      List<String> parameters) {
            String signature = owner + "->" + name + methodDescriptor(returnType, parameters);
            for (MethodId method : methods) if (method.key().equals(signature)) return method;
            addMethod(owner, name, returnType, parameters);
            for (MethodId method : methods) if (method.key().equals(signature)) return method;
            throw new IllegalStateException("fixture method was not added");
        }

        private FieldId ensureField(String owner, String name, String fieldType) {
            String signature = owner + "->" + name + ":" + fieldType;
            for (FieldId field : fields) if (field.key().equals(signature)) return field;
            addField(owner, name, fieldType);
            for (FieldId field : fields) if (field.key().equals(signature)) return field;
            throw new IllegalStateException("fixture field was not added");
        }

        private boolean containsMethod(MethodId candidate) {
            for (MethodId method : methods) if (method.key().equals(candidate.key())) return true;
            return false;
        }

        private boolean containsField(FieldId candidate) {
            for (FieldId field : fields) if (field.key().equals(candidate.key())) return true;
            return false;
        }

        byte[] build() throws IOException {
            firstEncodedInvokeCodeUnit = -1;
            ArrayList<String> orderedStrings = new ArrayList<>(strings);
            Collections.sort(orderedStrings);
            ArrayList<String> orderedTypes = new ArrayList<>(types);
            Collections.sort(orderedTypes);
            ArrayList<String> orderedPrototypes = new ArrayList<>(prototypes.keySet());
            Collections.sort(orderedPrototypes);
            Map<String, Integer> stringIndexes = indexes(orderedStrings);
            Map<String, Integer> typeIndexes = indexes(orderedTypes);
            Map<String, Integer> protoIndexes = indexes(orderedPrototypes);
            ArrayList<FieldId> orderedFields = new ArrayList<>(fields);
            Collections.sort(orderedFields, (left, right) -> {
                int owner = typeIndexes.get(left.owner).compareTo(typeIndexes.get(right.owner));
                if (owner != 0) return owner;
                int name = stringIndexes.get(left.name).compareTo(stringIndexes.get(right.name));
                return name != 0 ? name : typeIndexes.get(left.fieldType).compareTo(typeIndexes.get(right.fieldType));
            });
            ArrayList<MethodId> orderedMethods = new ArrayList<>(methods);
            Collections.sort(orderedMethods, (left, right) -> {
                int owner = typeIndexes.get(left.owner).compareTo(typeIndexes.get(right.owner));
                if (owner != 0) return owner;
                int name = stringIndexes.get(left.name).compareTo(stringIndexes.get(right.name));
                return name != 0 ? name : protoIndexes.get(left.prototype).compareTo(protoIndexes.get(right.prototype));
            });
            Map<String, Integer> fieldIndexes = new LinkedHashMap<>();
            for (int i = 0; i < orderedFields.size(); i++) fieldIndexes.put(orderedFields.get(i).key(), i);
            Map<String, Integer> methodIndexes = new LinkedHashMap<>();
            for (int i = 0; i < orderedMethods.size(); i++) methodIndexes.put(orderedMethods.get(i).key(), i);
            ArrayList<ClassDef> orderedClasses = new ArrayList<>(classDefinitions.values());
            Collections.sort(orderedClasses, (left, right) ->
                    typeIndexes.get(left.descriptor).compareTo(typeIndexes.get(right.descriptor)));

            int stringsOffset = 112;
            int typesOffset = stringsOffset + orderedStrings.size() * 4;
            int protosOffset = typesOffset + orderedTypes.size() * 4;
            int fieldsOffset = protosOffset + orderedPrototypes.size() * 12;
            int methodsOffset = fieldsOffset + orderedFields.size() * 8;
            int classesOffset = methodsOffset + orderedMethods.size() * 8;
            int classCount = orderedClasses.size();
            int dataOffset = classesOffset + classCount * 32;

            ByteArrayOutputStream data = new ByteArrayOutputStream();
            LinkedHashMap<String, Integer> parameterOffsets = new LinkedHashMap<>();
            for (String descriptor : orderedPrototypes) {
                Proto proto = prototypes.get(descriptor);
                if (proto.parameters.isEmpty()) continue;
                align4(data);
                parameterOffsets.put(descriptor, dataOffset + data.size());
                writeInt(data, proto.parameters.size());
                for (String parameter : proto.parameters) writeShort(data, typeIndexes.get(parameter));
                if ((proto.parameters.size() & 1) != 0) writeShort(data, 0);
            }
            LinkedHashMap<String, Integer> codeOffsets = new LinkedHashMap<>();
            for (CodeMethod codeMethod : codeMethods) {
                align4(data);
                codeOffsets.put(codeMethod.method.key(), dataOffset + data.size());
                int[] instructions = encodeInstructions(codeMethod, methodIndexes, fieldIndexes,
                        protoIndexes, typeIndexes);
                if (codeMethod.methodTarget != null && firstEncodedInvokeCodeUnit < 0) {
                    firstEncodedInvokeCodeUnit = instructions[
                            codeMethod.precedingFieldTarget == null ? 0 : 2];
                }
                writeShort(data, codeMethod.registersSize); // registers_size
                writeShort(data, codeMethod.incomingSize); // ins_size
                writeShort(data, 5); // outs_size
                writeShort(data, codeMethod.catchTypeDescriptor == null ? 0 : 1); // tries_size
                writeInt(data, 0); // debug_info_off
                writeInt(data, instructions.length);
                for (int instruction : instructions) writeShort(data, instruction);
                if (codeMethod.catchTypeDescriptor != null) {
                    if ((instructions.length & 1) != 0) writeShort(data, 0); // try_items alignment.
                    writeInt(data, 0); // try_item.start_addr
                    writeShort(data, 1); // try_item.insn_count
                    writeShort(data, 1); // handler_off after the handler-list size ULEB128.
                    writeUleb128(data, 1); // encoded_catch_handler_list.size
                    writeSleb128(data, 1); // one typed catch, no catch-all
                    writeUleb128(data, typeIndexes.get(codeMethod.catchTypeDescriptor));
                    writeUleb128(data, 1); // handler address at move-exception.
                }
            }
            LinkedHashMap<String, Integer> classDataOffsets = new LinkedHashMap<>();
            for (ClassDef classDef : orderedClasses) {
                ArrayList<FieldId> classFields = new ArrayList<>();
                for (FieldId field : definedFields.values()) {
                    if (classDef.descriptor.equals(field.owner)) classFields.add(field);
                }
                ArrayList<MethodId> classMethods = new ArrayList<>();
                for (MethodId method : definedMethods.values()) {
                    if (classDef.descriptor.equals(method.owner)) classMethods.add(method);
                }
                Collections.sort(classFields, (left, right) ->
                        fieldIndexes.get(left.key()).compareTo(fieldIndexes.get(right.key())));
                ArrayList<MethodId> directMethods = new ArrayList<>();
                ArrayList<MethodId> virtualMethods = new ArrayList<>();
                for (MethodId method : classMethods) {
                    CodeMethod code = findCodeMethod(method);
                    if (code != null && !code.callerStatic && !"<init>".equals(method.name)) {
                        virtualMethods.add(method);
                    }
                    else directMethods.add(method);
                }
                Collections.sort(directMethods, (left, right) ->
                        methodIndexes.get(left.key()).compareTo(methodIndexes.get(right.key())));
                Collections.sort(virtualMethods, (left, right) ->
                        methodIndexes.get(left.key()).compareTo(methodIndexes.get(right.key())));
                if (classFields.isEmpty() && directMethods.isEmpty() && virtualMethods.isEmpty()) continue;
                classDataOffsets.put(classDef.descriptor, dataOffset + data.size());
                writeUleb128(data, 0); // static fields
                writeUleb128(data, classFields.size()); // instance fields
                writeUleb128(data, directMethods.size());
                writeUleb128(data, virtualMethods.size());
                int previous = 0;
                for (FieldId field : classFields) {
                    int index = fieldIndexes.get(field.key());
                    writeUleb128(data, index - previous);
                    previous = index;
                    writeUleb128(data, 0x1); // public instance field
                }
                writeMethodGroup(data, directMethods, methodIndexes, codeOffsets, 0x9);
                writeMethodGroup(data, virtualMethods, methodIndexes, codeOffsets, 0x1);
            }
            LinkedHashMap<String, Integer> stringDataOffsets = new LinkedHashMap<>();
            for (String value : orderedStrings) {
                stringDataOffsets.put(value, dataOffset + data.size());
                byte[] encoded = encodeModifiedUtf8(value);
                writeUleb128(data, value.length());
                data.write(encoded);
                data.write(0);
            }

            LinkedHashMap<String, Integer> annotationDirectoryOffsets = new LinkedHashMap<>();
            ArrayList<OffsetPatch> annotationPatches = new ArrayList<>();
            ArrayList<AnnotationSetPatch> annotationSetPatches = new ArrayList<>();
            ArrayList<ParameterSetPatch> parameterSetPatches = new ArrayList<>();
            ArrayList<ParameterListPatch> parameterListPatches = new ArrayList<>();
            for (ClassDef classDef : orderedClasses) {
                List<AnnotationSpec> classSpecs = classAnnotations.get(classDef.descriptor);
                ArrayList<FieldId> annotatedFields = new ArrayList<>();
                for (String fieldKey : fieldAnnotations.keySet()) {
                    for (FieldId field : fields) {
                        if (field.key().equals(fieldKey) && classDef.descriptor.equals(field.owner)) {
                            annotatedFields.add(field);
                            break;
                        }
                    }
                }
                Collections.sort(annotatedFields, (left, right) ->
                        fieldIndexes.get(left.key()).compareTo(fieldIndexes.get(right.key())));
                ArrayList<MethodId> annotatedMethods = new ArrayList<>();
                for (String methodKey : methodAnnotations.keySet()) {
                    for (MethodId method : methods) {
                        if (method.key().equals(methodKey)
                                && classDef.descriptor.equals(method.owner)) {
                            annotatedMethods.add(method);
                            break;
                        }
                    }
                }
                Collections.sort(annotatedMethods, (left, right) ->
                        methodIndexes.get(left.key()).compareTo(methodIndexes.get(right.key())));
                ArrayList<MethodId> annotatedParameters = new ArrayList<>();
                for (String methodKey : parameterAnnotations.keySet()) {
                    for (MethodId method : methods) {
                        if (method.key().equals(methodKey)
                                && classDef.descriptor.equals(method.owner)) {
                            annotatedParameters.add(method);
                            break;
                        }
                    }
                }
                Collections.sort(annotatedParameters, (left, right) ->
                        methodIndexes.get(left.key()).compareTo(methodIndexes.get(right.key())));
                if ((classSpecs == null || classSpecs.isEmpty()) && annotatedFields.isEmpty()
                        && annotatedMethods.isEmpty() && annotatedParameters.isEmpty()) continue;

                align4(data);
                int directoryOffset = dataOffset + data.size();
                annotationDirectoryOffsets.put(classDef.descriptor, directoryOffset);
                int classSetPointer = dataOffset + data.size();
                writeInt(data, 0);
                writeInt(data, annotatedFields.size());
                writeInt(data, annotatedMethods.size());
                writeInt(data, annotatedParameters.size());
                if (classSpecs != null && !classSpecs.isEmpty()) {
                    annotationSetPatches.add(new AnnotationSetPatch(classSetPointer, classSpecs));
                }
                for (FieldId field : annotatedFields) {
                    writeInt(data, fieldIndexes.get(field.key()));
                    int setPointer = dataOffset + data.size();
                    writeInt(data, 0);
                    annotationSetPatches.add(new AnnotationSetPatch(setPointer,
                            fieldAnnotations.get(field.key())));
                }
                for (MethodId method : annotatedMethods) {
                    writeInt(data, methodIndexes.get(method.key()));
                    int setPointer = dataOffset + data.size();
                    writeInt(data, 0);
                    annotationSetPatches.add(new AnnotationSetPatch(setPointer,
                            methodAnnotations.get(method.key())));
                }
                for (MethodId method : annotatedParameters) {
                    writeInt(data, methodIndexes.get(method.key()));
                    int listPointer = dataOffset + data.size();
                    writeInt(data, 0);
                    if (PLUGIN_DESCRIPTOR.equals(classDef.descriptor)) {
                        pluginParameterAnnotationListPointerOffset = listPointer;
                    }
                    LinkedHashMap<Integer, List<AnnotationSpec>> byParameter =
                            parameterAnnotations.get(method.key());
                    Proto methodProto = prototypes.get(method.prototype);
                    parameterListPatches.add(new ParameterListPatch(listPointer,
                            methodProto.parameters.size(), byParameter));
                }
            }
            for (ParameterListPatch patch : parameterListPatches) {
                align4(data);
                int listOffset = dataOffset + data.size();
                annotationPatches.add(new OffsetPatch(patch.pointerOffset, listOffset));
                writeInt(data, patch.parameterCount);
                for (int parameter = 0; parameter < patch.parameterCount; parameter++) {
                    int setPointer = dataOffset + data.size();
                    writeInt(data, 0);
                    List<AnnotationSpec> specs = patch.byParameter.get(parameter);
                    if (specs != null && !specs.isEmpty()) {
                        parameterSetPatches.add(new ParameterSetPatch(setPointer, specs));
                    }
                }
            }
            for (AnnotationSetPatch patch : annotationSetPatches) {
                int setOffset = appendAnnotationSet(data, dataOffset, patch.annotations,
                        typeIndexes, stringIndexes, fieldIndexes, protoIndexes);
                annotationPatches.add(new OffsetPatch(patch.pointerOffset, setOffset));
            }
            for (ParameterSetPatch patch : parameterSetPatches) {
                int setOffset = appendAnnotationSet(data, dataOffset, patch.annotations,
                        typeIndexes, stringIndexes, fieldIndexes, protoIndexes);
                annotationPatches.add(new OffsetPatch(patch.pointerOffset, setOffset));
            }

            int fileSize = dataOffset + data.size();
            ByteBuffer dex = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = {'d', 'e', 'x', '\n', (byte) dexVersion.charAt(0),
                    (byte) dexVersion.charAt(1), (byte) dexVersion.charAt(2), 0};
            dex.position(0);
            dex.put(magic);
            dex.putInt(32, fileSize);
            dex.putInt(36, 112);
            dex.putInt(40, 0x12345678);
            dex.putInt(56, orderedStrings.size());
            dex.putInt(60, stringsOffset);
            dex.putInt(64, orderedTypes.size());
            dex.putInt(68, typesOffset);
            dex.putInt(72, orderedPrototypes.size());
            dex.putInt(76, protosOffset);
            dex.putInt(80, orderedFields.size());
            dex.putInt(84, fieldsOffset);
            dex.putInt(88, orderedMethods.size());
            dex.putInt(92, methodsOffset);
            dex.putInt(96, classCount);
            dex.putInt(100, classesOffset);
            dex.putInt(104, data.size());
            dex.putInt(108, dataOffset);

            for (int i = 0; i < orderedStrings.size(); i++) {
                dex.putInt(stringsOffset + i * 4, stringDataOffsets.get(orderedStrings.get(i)));
            }
            for (int i = 0; i < orderedTypes.size(); i++) {
                dex.putInt(typesOffset + i * 4, stringIndexes.get(orderedTypes.get(i)));
            }
            for (int i = 0; i < orderedPrototypes.size(); i++) {
                String descriptor = orderedPrototypes.get(i);
                Proto proto = prototypes.get(descriptor);
                int base = protosOffset + i * 12;
                dex.putInt(base, 0); // The parser does not inspect the shorty index.
                dex.putInt(base + 4, typeIndexes.get(proto.returnType));
                dex.putInt(base + 8, parameterOffsets.containsKey(descriptor)
                        ? parameterOffsets.get(descriptor) : 0);
            }
            for (int i = 0; i < orderedFields.size(); i++) {
                FieldId field = orderedFields.get(i);
                int base = fieldsOffset + i * 8;
                dex.putShort(base, (short) (int) typeIndexes.get(field.owner));
                dex.putShort(base + 2, (short) (int) typeIndexes.get(field.fieldType));
                dex.putInt(base + 4, stringIndexes.get(field.name));
            }
            for (int i = 0; i < orderedMethods.size(); i++) {
                MethodId method = orderedMethods.get(i);
                int base = methodsOffset + i * 8;
                dex.putShort(base, (short) (int) typeIndexes.get(method.owner));
                dex.putShort(base + 2, (short) (int) protoIndexes.get(method.prototype));
                dex.putInt(base + 4, stringIndexes.get(method.name));
            }
            for (int i = 0; i < orderedClasses.size(); i++) {
                ClassDef classDef = orderedClasses.get(i);
                int classBase = classesOffset + i * 32;
                dex.putInt(classBase, typeIndexes.get(classDef.descriptor));
                dex.putInt(classBase + 4, 0x1);
                dex.putInt(classBase + 8, classDef.parentDescriptor == null ? -1
                        : typeIndexes.get(classDef.parentDescriptor));
                dex.putInt(classBase + 16, stringIndexes.get(sourceFileName));
                dex.putInt(classBase + 20, annotationDirectoryOffsets.containsKey(classDef.descriptor)
                        ? annotationDirectoryOffsets.get(classDef.descriptor) : 0);
                if (PLUGIN_DESCRIPTOR.equals(classDef.descriptor)) {
                    pluginAnnotationDirectoryPointerOffset = classBase + 20;
                }
                dex.putInt(classBase + 24, classDataOffsets.containsKey(classDef.descriptor)
                        ? classDataOffsets.get(classDef.descriptor) : 0);
            }
            dex.position(dataOffset);
            dex.put(data.toByteArray());
            for (OffsetPatch patch : annotationPatches) {
                dex.putInt(patch.pointerOffset, patch.targetOffset);
            }
            return dex.array();
        }

        private CodeMethod findCodeMethod(MethodId method) {
            for (CodeMethod code : codeMethods) {
                if (code.method.key().equals(method.key())) return code;
            }
            return null;
        }

        private void writeMethodGroup(ByteArrayOutputStream data, List<MethodId> group,
                                      Map<String, Integer> methodIndexes,
                                      Map<String, Integer> codeOffsets, int accessFlags) {
            int previous = 0;
            for (MethodId method : group) {
                int index = methodIndexes.get(method.key());
                writeUleb128(data, index - previous);
                previous = index;
                CodeMethod code = findCodeMethod(method);
                int encodedAccessFlags = code == null ? (accessFlags | 0x100) : accessFlags;
                if (code != null && "<init>".equals(method.name)) encodedAccessFlags = 0x1;
                writeUleb128(data, encodedAccessFlags);
                writeUleb128(data, code == null ? 0 : codeOffsets.get(method.key()));
            }
        }

        private int[] encodeInstructions(CodeMethod codeMethod, Map<String, Integer> methodIndexes,
                                         Map<String, Integer> fieldIndexes,
                                         Map<String, Integer> protoIndexes,
                                         Map<String, Integer> typeIndexes) {
            ArrayList<Integer> units = new ArrayList<>();
            if (codeMethod.precedingFieldTarget != null) {
                units.add(codeMethod.precedingFieldOpcode
                        | (codeMethod.precedingFieldRegister << 8));
                units.add(fieldIndexes.get(codeMethod.precedingFieldTarget.key()));
            }
            if (codeMethod.protoTarget != null) {
                units.add(0xff);
                units.add(protoIndexes.get(codeMethod.protoTarget));
            } else if (codeMethod.typeTarget != null) {
                units.add(codeMethod.opcode);
                units.add(typeIndexes.get(codeMethod.typeTarget));
            } else if (codeMethod.methodTarget != null) {
                MethodId target = codeMethod.methodTarget;
                int methodIndex = methodIndexes.get(target.key());
                if (codeMethod.opcode == 0xfa || codeMethod.opcode == 0xfb) {
                    int count = target.parameterWordCount() + 1;
                    units.add(codeMethod.opcode | (count << 8));
                    units.add(methodIndex);
                    units.add(0);
                    units.add(protoIndexes.get(target.prototype));
                } else {
                    int expectedCount = target.parameterWordCount() + ((codeMethod.opcode == 0x71
                            || codeMethod.opcode == 0x77) ? 0 : 1);
                    int count = codeMethod.encodedWordCount >= 0
                            ? codeMethod.encodedWordCount : expectedCount;
                    boolean range = codeMethod.opcode >= 0x74 && codeMethod.opcode <= 0x78;
                    units.add(codeMethod.opcode | (count << (range ? 8 : 12)));
                    units.add(methodIndex);
                    if (codeMethod.argumentRegisters != null && range) {
                        throw new IllegalStateException(
                                "explicit register-list fixture requires a 35c invoke");
                    }
                    if (range) {
                        units.add(codeMethod.firstRegister);
                    } else {
                        int registers = 0;
                        for (int register = 0; register < Math.min(count, 4); register++) {
                            int registerIndex = codeMethod.argumentRegisters == null
                                    ? codeMethod.firstRegister + register
                                    : codeMethod.argumentRegisters[register];
                            registers |= registerIndex << (register * 4);
                        }
                        if (count > 4) {
                            int invokeUnit = units.size() - 2;
                            int fifthRegister = codeMethod.argumentRegisters == null
                                    ? codeMethod.firstRegister + 4
                                    : codeMethod.argumentRegisters[4];
                            units.set(invokeUnit,
                                    units.get(invokeUnit)
                                            | (fifthRegister << 8));
                        }
                        units.add(registers);
                    }
                }
            } else if (codeMethod.fieldTarget != null) {
                units.add(codeMethod.opcode);
                units.add(fieldIndexes.get(codeMethod.fieldTarget.key()));
            }
            if (codeMethod.catchTypeDescriptor != null) {
                units.add(0x0000); // nop covered by try_item
                units.add(0x000d); // move-exception v0
            }
            units.add(0x000e); // return-void
            int[] result = new int[units.size()];
            for (int i = 0; i < units.size(); i++) result[i] = units.get(i);
            return result;
        }

        private int appendAnnotationSet(ByteArrayOutputStream data, int dataOffset,
                                        List<AnnotationSpec> annotations,
                                        Map<String, Integer> typeIndexes,
                                        Map<String, Integer> stringIndexes,
                                        Map<String, Integer> fieldIndexes,
                                        Map<String, Integer> protoIndexes) throws IOException {
            ArrayList<byte[]> items = new ArrayList<>();
            ArrayList<AnnotationSpec> orderedAnnotations = new ArrayList<>(annotations);
            Collections.sort(orderedAnnotations, (left, right) ->
                    typeIndexes.get(left.typeDescriptor).compareTo(typeIndexes.get(right.typeDescriptor)));
            for (AnnotationSpec annotation : orderedAnnotations) {
                ByteArrayOutputStream item = new ByteArrayOutputStream();
                item.write(annotation.visibility);
                writeEncodedAnnotation(item, annotation, typeIndexes, stringIndexes,
                        fieldIndexes, protoIndexes);
                items.add(item.toByteArray());
            }
            align4(data);
            int setOffset = dataOffset + data.size();
            writeInt(data, items.size());
            int nextItemOffset = setOffset + 4 + items.size() * 4;
            for (byte[] item : items) {
                writeInt(data, nextItemOffset);
                nextItemOffset += item.length;
            }
            for (byte[] item : items) data.write(item);
            return setOffset;
        }

        private void writeEncodedAnnotation(ByteArrayOutputStream output,
                                            AnnotationSpec annotation,
                                            Map<String, Integer> typeIndexes,
                                            Map<String, Integer> stringIndexes,
                                            Map<String, Integer> fieldIndexes,
                                            Map<String, Integer> protoIndexes)
                throws IOException {
            writeUleb128(output, typeIndexes.get(annotation.typeDescriptor));
            writeUleb128(output, annotation.elements.size());
            ArrayList<AnnotationElement> orderedElements = new ArrayList<>(annotation.elements);
            Collections.sort(orderedElements, (left, right) ->
                    stringIndexes.get(left.name).compareTo(stringIndexes.get(right.name)));
            for (AnnotationElement element : orderedElements) {
                writeUleb128(output, stringIndexes.get(element.name));
                writeEncodedValue(output, element.value, typeIndexes, stringIndexes,
                        fieldIndexes, protoIndexes);
            }
        }

        private void writeEncodedValue(ByteArrayOutputStream output, AnnotationValue value,
                                       Map<String, Integer> typeIndexes,
                                       Map<String, Integer> stringIndexes,
                                       Map<String, Integer> fieldIndexes,
                                       Map<String, Integer> protoIndexes)
                throws IOException {
            if (value.type == 0x18) {
                writeEncodedIndexValue(output, value.type, typeIndexes.get(value.descriptor));
            } else if (value.type == 0x1b || value.type == 0x19) {
                writeEncodedIndexValue(output, value.type, fieldIndexes.get(value.enumField.key()));
            } else if (value.type == 0x15) {
                writeEncodedIndexValue(output, value.type,
                        protoIndexes.get(methodDescriptor(value.methodType.returnType,
                                value.methodType.parameters)));
            } else if (value.type == 0x17) {
                writeEncodedIndexValue(output, value.type, stringIndexes.get(value.descriptor));
            } else if (value.type == 0x1c) {
                output.write(value.type);
                writeUleb128(output, value.arrayValues.size());
                for (AnnotationValue item : value.arrayValues) {
                    writeEncodedValue(output, item, typeIndexes, stringIndexes,
                            fieldIndexes, protoIndexes);
                }
            } else if (value.type == 0x1d) {
                output.write(value.type);
                writeEncodedAnnotation(output, value.nestedAnnotation, typeIndexes,
                        stringIndexes, fieldIndexes, protoIndexes);
            } else {
                throw new IOException("unsupported test annotation value kind");
            }
        }

        private static void writeEncodedIndexValue(ByteArrayOutputStream output, int valueType,
                                                   Integer boxedIndex) throws IOException {
            if (boxedIndex == null || boxedIndex < 0) {
                throw new IOException("test annotation index was not registered");
            }
            int value = boxedIndex;
            int byteCount = 1;
            while (byteCount < 4 && (value >>> (byteCount * 8)) != 0) byteCount++;
            output.write(valueType | ((byteCount - 1) << 5));
            for (int index = 0; index < byteCount; index++) {
                output.write((value >>> (index * 8)) & 0xff);
            }
        }

        private static String methodDescriptor(String returnType, List<String> parameters) {
            StringBuilder descriptor = new StringBuilder("(");
            for (String parameter : parameters) descriptor.append(parameter);
            return descriptor.append(')').append(returnType).toString();
        }

        private static Map<String, Integer> indexes(List<String> values) {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (int i = 0; i < values.size(); i++) result.put(values.get(i), i);
            return result;
        }

        private static void align4(ByteArrayOutputStream output) {
            while ((output.size() & 3) != 0) output.write(0);
        }

        private static void writeInt(ByteArrayOutputStream output, int value) {
            output.write(value & 255);
            output.write((value >>> 8) & 255);
            output.write((value >>> 16) & 255);
            output.write((value >>> 24) & 255);
        }

        private static void writeShort(ByteArrayOutputStream output, int value) {
            output.write(value & 255);
            output.write((value >>> 8) & 255);
        }

        private static void writeUleb128(ByteArrayOutputStream output, int value) {
            do {
                int next = value & 0x7f;
                value >>>= 7;
                if (value != 0) next |= 0x80;
                output.write(next);
            } while (value != 0);
        }

        private static void writeSleb128(ByteArrayOutputStream output, int value) {
            boolean more;
            do {
                int next = value & 0x7f;
                value >>= 7;
                boolean signBitSet = (next & 0x40) != 0;
                more = !((value == 0 && !signBitSet) || (value == -1 && signBitSet));
                if (more) next |= 0x80;
                output.write(next);
            } while (more);
        }

        private static byte[] encodeModifiedUtf8(String value) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int index = 0; index < value.length(); index++) {
                int character = value.charAt(index);
                if (character >= 0x0001 && character <= 0x007f) {
                    output.write(character);
                } else if (character <= 0x07ff) {
                    output.write(0xc0 | ((character >> 6) & 0x1f));
                    output.write(0x80 | (character & 0x3f));
                } else {
                    output.write(0xe0 | ((character >> 12) & 0x0f));
                    output.write(0x80 | ((character >> 6) & 0x3f));
                    output.write(0x80 | (character & 0x3f));
                }
            }
            return output.toByteArray();
        }
    }

    private static final class AnnotationSpec {
        final String typeDescriptor;
        final int visibility;
        final List<AnnotationElement> elements = new ArrayList<>();

        AnnotationSpec(String typeDescriptor, int visibility) {
            this.typeDescriptor = typeDescriptor;
            this.visibility = visibility;
        }

        AnnotationSpec add(String name, AnnotationValue value) {
            elements.add(new AnnotationElement(name, value));
            return this;
        }
    }

    private static final class AnnotationElement {
        final String name;
        final AnnotationValue value;

        AnnotationElement(String name, AnnotationValue value) {
            this.name = name;
            this.value = value;
        }
    }

    private static final class AnnotationValue {
        final int type;
        String descriptor;
        FieldId enumField;
        Proto methodType;
        AnnotationSpec nestedAnnotation;
        List<AnnotationValue> arrayValues = Collections.emptyList();

        private AnnotationValue(int type) { this.type = type; }

        static AnnotationValue type(String descriptor) {
            AnnotationValue value = new AnnotationValue(0x18);
            value.descriptor = descriptor;
            return value;
        }

        static AnnotationValue enumValue(String owner, String name, String type) {
            AnnotationValue value = new AnnotationValue(0x1b);
            value.enumField = new FieldId(owner, name, type);
            return value;
        }

        static AnnotationValue methodType(String returnType, List<String> parameters) {
            AnnotationValue value = new AnnotationValue(0x15);
            value.methodType = new Proto(returnType, new ArrayList<>(parameters));
            return value;
        }

        static AnnotationValue array(List<AnnotationValue> values) {
            AnnotationValue value = new AnnotationValue(0x1c);
            value.arrayValues = new ArrayList<>(values);
            return value;
        }

        static AnnotationValue nested(AnnotationSpec annotation) {
            AnnotationValue value = new AnnotationValue(0x1d);
            value.nestedAnnotation = annotation;
            return value;
        }

        static AnnotationValue field(String owner, String name, String type) {
            AnnotationValue value = new AnnotationValue(0x19);
            value.enumField = new FieldId(owner, name, type);
            return value;
        }
    }

    private static final class OffsetPatch {
        final int pointerOffset;
        final int targetOffset;

        OffsetPatch(int pointerOffset, int targetOffset) {
            this.pointerOffset = pointerOffset;
            this.targetOffset = targetOffset;
        }
    }

    private static final class AnnotationSetPatch {
        final int pointerOffset;
        final List<AnnotationSpec> annotations;

        AnnotationSetPatch(int pointerOffset, List<AnnotationSpec> annotations) {
            this.pointerOffset = pointerOffset;
            this.annotations = annotations;
        }
    }

    private static final class ParameterSetPatch {
        final int pointerOffset;
        final List<AnnotationSpec> annotations;

        ParameterSetPatch(int pointerOffset, List<AnnotationSpec> annotations) {
            this.pointerOffset = pointerOffset;
            this.annotations = annotations;
        }
    }

    private static final class ParameterListPatch {
        final int pointerOffset;
        final int parameterCount;
        final LinkedHashMap<Integer, List<AnnotationSpec>> byParameter;

        ParameterListPatch(int pointerOffset, int parameterCount,
                           LinkedHashMap<Integer, List<AnnotationSpec>> byParameter) {
            this.pointerOffset = pointerOffset;
            this.parameterCount = parameterCount;
            this.byParameter = byParameter;
        }
    }

    private static final class Proto {
        final String returnType;
        final List<String> parameters;

        Proto(String returnType, List<String> parameters) {
            this.returnType = returnType;
            this.parameters = parameters;
        }
    }

    private static final class FieldId {
        final String owner;
        final String name;
        final String fieldType;

        FieldId(String owner, String name, String fieldType) {
            this.owner = owner;
            this.name = name;
            this.fieldType = fieldType;
        }

        String key() { return owner + "->" + name + ":" + fieldType; }
    }

    private static final class MethodId {
        final String owner;
        final String name;
        final String prototype;

        MethodId(String owner, String name, String prototype) {
            this.owner = owner;
            this.name = name;
            this.prototype = prototype;
        }

        String key() { return owner + "->" + name + prototype; }

        int parameterWordCount() {
            int close = prototype.indexOf(')');
            int words = 0;
            for (int index = 1; index < close;) {
                char kind = prototype.charAt(index);
                boolean array = false;
                while (kind == '[') {
                    array = true;
                    kind = prototype.charAt(++index);
                }
                if (kind == 'L') index = prototype.indexOf(';', index) + 1;
                else index++;
                words += !array && (kind == 'J' || kind == 'D') ? 2 : 1;
            }
            return words;
        }
    }

    private static final class ClassDef {
        final String descriptor;
        final String parentDescriptor;

        ClassDef(String descriptor, String parentDescriptor) {
            this.descriptor = descriptor;
            this.parentDescriptor = parentDescriptor;
        }
    }

    private static final class CodeMethod {
        final MethodId method;
        final MethodId methodTarget;
        final FieldId fieldTarget;
        final String typeTarget;
        final FieldId precedingFieldTarget;
        final int precedingFieldOpcode;
        final int precedingFieldRegister;
        final int opcode;
        final boolean callerStatic;
        final int encodedWordCount;
        final int firstRegister;
        final int[] argumentRegisters;
        final int registersSize;
        final int incomingSize;
        String protoTarget;
        String catchTypeDescriptor;

        CodeMethod(MethodId method, MethodId methodTarget, FieldId fieldTarget,
                   String typeTarget, FieldId precedingFieldTarget,
                   int precedingFieldOpcode, int precedingFieldRegister, int opcode,
                   boolean callerStatic, int encodedWordCount, int firstRegister,
                   int[] argumentRegisters) {
            this.method = method;
            this.methodTarget = methodTarget;
            this.fieldTarget = fieldTarget;
            this.typeTarget = typeTarget;
            this.precedingFieldTarget = precedingFieldTarget;
            this.precedingFieldOpcode = precedingFieldOpcode;
            this.precedingFieldRegister = precedingFieldRegister;
            this.opcode = opcode;
            this.callerStatic = callerStatic;
            this.encodedWordCount = encodedWordCount;
            this.firstRegister = firstRegister;
            this.argumentRegisters = argumentRegisters == null ? null : argumentRegisters.clone();
            this.registersSize = 8;
            this.incomingSize = method.parameterWordCount() + (callerStatic ? 0 : 1);
        }
    }
}
