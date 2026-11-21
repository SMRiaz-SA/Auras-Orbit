package com.auras.orbit.extensions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads bounded DEX symbol tables from a CS3 ZIP and resolves host/dependency
 * descriptors without defining or initializing an archive class. External type
 * closure uses class hierarchies, member descriptors, classified code operands,
 * and bounded runtime/system annotation values.
 */
public final class Cs3DexCapabilityPreflight {
    private static final String HOST_PREFIX = "Lcom/lagradost/cloudstream3/";
    private static final String ANDROID_PLUGIN_DESCRIPTOR =
            "Lcom/lagradost/cloudstream3/plugins/Plugin;";
    private static final String BASE_PLUGIN_DESCRIPTOR =
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;";
    private static final String[] REQUIRED_BASE_PLUGIN_METHODS = {
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;->registerMainAPI(Lcom/lagradost/cloudstream3/MainAPI;)V",
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;->registerExtractorAPI(Lcom/lagradost/cloudstream3/utils/ExtractorApi;)V",
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;->setFilename(Ljava/lang/String;)V",
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;->load()V",
            "Lcom/lagradost/cloudstream3/plugins/BasePlugin;->beforeUnload()V"
    };
    private static final String[] REQUIRED_ANDROID_PLUGIN_METHODS = {
            "Lcom/lagradost/cloudstream3/plugins/Plugin;->load(Landroid/content/Context;)V",
            "Lcom/lagradost/cloudstream3/plugins/Plugin;->setOpenSettings(Lkotlin/jvm/functions/Function1;)V",
            "Lcom/lagradost/cloudstream3/plugins/Plugin;->getResources()Landroid/content/res/Resources;"
    };
    private static final String REQUIRED_RESOURCE_METHOD =
            "Lcom/lagradost/cloudstream3/plugins/Plugin;->setResources(Landroid/content/res/Resources;)V";
    private static final String OBJECT_CLONE_REFERENCE =
            "Ljava/lang/Object;->clone()Ljava/lang/Object;";
    private static final int MAX_DEX_ENTRY_BYTES = 96 * 1024 * 1024;
    private static final int MAX_TOTAL_DEX_BYTES = 192 * 1024 * 1024;
    private static final int MAX_DEX_ENTRIES = 16;
    private static final int MAX_DEX_TABLE_ITEMS = 500_000;
    private static final long MAX_TOTAL_DEX_TABLE_ITEMS = 2_000_000L;
    private static final int MAX_DECODED_STRING_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_DECODED_STRING_BYTES = 24L * 1024 * 1024;
    private static final int MAX_DECODED_STRING_CHARS = 1024 * 1024;
    private static final long MAX_TOTAL_DECODED_STRING_CHARS = 8L * 1024 * 1024;
    private static final int MAX_TOTAL_DECODED_STRINGS = 500_000;
    private static final int MAX_DEX_DESCRIPTOR_CHARS = 4096;
    private static final int MAX_DEX_MEMBER_NAME_CHARS = 1024;
    private static final int MAX_ANNOTATION_VALUE_NESTING = 64;
    private static final long MAX_TOTAL_DESCRIPTOR_CHARS = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_REFERENCE_CHARS = 32L * 1024 * 1024;
    // Bound reflective work independently from the DEX parser's table traversal limits.
    private static final int MAX_EXTERNAL_SYMBOL_CHECKS = 100_000;
    private static final long MAX_TOTAL_CODE_UNITS = 8_000_000L;
    private static final int MAX_TOTAL_MEMBER_USE_SITES = 500_000;

    private Cs3DexCapabilityPreflight() { }

    public static Result inspect(File archive, ClassLoader hostLoader) {
        return inspect(archive, null, false, hostLoader);
    }

    public static Result inspect(File archive, String manifestClassName,
                                 boolean requiresResources, ClassLoader hostLoader) {
        if (archive == null || !archive.isFile()) return Result.failure("package file is unavailable");
        if (hostLoader == null) return Result.failure("Orbit host class loader is unavailable");
        LinkedHashSet<String> allDexTypes = new LinkedHashSet<>();
        LinkedHashSet<String> allDexMethods = new LinkedHashSet<>();
        LinkedHashSet<String> allDexFields = new LinkedHashSet<>();
        LinkedHashMap<String, LinkedHashSet<MethodUse>> allMethodUses = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<FieldUse>> allFieldUses = new LinkedHashMap<>();
        LinkedHashSet<String> referencedHostTypes = new LinkedHashSet<>();
        LinkedHashSet<String> referencedHostMethods = new LinkedHashSet<>();
        LinkedHashSet<String> referencedHostFields = new LinkedHashSet<>();
        LinkedHashMap<String, String> classParents = new LinkedHashMap<>();
        ArrayList<String> errors = new ArrayList<>();
        int dexCount = 0;
        int totalBytes = 0;
        boolean archiveDexValid = true;
        DecodeBudget decodeBudget = new DecodeBudget();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isDexName(entry.getName())) continue;
                if (++dexCount > MAX_DEX_ENTRIES) throw new IOException("package contains more DEX entries than the preflight limit");
                long declaredSize = entry.getSize();
                if (declaredSize < 112 || declaredSize > MAX_DEX_ENTRY_BYTES) {
                    throw new IOException("DEX entry has an invalid or excessive declared size: " + entry.getName());
                }
                byte[] dex;
                try (InputStream input = zip.getInputStream(entry)) {
                    dex = readLimited(input, MAX_DEX_ENTRY_BYTES);
                }
                totalBytes += dex.length;
                if (totalBytes > MAX_TOTAL_DEX_BYTES) throw new IOException("package DEX data exceeds the preflight limit");
                DexRefs refs = DexRefs.parse(dex, decodeBudget);
                allDexTypes.addAll(refs.runtimeTypes);
                allDexMethods.addAll(refs.methods);
                allDexFields.addAll(refs.fields);
                mergeUses(allMethodUses, refs.methodUses);
                mergeUses(allFieldUses, refs.fieldUses);
                for (java.util.Map.Entry<String, String> hierarchy : refs.classParents.entrySet()) {
                    if (classParents.containsKey(hierarchy.getKey())) {
                        throw new IOException("conflicting class definitions");
                    }
                    classParents.put(hierarchy.getKey(), hierarchy.getValue());
                }
            }
        } catch (Throwable error) {
            // ZIP/DEX errors can embed entry names or identifiers supplied by the package.
            archiveDexValid = false;
            errors.add("archive DEX inspection failed (invalid or over the preflight limit)");
        }

        LinkedHashSet<String> pluginDefinedTypes = new LinkedHashSet<>(classParents.keySet());
        LinkedHashSet<String> referencedExternalTypes = new LinkedHashSet<>();
        LinkedHashSet<String> referencedExternalMethods = new LinkedHashSet<>();
        LinkedHashSet<String> referencedExternalFields = new LinkedHashSet<>();
        for (String descriptor : allDexTypes) {
            String component = objectComponentDescriptor(descriptor);
            if (component == null || pluginDefinedTypes.contains(component)) continue;
            if (component.startsWith(HOST_PREFIX)) referencedHostTypes.add(descriptor);
            else referencedExternalTypes.add(descriptor);
        }
        for (String reference : allDexMethods) {
            String owner = methodOwnerDescriptor(reference);
            if (owner == null || descriptorContainsPluginType(owner, pluginDefinedTypes)) continue;
            String ownerComponent = objectComponentDescriptor(owner);
            if (ownerComponent != null && ownerComponent.startsWith(HOST_PREFIX)) {
                referencedHostMethods.add(reference);
            }
            else referencedExternalMethods.add(reference);
        }
        for (String reference : allDexFields) {
            String owner = memberOwnerDescriptor(reference);
            if (owner == null || pluginDefinedTypes.contains(owner)) continue;
            if (owner.startsWith(HOST_PREFIX)) referencedHostFields.add(reference);
            else referencedExternalFields.add(reference);
        }

        if (dexCount == 0) errors.add("archive contains no classes*.dex entry");
        if (manifestClassName == null || manifestClassName.trim().isEmpty()) {
            errors.add("manifest plugin class name is missing");
        } else {
            String pluginClass = toDescriptor(manifestClassName.trim());
            if (!classParents.containsKey(pluginClass)) errors.add("manifest plugin class is absent from DEX");
            if (archiveDexValid && !hasParent(classParents, pluginClass, BASE_PLUGIN_DESCRIPTOR, hostLoader)) {
                errors.add("manifest plugin class does not extend Orbit BasePlugin in its DEX hierarchy");
            }
            if (archiveDexValid && requiresResources && !hasParent(classParents, pluginClass,
                    ANDROID_PLUGIN_DESCRIPTOR, hostLoader)) {
                errors.add("resource-bearing manifest requires the Android Plugin base class");
            }
        }
        ArrayList<String> missingTypes = new ArrayList<>();
        ExternalResolutionBudget resolutionBudget = new ExternalResolutionBudget();
        if (archiveDexValid) {
            for (String descriptor : referencedHostTypes) {
                try {
                    String hostType = hostComponentDescriptor(descriptor);
                    if (hostType == null) continue;
                    Class<?> type = Class.forName(toClassName(hostType), false, hostLoader);
                    if (!Modifier.isPublic(type.getModifiers())) missingTypes.add(descriptor);
                } catch (Throwable error) { missingTypes.add(descriptor); }
            }
        }
        ArrayList<String> missingExternalTypes = new ArrayList<>();
        LinkedHashSet<String> missingExternalMethods = new LinkedHashSet<>();
        LinkedHashSet<String> missingExternalFields = new LinkedHashSet<>();
        boolean externalResolutionComplete = archiveDexValid;
        if (archiveDexValid) {
            try {
                for (String descriptor : referencedExternalTypes) {
                    resolutionBudget.consumeSymbolCheck();
                    try {
                        String component = objectComponentDescriptor(descriptor);
                        if (component == null) continue;
                        Class<?> type = Class.forName(toClassName(component), false, hostLoader);
                        if (!Modifier.isPublic(type.getModifiers())) missingExternalTypes.add(descriptor);
                    } catch (Throwable ignored) { missingExternalTypes.add(descriptor); }
                }
            } catch (IOException overBudget) {
                externalResolutionComplete = false;
                errors.add("external symbol resolution exceeded the preflight limit");
            }
        }
        LinkedHashSet<String> missingMethods = new LinkedHashSet<>();
        // DEX invoke instructions for inherited methods are commonly owned by
        // the plugin subclass, so those method_ids do not start with HOST_PREFIX.
        // These are host reflection/registration calls or inherited methods
        // whose DEX method_id owner may be the plugin subclass.
        for (String reference : REQUIRED_BASE_PLUGIN_METHODS) {
            if (!hasMethod(hostLoader, reference)) missingMethods.add(reference);
        }
        if (archiveDexValid && hasAndroidPluginSubclass(classParents, hostLoader)) {
            for (String reference : REQUIRED_ANDROID_PLUGIN_METHODS) {
                if (!hasMethod(hostLoader, reference)) missingMethods.add(reference);
            }
        }
        if (requiresResources && !hasMethod(hostLoader, REQUIRED_RESOURCE_METHOD)) {
            missingMethods.add(REQUIRED_RESOURCE_METHOD);
        }
        if (archiveDexValid) {
            for (String reference : referencedHostMethods) {
                if (!hasMethodReference(hostLoader, reference, pluginDefinedTypes,
                        allMethodUses.get(reference), classParents)) missingMethods.add(reference);
            }
        }
        ArrayList<String> missingFields = new ArrayList<>();
        if (archiveDexValid) {
            for (String reference : referencedHostFields) {
                if (!hasFieldReference(hostLoader, reference, pluginDefinedTypes,
                        allFieldUses.get(reference), classParents)) missingFields.add(reference);
            }
        }
        if (externalResolutionComplete) {
            try {
                for (String reference : referencedExternalMethods) {
                    resolutionBudget.consumeSymbolCheck();
                    if (!hasMethodReference(hostLoader, reference, pluginDefinedTypes,
                            allMethodUses.get(reference), classParents)) {
                        missingExternalMethods.add(reference);
                    }
                }
                for (String reference : referencedExternalFields) {
                    resolutionBudget.consumeSymbolCheck();
                    if (!hasFieldReference(hostLoader, reference, pluginDefinedTypes,
                            allFieldUses.get(reference), classParents)) {
                        missingExternalFields.add(reference);
                    }
                }
            } catch (IOException overBudget) {
                externalResolutionComplete = false;
                errors.add("external symbol resolution exceeded the preflight limit");
            }
        }
        if (!missingExternalTypes.isEmpty() || !missingExternalMethods.isEmpty()
                || !missingExternalFields.isEmpty()) {
            errors.add("unresolved external dependencies: types=" + missingExternalTypes.size()
                    + " methods=" + missingExternalMethods.size()
                    + " fields=" + missingExternalFields.size());
        }
        return new Result(dexCount, totalBytes, referencedHostTypes, referencedHostMethods,
                referencedHostFields, missingTypes, new ArrayList<>(missingMethods), missingFields,
                referencedExternalTypes, referencedExternalMethods, referencedExternalFields,
                missingExternalTypes, new ArrayList<>(missingExternalMethods),
                new ArrayList<>(missingExternalFields), pluginDefinedTypes.size(),
                externalResolutionComplete, errors);
    }

    private static boolean isDexName(String name) {
        return name != null && name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    }

    private static <T> void mergeUses(Map<String, LinkedHashSet<T>> target,
                                      Map<String, LinkedHashSet<T>> source) {
        for (Map.Entry<String, LinkedHashSet<T>> entry : source.entrySet()) {
            LinkedHashSet<T> current = target.get(entry.getKey());
            if (current == null) {
                current = new LinkedHashSet<>();
                target.put(entry.getKey(), current);
            }
            current.addAll(entry.getValue());
        }
    }

    private static boolean hasMethod(ClassLoader loader, String reference) {
        return hasMethod(loader, reference, Collections.emptySet());
    }

    private static boolean hasMethod(ClassLoader loader, String reference,
                                     Set<String> pluginDefinedTypes) {
        return findMethod(loader, reference, pluginDefinedTypes) != null;
    }

    private static ResolvedMethod findMethod(ClassLoader loader, String reference,
                                             Set<String> pluginDefinedTypes) {
        int arrow = reference.indexOf("->");
        int open = reference.indexOf('(', arrow + 2);
        if (arrow <= 0 || open <= arrow) return null;
        String ownerDescriptor = reference.substring(0, arrow);
        if (pluginDefinedTypes.contains(ownerDescriptor)) return null;
        String name = reference.substring(arrow + 2, open);
        int close = reference.indexOf(')', open + 1);
        if (close < 0) return null;
        String descriptor = reference.substring(open + 1, close);
        String returnDescriptor = reference.substring(close + 1);
        // A parent-loaded dependency cannot expose a child/archive-defined class in its method ABI.
        // Treat the exact reference as unresolved without asking the host loader for that class.
        if (descriptorContainsPluginType(descriptor, pluginDefinedTypes)
                || descriptorContainsPluginType(returnDescriptor, pluginDefinedTypes)) return null;
        try {
            Class<?> owner = Class.forName(toClassName(ownerDescriptor), false, loader);
            Class<?>[] parameters = parseParameters(descriptor, loader);
            Class<?> returnType = parseType(returnDescriptor, loader);
            if ("<init>".equals(name)) {
                if (!"V".equals(returnDescriptor)) return null;
                Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
                return isPluginVisible(constructor.getModifiers())
                        ? new ResolvedMethod(constructor.getModifiers(), ownerDescriptor, true,
                                owner.isInterface(), owner.isInterface()) : null;
            }
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (isPluginVisible(method.getModifiers()) && method.getName().equals(name)
                            && method.getReturnType() == returnType
                            && exactParameters(method.getParameterTypes(), parameters)) {
                        return new ResolvedMethod(method.getModifiers(), toDescriptor(current.getName()),
                                false, owner.isInterface(), current.isInterface());
                    }
                }
            }
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                ResolvedMethod method = findInterfaceMethod(current.getInterfaces(), name,
                        returnType, parameters, owner.isInterface());
                if (method != null) return method;
            }
            return null;
        } catch (Throwable ignored) { return null; }
    }

    private static ResolvedMethod findInterfaceMethod(Class<?>[] interfaces, String name,
                                                      Class<?> returnType, Class<?>[] parameters,
                                                      boolean symbolicOwnerIsInterface) {
        for (Class<?> type : interfaces) {
            for (Method method : type.getDeclaredMethods()) {
                if (isPluginVisible(method.getModifiers()) && method.getName().equals(name)
                        && method.getReturnType() == returnType
                        && exactParameters(method.getParameterTypes(), parameters)) {
                    return new ResolvedMethod(method.getModifiers(), toDescriptor(type.getName()),
                            false, symbolicOwnerIsInterface, true);
                }
            }
            ResolvedMethod method = findInterfaceMethod(type.getInterfaces(), name,
                    returnType, parameters, symbolicOwnerIsInterface);
            if (method != null) return method;
        }
        return null;
    }

    private static boolean hasMethodReference(ClassLoader loader, String reference,
                                              Set<String> pluginDefinedTypes,
                                              Set<MethodUse> uses,
                                              Map<String, String> classParents) {
        if (uses == null || uses.isEmpty()) return false;
        if (OBJECT_CLONE_REFERENCE.equals(reference)) {
            for (MethodUse use : uses) {
                if ((use.opcode != 0x6e && use.opcode != 0x74)
                        || !use.arrayCloneReceiverProven) return false;
            }
            return true;
        }
        String owner = methodOwnerDescriptor(reference);
        if (isArrayDescriptor(owner)) {
            return hasArrayCloneReference(reference, owner, pluginDefinedTypes, uses);
        }
        ResolvedMethod method = findMethod(loader, reference, pluginDefinedTypes);
        if (method == null) return false;
        boolean isStatic = !method.constructor && Modifier.isStatic(method.modifiers);
        for (MethodUse use : uses) {
            if (use.annotationMemberValue) continue;
            if (methodStaticOpcode(use.opcode) != isStatic) return false;
            if (!methodInvokeKindLegal(method, reference, use, classParents, loader)) return false;
            if (Modifier.isProtected(method.modifiers)) {
                if (!isSubclassOf(classParents, use.callerClass, method.declaringType, loader)) return false;
                // invoke-super uses the caller's `this`; other instance calls need register-type
                // analysis to prove the protected receiver restriction, so fail those closed.
                // The direct-superclass constructor case is separately proven above by checking
                // invoke-direct, <init>, and the caller's direct DEX superclass.
                if (!isStatic && !method.constructor
                        && use.opcode != 0x6f && use.opcode != 0x75) return false;
            }
        }
        return true;
    }

    /**
     * DEX array classes have a VM-provided public clone method that reflection does not expose
     * as a declared method on the array Class object. Accept only its exact virtual call shape.
     */
    private static boolean hasArrayCloneReference(String reference, String ownerDescriptor,
                                                  Set<String> pluginDefinedTypes,
                                                  Set<MethodUse> uses) {
        if (descriptorContainsPluginType(ownerDescriptor, pluginDefinedTypes)
                || !reference.equals(ownerDescriptor + "->clone()Ljava/lang/Object;")) return false;
        for (MethodUse use : uses) {
            if (use.opcode != 0x6e && use.opcode != 0x74) return false;
        }
        return true;
    }

    private static boolean methodInvokeKindLegal(ResolvedMethod method, String reference,
                                                 MethodUse use, Map<String, String> classParents,
                                                 ClassLoader hostLoader) {
        int arrow = reference.indexOf("->");
        int open = reference.indexOf('(', arrow + 2);
        String name = reference.substring(arrow + 2, open);
        int opcode = use.opcode;
        boolean direct = opcode == 0x70 || opcode == 0x76;
        boolean virtual = opcode == 0x6e || opcode == 0x74;
        boolean invokeSuper = opcode == 0x6f || opcode == 0x75;
        boolean invokeInterface = opcode == 0x72 || opcode == 0x78;
        if (method.constructor || "<init>".equals(name)) {
            if (!method.constructor || !"<init>".equals(name) || !direct) return false;
            if (!Modifier.isProtected(method.modifiers)) return true;
            String owner = memberOwnerDescriptor(reference);
            return !use.callerStatic && use.callerConstructor && owner != null
                    && use.protectedConstructorReceiverIsThis
                    && owner.equals(classParents.get(use.callerClass));
        }
        if (methodStaticOpcode(opcode)) {
            if (!method.declaringTypeIsInterface) return true;
            // Interface static methods are not inherited; the DEX reference must name
            // the interface that actually declares the method.
            return method.ownerIsInterface
                    && method.declaringType.equals(memberOwnerDescriptor(reference));
        }
        if (direct) return false;
        if (invokeInterface) return method.ownerIsInterface;
        if (virtual) return !method.ownerIsInterface;
        if (invokeSuper) {
            String owner = memberOwnerDescriptor(reference);
            // Interface-super linkage needs the class-def interface list, which this pass does
            // not currently parse; leave that valid but unproven form closed.
            return !method.ownerIsInterface && !use.callerStatic
                    && !Modifier.isStatic(method.modifiers)
                    && isStrictSubclassOf(classParents, use.callerClass, owner, hostLoader);
        }
        return false;
    }

    private static boolean hasField(ClassLoader loader, String reference) {
        return hasField(loader, reference, Collections.emptySet());
    }

    private static boolean hasField(ClassLoader loader, String reference,
                                    Set<String> pluginDefinedTypes) {
        return findField(loader, reference, pluginDefinedTypes) != null;
    }

    private static ResolvedField findField(ClassLoader loader, String reference,
                                           Set<String> pluginDefinedTypes) {
        int arrow = reference.indexOf("->");
        int colon = reference.indexOf(':', arrow + 2);
        if (arrow <= 0 || colon <= arrow) return null;
        String ownerDescriptor = reference.substring(0, arrow);
        if (pluginDefinedTypes.contains(ownerDescriptor)) return null;
        String name = reference.substring(arrow + 2, colon);
        String fieldDescriptor = reference.substring(colon + 1);
        if (descriptorContainsPluginType(fieldDescriptor, pluginDefinedTypes)) return null;
        try {
            Class<?> owner = Class.forName(toClassName(ownerDescriptor), false, loader);
            Class<?> fieldType = parseType(fieldDescriptor, loader);
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    return isPluginVisible(field.getModifiers()) && field.getType() == fieldType
                            ? new ResolvedField(field.getModifiers(), toDescriptor(current.getName())) : null;
                } catch (NoSuchFieldException ignored) { }
            }
            for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
                for (Class<?> type : current.getInterfaces()) {
                    ResolvedField field = findInterfaceField(type, name, fieldType);
                    if (field != null) return field;
                }
            }
            return null;
        } catch (Throwable ignored) { return null; }
    }

    private static ResolvedField findInterfaceField(Class<?> type, String name, Class<?> fieldType) {
        try {
            Field field = type.getDeclaredField(name);
            return isPluginVisible(field.getModifiers()) && field.getType() == fieldType
                    ? new ResolvedField(field.getModifiers(), toDescriptor(type.getName())) : null;
        } catch (NoSuchFieldException ignored) {
            for (Class<?> parent : type.getInterfaces()) {
                ResolvedField field = findInterfaceField(parent, name, fieldType);
                if (field != null) return field;
            }
            return null;
        }
    }

    private static boolean hasFieldReference(ClassLoader loader, String reference,
                                             Set<String> pluginDefinedTypes,
                                             Set<FieldUse> uses,
                                             Map<String, String> classParents) {
        if (uses == null || uses.isEmpty()) return false;
        ResolvedField field = findField(loader, reference, pluginDefinedTypes);
        if (field == null) return false;
        boolean isStatic = Modifier.isStatic(field.modifiers);
        for (FieldUse use : uses) {
            if (use.annotationMemberValue) continue;
            if (fieldStaticOpcode(use.opcode) != isStatic) return false;
            if (use.annotationEnumValue
                    && (!Modifier.isPublic(field.modifiers) || !isStatic
                    || !Modifier.isFinal(field.modifiers)
                    || !field.declaringType.equals(memberOwnerDescriptor(reference)))) return false;
            if (Modifier.isProtected(field.modifiers)) {
                if (!isStatic || !isSubclassOf(classParents, use.callerClass,
                        field.declaringType, loader)) return false;
            }
        }
        return true;
    }

    private static boolean methodStaticOpcode(int opcode) {
        return opcode == 0x71 || opcode == 0x77;
    }

    private static boolean isDexTypeReferenceOpcode(int opcode) {
        return opcode == 0x1c || opcode == 0x1f || opcode == 0x20
                || opcode == 0x22 || opcode == 0x23 || opcode == 0x24 || opcode == 0x25;
    }

    private static String fieldTypeDescriptor(String reference) {
        if (reference == null) return null;
        int arrow = reference.indexOf("->");
        int colon = reference.indexOf(':', arrow + 2);
        return arrow <= 0 || colon <= arrow ? null : reference.substring(colon + 1);
    }

    private static int invokeReceiverRegister(DexReader reader, int instructionAddress,
                                              int opcode) throws IOException {
        if (opcode >= 0x74 && opcode <= 0x78) {
            return reader.u16(instructionAddress + 4);
        }
        return reader.u16(instructionAddress + 4) & 0x0f;
    }

    private static boolean fieldStaticOpcode(int opcode) {
        return opcode >= 0x60 && opcode <= 0x6d;
    }

    private static Class<?>[] parseParameters(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        ArrayList<Class<?>> values = new ArrayList<>();
        for (int index = 0; index < descriptor.length();) {
            int end = descriptorEnd(descriptor, index);
            values.add(parseType(descriptor.substring(index, end), loader));
            index = end;
        }
        return values.toArray(new Class<?>[0]);
    }

    private static int descriptorEnd(String descriptor, int start) {
        int index = start;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') index++;
        if (index >= descriptor.length()) throw new IllegalArgumentException("truncated type descriptor");
        if (descriptor.charAt(index) == 'L') {
            int end = descriptor.indexOf(';', index);
            if (end < 0) throw new IllegalArgumentException("invalid object descriptor");
            return end + 1;
        }
        return index + 1;
    }

    private static void addMethodDescriptorTypes(String descriptor, Set<String> types)
            throws IOException {
        int open = descriptor.indexOf('(');
        int close = descriptor.indexOf(')', open + 1);
        if (open != 0 || close < 0) throw new IOException("invalid DEX method descriptor");
        for (int index = open + 1; index < close;) {
            int end = descriptorEnd(descriptor, index);
            if (end > close) throw new IOException("invalid DEX method parameter descriptor");
            types.add(descriptor.substring(index, end));
            index = end;
        }
        String returnType = descriptor.substring(close + 1);
        if (returnType.isEmpty() || !isValidTypeDescriptor(returnType)) {
            throw new IOException("invalid DEX method return descriptor");
        }
        types.add(returnType);
    }

    private static Class<?> parseType(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        if (descriptor.length() == 1) {
            switch (descriptor.charAt(0)) {
                case 'V': return void.class;
                case 'Z': return boolean.class;
                case 'B': return byte.class;
                case 'C': return char.class;
                case 'S': return short.class;
                case 'I': return int.class;
                case 'J': return long.class;
                case 'F': return float.class;
                case 'D': return double.class;
                default: throw new IllegalArgumentException("invalid primitive descriptor " + descriptor);
            }
        }
        String className;
        if (descriptor.charAt(0) == '[') className = descriptor.replace('/', '.');
        else className = toClassName(descriptor);
        return Class.forName(className, false, loader);
    }

    private static String toClassName(String descriptor) {
        if (descriptor == null || descriptor.length() < 2 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("invalid class descriptor " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String toDescriptor(String className) {
        if (className.startsWith("L") && className.endsWith(";")) return className.replace('.', '/');
        return "L" + className.replace('.', '/') + ";";
    }

    /** Returns the referenced CloudStream object descriptor for an object or object-array type ID. */
    private static String hostComponentDescriptor(String descriptor) {
        String component = objectComponentDescriptor(descriptor);
        return component != null && component.startsWith(HOST_PREFIX) ? component : null;
    }

    /** Returns an object component descriptor from an object or object-array DEX type. */
    private static String objectComponentDescriptor(String descriptor) {
        if (descriptor == null) return null;
        int index = 0;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') index++;
        if (index >= descriptor.length()) return null;
        String component = descriptor.substring(index);
        return component.startsWith("L") && component.endsWith(";") ? component : null;
    }

    private static boolean isValidTypeDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return false;
        int index = 0;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') index++;
        if (index > 255 || index >= descriptor.length()) return false;
        String component = descriptor.substring(index);
        if (component.length() == 1) {
            char kind = component.charAt(0);
            return kind == 'Z' || kind == 'B' || kind == 'C' || kind == 'S'
                    || kind == 'I' || kind == 'J' || kind == 'F' || kind == 'D'
                    || (index == 0 && kind == 'V');
        }
        return isClassDescriptor(component);
    }

    private static boolean isClassDescriptor(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') return false;
        for (int index = 1; index < descriptor.length() - 1; index++) {
            char value = descriptor.charAt(index);
            if (value == '.' || value == ';' || value == '[' || value == '\0') return false;
        }
        return true;
    }

    private static String memberOwnerDescriptor(String reference) {
        if (reference == null) return null;
        int arrow = reference.indexOf("->");
        if (arrow <= 0) return null;
        String owner = reference.substring(0, arrow);
        return owner.startsWith("L") && owner.endsWith(";") ? owner : null;
    }

    private static String methodOwnerDescriptor(String reference) {
        if (reference == null) return null;
        int arrow = reference.indexOf("->");
        if (arrow <= 0) return null;
        String owner = reference.substring(0, arrow);
        return isClassDescriptor(owner) || isArrayDescriptor(owner) ? owner : null;
    }

    private static boolean isArrayDescriptor(String descriptor) {
        return descriptor != null && descriptor.startsWith("[")
                && isValidTypeDescriptor(descriptor);
    }

    /** Checks descriptors without resolving any archive-defined class through the host loader. */
    private static boolean descriptorContainsPluginType(String descriptor,
                                                        Set<String> pluginDefinedTypes) {
        if (descriptor == null || pluginDefinedTypes.isEmpty()) return false;
        for (int index = 0; index < descriptor.length();) {
            while (index < descriptor.length() && descriptor.charAt(index) == '[') index++;
            if (index >= descriptor.length()) return false;
            if (descriptor.charAt(index) == 'L') {
                int end = descriptor.indexOf(';', index);
                if (end < 0) return false;
                if (pluginDefinedTypes.contains(descriptor.substring(index, end + 1))) return true;
                index = end + 1;
            } else {
                index++;
            }
        }
        return false;
    }

    private static boolean hasParent(java.util.Map<String, String> parents, String start,
                                     String expected, ClassLoader hostLoader) {
        String current = start;
        for (int depth = 0; depth < 256 && current != null; depth++) {
            if (expected.equals(current)) return true;
            String dexParent = parents.get(current);
            if (dexParent != null) {
                current = dexParent;
                continue;
            }
            // CloudStream's Android Plugin class lives in the host, not in the CS3 archive.
            // Verify its host-declared superclass edge instead of treating the missing DEX
            // definition as a broken plugin hierarchy.
            if (isHostType(current) && isHostType(expected)) {
                try {
                    Class<?> expectedType = Class.forName(toClassName(expected), false, hostLoader);
                    Class<?> currentType = Class.forName(toClassName(current), false, hostLoader);
                    return expectedType.isAssignableFrom(currentType);
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean isSubclassOf(Map<String, String> parents, String child,
                                        String expectedParent, ClassLoader hostLoader) {
        String current = child;
        for (int depth = 0; depth < 256 && current != null; depth++) {
            if (expectedParent.equals(current)) return true;
            if (parents.containsKey(current)) {
                current = parents.get(current);
                continue;
            }
            if (!isClassDescriptor(current) || !isClassDescriptor(expectedParent)) return false;
            try {
                Class<?> expectedType = Class.forName(toClassName(expectedParent), false, hostLoader);
                Class<?> currentType = Class.forName(toClassName(current), false, hostLoader);
                return expectedType.isAssignableFrom(currentType);
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** Invoke-super resolves through a superclass and cannot name the caller class itself. */
    private static boolean isStrictSubclassOf(Map<String, String> parents, String child,
                                              String expectedParent, ClassLoader hostLoader) {
        if (child == null || expectedParent == null || child.equals(expectedParent)
                || !parents.containsKey(child)) return false;
        String directParent = parents.get(child);
        return directParent != null && isSubclassOf(parents, directParent, expectedParent, hostLoader);
    }

    private static boolean isHostType(String descriptor) {
        return descriptor != null && descriptor.startsWith(HOST_PREFIX)
                && descriptor.endsWith(";");
    }

    private static boolean hasAndroidPluginSubclass(java.util.Map<String, String> parents,
                                                    ClassLoader hostLoader) {
        for (String descriptor : parents.keySet()) {
            if (hasParent(parents, descriptor, ANDROID_PLUGIN_DESCRIPTOR, hostLoader)) return true;
        }
        return false;
    }

    private static boolean exactParameters(Class<?>[] left, Class<?>[] right) {
        if (left.length != right.length) return false;
        for (int index = 0; index < left.length; index++) if (left[index] != right[index]) return false;
        return true;
    }

    private static boolean isPluginVisible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) throw new IOException("Input stream made no progress.");
            if (output.size() + count > maximum) throw new IOException("DEX entry exceeds the preflight limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static final class DexRefs {
        final Set<String> types = new LinkedHashSet<>();
        final Set<String> runtimeTypes = new LinkedHashSet<>();
        final Set<String> methods = new LinkedHashSet<>();
        final Set<String> fields = new LinkedHashSet<>();
        final java.util.Map<String, String> classParents = new LinkedHashMap<>();
        final Map<String, LinkedHashSet<MethodUse>> methodUses = new LinkedHashMap<>();
        final Map<String, LinkedHashSet<FieldUse>> fieldUses = new LinkedHashMap<>();
        private String[] typeDescriptors;
        private String[] prototypeDescriptors;
        private int dexVersion;

        static DexRefs parse(byte[] data, DecodeBudget decodeBudget) throws IOException {
            DexReader reader = new DexReader(data, decodeBudget);
            if (data.length < 112 || data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n') {
                throw new IOException("invalid DEX header");
            }
            if (data[7] != 0 || data[4] < '0' || data[4] > '9'
                    || data[5] < '0' || data[5] > '9'
                    || data[6] < '0' || data[6] > '9') {
                throw new IOException("invalid DEX version");
            }
            int dexVersion = (data[4] - '0') * 100 + (data[5] - '0') * 10 + data[6] - '0';
            int fileSize = reader.i32(32);
            if (fileSize < 112 || fileSize > data.length || reader.i32(40) != 0x12345678) {
                throw new IOException("invalid DEX size or byte order");
            }
            reader.setLogicalLimit(fileSize);
            int mapOffset = reader.i32(52);
            if (mapOffset != 0) rejectUnclassifiedHandleTables(reader, mapOffset, decodeBudget);
            int stringCount = reader.checkedCount(reader.i32(56), "string_ids");
            int stringOffset = reader.i32(60);
            int typeCount = reader.checkedCount(reader.i32(64), "type_ids");
            int typeOffset = reader.i32(68);
            int protoCount = reader.checkedCount(reader.i32(72), "proto_ids");
            int protoOffset = reader.i32(76);
            int fieldCount = reader.checkedCount(reader.i32(80), "field_ids");
            int fieldOffset = reader.i32(84);
            int methodCount = reader.checkedCount(reader.i32(88), "method_ids");
            int methodOffset = reader.i32(92);
            int classCount = reader.checkedCount(reader.i32(96), "class_defs");
            int classOffset = reader.i32(100);
            reader.checkTable(stringOffset, stringCount, 4, "string_ids");
            reader.checkTable(typeOffset, typeCount, 4, "type_ids");
            reader.checkTable(protoOffset, protoCount, 12, "proto_ids");
            reader.checkTable(fieldOffset, fieldCount, 8, "field_ids");
            reader.checkTable(methodOffset, methodCount, 8, "method_ids");
            reader.checkTable(classOffset, classCount, 32, "class_defs");
            decodeBudget.consumeTableItems((long) stringCount + typeCount + protoCount
                    + fieldCount + methodCount + classCount);
            DexRefs result = new DexRefs();
            result.dexVersion = dexVersion;
            String[] strings = new String[stringCount];
            for (int index = 0; index < stringCount; index++) {
                int dataOffset = reader.i32(reader.tableItemOffset(stringOffset, index, 4, "string_ids"));
                strings[index] = reader.stringData(dataOffset);
            }
            reader.clearDecodedStringCache();
            String[] descriptors = new String[typeCount];
            for (int index = 0; index < typeCount; index++) {
                int descriptorIndex = reader.u32Index(reader.tableItemOffset(typeOffset, index, 4, "type_ids"), stringCount, "type descriptor");
                descriptors[index] = strings[descriptorIndex];
                if (descriptors[index].length() > MAX_DEX_DESCRIPTOR_CHARS) {
                    throw new IOException("DEX type descriptor exceeds the preflight limit");
                }
                if (!isValidTypeDescriptor(descriptors[index])) {
                    throw new IOException("invalid DEX type descriptor");
                }
                if (objectComponentDescriptor(descriptors[index]) != null) {
                    decodeBudget.consumeReferenceChars(descriptors[index].length());
                }
                result.types.add(descriptors[index]);
            }
            result.typeDescriptors = descriptors;
            String[] prototypes = new String[protoCount];
            for (int index = 0; index < protoCount; index++) {
                int base = reader.tableItemOffset(protoOffset, index, 12, "proto_ids");
                int returnIndex = reader.u32Index(base + 4, typeCount, "prototype return type");
                int parametersOffset = reader.i32(base + 8);
                StringBuilder descriptor = new StringBuilder("(");
                if (parametersOffset != 0) {
                    int count = reader.checkedCount(reader.i32(parametersOffset), "prototype parameters");
                    reader.checkTable(parametersOffset + 4, count, 2, "prototype parameter types");
                    decodeBudget.consumeTableItems(count);
                    for (int item = 0; item < count; item++) {
                        int typeIndex = reader.u16(reader.tableItemOffset(parametersOffset + 4, item, 2, "prototype parameter types"));
                        if (typeIndex >= typeCount) throw new IOException("prototype type index out of range");
                        if ("V".equals(descriptors[typeIndex])) throw new IOException("void prototype parameter");
                        if (descriptor.length() > MAX_DEX_DESCRIPTOR_CHARS - descriptors[typeIndex].length()) {
                            throw new IOException("DEX method descriptor exceeds the preflight limit");
                        }
                        descriptor.append(descriptors[typeIndex]);
                    }
                }
                descriptor.append(')');
                if (descriptor.length() > MAX_DEX_DESCRIPTOR_CHARS - descriptors[returnIndex].length()) {
                    throw new IOException("DEX method descriptor exceeds the preflight limit");
                }
                prototypes[index] = descriptor.append(descriptors[returnIndex]).toString();
                decodeBudget.consumeDescriptorChars(prototypes[index].length());
            }
            result.prototypeDescriptors = prototypes;
            String[] fieldReferences = new String[fieldCount];
            for (int index = 0; index < fieldCount; index++) {
                int base = reader.tableItemOffset(fieldOffset, index, 8, "field_ids");
                int owner = reader.u16(base);
                int type = reader.u16(base + 2);
                int name = reader.u32Index(base + 4, stringCount, "field name");
                if (owner >= typeCount || type >= typeCount) throw new IOException("field type index out of range");
                if (!isClassDescriptor(descriptors[owner]) || "V".equals(descriptors[type])) {
                    throw new IOException("invalid DEX field descriptor");
                }
                if (strings[name].length() > MAX_DEX_MEMBER_NAME_CHARS) throw new IOException("DEX field name exceeds the preflight limit");
                String reference = descriptors[owner] + "->" + strings[name] + ":" + descriptors[type];
                decodeBudget.consumeReferenceChars(reference.length());
                if (!result.fields.add(reference)) throw new IOException("duplicate DEX field ID");
                result.runtimeTypes.add(descriptors[owner]);
                result.runtimeTypes.add(descriptors[type]);
                fieldReferences[index] = reference;
            }
            String[] methodReferences = new String[methodCount];
            for (int index = 0; index < methodCount; index++) {
                int base = reader.tableItemOffset(methodOffset, index, 8, "method_ids");
                int owner = reader.u16(base);
                int proto = reader.u16(base + 2);
                int name = reader.u32Index(base + 4, stringCount, "method name");
                if (owner >= typeCount || proto >= protoCount) throw new IOException("method index out of range");
                String ownerDescriptor = descriptors[owner];
                if (!isClassDescriptor(ownerDescriptor) && !isArrayDescriptor(ownerDescriptor)) {
                    throw new IOException("invalid DEX method owner");
                }
                if (strings[name].length() > MAX_DEX_MEMBER_NAME_CHARS) throw new IOException("DEX method name exceeds the preflight limit");
                String reference = ownerDescriptor + "->" + strings[name] + prototypes[proto];
                if (isArrayDescriptor(ownerDescriptor)
                        && !reference.equals(ownerDescriptor + "->clone()Ljava/lang/Object;")) {
                    throw new IOException("unsupported DEX array method reference");
                }
                decodeBudget.consumeReferenceChars(reference.length());
                if (!result.methods.add(reference)) throw new IOException("duplicate DEX method ID");
                result.runtimeTypes.add(ownerDescriptor);
                addMethodDescriptorTypes(prototypes[proto], result.runtimeTypes);
                methodReferences[index] = reference;
            }
            int[] classDataOffsets = new int[classCount];
            int[] annotationDirectoryOffsets = new int[classCount];
            String[] classDescriptors = new String[classCount];
            for (int index = 0; index < classCount; index++) {
                int base = reader.tableItemOffset(classOffset, index, 32, "class_defs");
                int classIndex = reader.u32Index(base, typeCount, "class definition");
                int superclassIndex = reader.i32(base + 8);
                if (!isClassDescriptor(descriptors[classIndex])) {
                    throw new IOException("invalid DEX class definition type");
                }
                String parent = superclassIndex == -1 ? null
                        : descriptors[reader.u32Index(base + 8, typeCount, "superclass")];
                if (parent != null && !isClassDescriptor(parent)) {
                    throw new IOException("invalid DEX superclass type");
                }
                if (result.classParents.containsKey(descriptors[classIndex])) {
                    throw new IOException("duplicate class definition");
                }
                result.classParents.put(descriptors[classIndex], parent);
                result.runtimeTypes.add(descriptors[classIndex]);
                if (parent != null) result.runtimeTypes.add(parent);
                int interfacesOffset = reader.i32(base + 12);
                if (interfacesOffset != 0) {
                    int interfaceCount = reader.checkedCount(reader.i32(interfacesOffset),
                            "class interfaces");
                    reader.checkTable(interfacesOffset + 4, interfaceCount, 2,
                            "class interface types");
                    decodeBudget.consumeTableItems(interfaceCount);
                    for (int interfaceIndex = 0; interfaceIndex < interfaceCount; interfaceIndex++) {
                        int typeIndex = reader.u16(reader.tableItemOffset(interfacesOffset + 4,
                                interfaceIndex, 2, "class interface type"));
                        if (typeIndex >= typeCount
                                || !isClassDescriptor(descriptors[typeIndex])) {
                            throw new IOException("invalid DEX class interface type");
                        }
                        result.runtimeTypes.add(descriptors[typeIndex]);
                    }
                }
                classDescriptors[index] = descriptors[classIndex];
                annotationDirectoryOffsets[index] = reader.i32(base + 20);
                classDataOffsets[index] = reader.i32(base + 24);
            }
            for (int index = 0; index < classCount; index++) {
                if (classDataOffsets[index] != 0) {
                    result.readClassData(reader, classDataOffsets[index], classDescriptors[index],
                            fieldReferences, methodReferences, decodeBudget);
                }
                if (annotationDirectoryOffsets[index] != 0) {
                    result.readAnnotationsDirectory(reader, annotationDirectoryOffsets[index],
                            classDescriptors[index], fieldReferences, methodReferences,
                            prototypes, stringCount, decodeBudget);
                }
            }
            return result;
        }

        private void readClassData(DexReader reader, int offset, String classDescriptor,
                                   String[] fieldReferences, String[] methodReferences,
                                   DecodeBudget decodeBudget) throws IOException {
            UlebResult staticFields = reader.uleb128(offset); offset = staticFields.nextOffset;
            UlebResult instanceFields = reader.uleb128(offset); offset = instanceFields.nextOffset;
            UlebResult directMethods = reader.uleb128(offset); offset = directMethods.nextOffset;
            UlebResult virtualMethods = reader.uleb128(offset); offset = virtualMethods.nextOffset;
            int staticFieldCount = reader.checkedCount(staticFields.value, "class static fields");
            int instanceFieldCount = reader.checkedCount(instanceFields.value, "class instance fields");
            int directMethodCount = reader.checkedCount(directMethods.value, "class direct methods");
            int virtualMethodCount = reader.checkedCount(virtualMethods.value, "class virtual methods");
            decodeBudget.consumeTableItems((long) staticFieldCount + instanceFieldCount
                    + directMethodCount + virtualMethodCount);
            offset = skipEncodedFields(reader, offset, staticFieldCount, classDescriptor, fieldReferences);
            offset = skipEncodedFields(reader, offset, instanceFieldCount, classDescriptor, fieldReferences);
            offset = readEncodedMethods(reader, offset, directMethodCount, classDescriptor,
                    methodReferences, fieldReferences, decodeBudget);
            readEncodedMethods(reader, offset, virtualMethodCount, classDescriptor,
                    methodReferences, fieldReferences, decodeBudget);
        }

        private void readAnnotationsDirectory(DexReader reader, int offset, String classDescriptor,
                                              String[] fieldReferences, String[] methodReferences,
                                              String[] prototypes, int stringCount,
                                              DecodeBudget decodeBudget) throws IOException {
            if ((offset & 3) != 0) throw new IOException("unaligned DEX annotations directory");
            reader.check(offset, 16);
            int classAnnotationsOffset = reader.i32(offset);
            int fieldCount = reader.checkedCount(reader.i32(offset + 4), "field annotations");
            int methodCount = reader.checkedCount(reader.i32(offset + 8), "method annotations");
            int parameterCount = reader.checkedCount(reader.i32(offset + 12),
                    "parameter annotations");
            decodeBudget.consumeTableItems((long) fieldCount + methodCount + parameterCount);
            long fieldItemsOffset = (long) offset + 16L;
            long methodItemsOffset = fieldItemsOffset + (long) fieldCount * 8L;
            long parameterItemsOffset = methodItemsOffset + (long) methodCount * 8L;
            if (parameterItemsOffset > Integer.MAX_VALUE) {
                throw new IOException("DEX annotations directory offset overflows");
            }
            reader.checkTable((int) fieldItemsOffset, fieldCount, 8, "field annotation entries");
            reader.checkTable((int) methodItemsOffset, methodCount, 8, "method annotation entries");
            reader.checkTable((int) parameterItemsOffset, parameterCount, 8,
                    "parameter annotation entries");

            if (classAnnotationsOffset != 0) {
                readAnnotationSet(reader, classAnnotationsOffset, classDescriptor, fieldReferences,
                        methodReferences, prototypes, stringCount, decodeBudget);
            }
            for (int index = 0; index < fieldCount; index++) {
                int item = reader.tableItemOffset((int) fieldItemsOffset, index, 8,
                        "field annotation entry");
                int fieldIndex = reader.u32Index(item, fieldReferences.length,
                        "annotated field");
                String reference = fieldReferences[fieldIndex];
                if (!classDescriptor.equals(memberOwnerDescriptor(reference))) {
                    throw new IOException("annotated field owner mismatch");
                }
                readAnnotationSet(reader, reader.i32(item + 4), classDescriptor, fieldReferences,
                        methodReferences, prototypes, stringCount, decodeBudget);
            }
            for (int index = 0; index < methodCount; index++) {
                int item = reader.tableItemOffset((int) methodItemsOffset, index, 8,
                        "method annotation entry");
                int methodIndex = reader.u32Index(item, methodReferences.length,
                        "annotated method");
                if (!classDescriptor.equals(memberOwnerDescriptor(methodReferences[methodIndex]))) {
                    throw new IOException("annotated method owner mismatch");
                }
                readAnnotationSet(reader, reader.i32(item + 4), classDescriptor, fieldReferences,
                        methodReferences, prototypes, stringCount, decodeBudget);
            }
            for (int index = 0; index < parameterCount; index++) {
                int item = reader.tableItemOffset((int) parameterItemsOffset, index, 8,
                        "parameter annotation entry");
                int methodIndex = reader.u32Index(item, methodReferences.length,
                        "parameter-annotated method");
                String methodReference = methodReferences[methodIndex];
                if (!classDescriptor.equals(memberOwnerDescriptor(methodReference))) {
                    throw new IOException("parameter annotation method owner mismatch");
                }
                int referenceListOffset = reader.i32(item + 4);
                if ((referenceListOffset & 3) != 0) {
                    throw new IOException("unaligned DEX parameter annotation list");
                }
                int parameterSets = reader.checkedCount(reader.i32(referenceListOffset),
                        "parameter annotation sets");
                int expectedParameters = methodParameterCount(methodReference);
                if (parameterSets > expectedParameters) {
                    throw new IOException("DEX parameter annotation list exceeds method prototype");
                }
                decodeBudget.consumeTableItems(parameterSets);
                reader.checkTable(referenceListOffset + 4, parameterSets, 4,
                        "parameter annotation set references");
                for (int parameter = 0; parameter < parameterSets; parameter++) {
                    int setOffset = reader.i32(reader.tableItemOffset(referenceListOffset + 4,
                            parameter, 4, "parameter annotation set reference"));
                    if (setOffset != 0) {
                        readAnnotationSet(reader, setOffset, classDescriptor, fieldReferences,
                                methodReferences, prototypes, stringCount, decodeBudget);
                    }
                }
            }
        }

        private void readAnnotationSet(DexReader reader, int offset, String classDescriptor,
                                       String[] fieldReferences, String[] methodReferences,
                                       String[] prototypes, int stringCount,
                                       DecodeBudget decodeBudget)
                throws IOException {
            if ((offset & 3) != 0) throw new IOException("unaligned DEX annotation set");
            int size = reader.checkedCount(reader.i32(offset), "annotation set");
            decodeBudget.consumeTableItems(size);
            reader.checkTable(offset + 4, size, 4, "annotation set entries");
            for (int index = 0; index < size; index++) {
                int annotationOffset = reader.i32(reader.tableItemOffset(offset + 4, index, 4,
                        "annotation set entry"));
                if (annotationOffset <= 0) throw new IOException("invalid DEX annotation item offset");
                int visibility = reader.u8(annotationOffset);
                if (visibility > 2) throw new IOException("invalid DEX annotation visibility");
                readEncodedAnnotation(reader, annotationOffset + 1, visibility,
                        classDescriptor, fieldReferences, methodReferences, prototypes,
                        stringCount, 0, decodeBudget);
            }
        }

        private int readEncodedAnnotation(DexReader reader, int offset, int visibility,
                                          String classDescriptor, String[] fieldReferences,
                                          String[] methodReferences, String[] prototypes,
                                          int stringCount, int depth,
                                          DecodeBudget decodeBudget) throws IOException {
            if (depth > MAX_ANNOTATION_VALUE_NESTING) {
                throw new IOException("DEX annotation nesting exceeds the preflight limit");
            }
            decodeBudget.consumeTableItems(1);
            UlebResult typeResult = reader.uleb128(offset);
            offset = typeResult.nextOffset;
            if (typeResult.value >= typeDescriptors.length
                    || !isClassDescriptor(typeDescriptors[typeResult.value])) {
                throw new IOException("invalid DEX annotation type index");
            }
            // Application-visible runtime annotations need their annotation class resolved.
            // System annotations are consumed by ART from their DEX descriptor and are not
            // exposed as ordinary application classes; their values still join dependency
            // closure below.
            if (visibility == 1) runtimeTypes.add(typeDescriptors[typeResult.value]);
            UlebResult elementCountResult = reader.uleb128(offset);
            int elementCount = reader.checkedCount(elementCountResult.value,
                    "annotation elements");
            decodeBudget.consumeTableItems(elementCount);
            offset = elementCountResult.nextOffset;
            for (int element = 0; element < elementCount; element++) {
                UlebResult nameResult = reader.uleb128(offset);
                offset = nameResult.nextOffset;
                if (nameResult.value >= stringCount) {
                    throw new IOException("DEX annotation element name index out of range");
                }
                offset = readEncodedValue(reader, offset, visibility, classDescriptor,
                        fieldReferences, methodReferences, prototypes, stringCount,
                        depth + 1, decodeBudget);
            }
            return offset;
        }

        private int readEncodedValue(DexReader reader, int offset, int visibility,
                                     String classDescriptor, String[] fieldReferences,
                                     String[] methodReferences, String[] prototypes,
                                     int stringCount, int depth,
                                     DecodeBudget decodeBudget) throws IOException {
            if (depth > MAX_ANNOTATION_VALUE_NESTING) {
                throw new IOException("DEX annotation nesting exceeds the preflight limit");
            }
            decodeBudget.consumeTableItems(1);
            int header = reader.u8(offset++);
            int valueType = header & 0x1f;
            int valueArg = (header >>> 5) & 0x07;
            switch (valueType) {
                case 0x00: return skipEncodedScalar(reader, offset, valueArg, 0);
                case 0x02: case 0x03: return skipEncodedScalar(reader, offset, valueArg, 1);
                case 0x04: return skipEncodedScalar(reader, offset, valueArg, 3);
                case 0x06: return skipEncodedScalar(reader, offset, valueArg, 7);
                case 0x10: return skipEncodedScalar(reader, offset, valueArg, 3);
                case 0x11: return skipEncodedScalar(reader, offset, valueArg, 7);
                case 0x17: {
                    int index = encodedValueIndex(reader, offset, valueArg);
                    if (index >= stringCount) throw new IOException("DEX annotation string index out of range");
                    return offset + valueArg + 1;
                }
                case 0x18: {
                    int index = encodedValueIndex(reader, offset, valueArg);
                    if (index >= typeDescriptors.length) throw new IOException("DEX annotation type value index out of range");
                    if (visibility != 0) runtimeTypes.add(typeDescriptors[index]);
                    return offset + valueArg + 1;
                }
                case 0x19: case 0x1a: {
                    int index = encodedValueIndex(reader, offset, valueArg);
                    if (valueType == 0x19 && index >= fieldReferences.length) {
                        throw new IOException("DEX annotation field value index out of range");
                    }
                    if (valueType == 0x1a && index >= methodReferences.length) {
                        throw new IOException("DEX annotation method value index out of range");
                    }
                    if (visibility != 0) {
                        // Keep valid DEX metadata member references in the exact dependency
                        // closure without interpreting them as bytecode accesses or invokes.
                        if (valueType == 0x19) {
                            addFieldUse(fieldReferences[index],
                                    FieldUse.annotationMemberValue(classDescriptor), decodeBudget);
                        } else {
                            addMethodUse(methodReferences[index],
                                    MethodUse.annotationMemberValue(classDescriptor), decodeBudget);
                        }
                    }
                    return offset + valueArg + 1;
                }
                case 0x15: {
                    int index = encodedValueIndex(reader, offset, valueArg);
                    if (index >= prototypes.length) throw new IOException("DEX annotation method type index out of range");
                    if (visibility != 0) addMethodDescriptorTypes(prototypes[index], runtimeTypes);
                    return offset + valueArg + 1;
                }
                case 0x16:
                    throw new IOException("DEX annotation method-handle value is unsupported");
                case 0x1b: {
                    int index = encodedValueIndex(reader, offset, valueArg);
                    if (index >= fieldReferences.length) throw new IOException("DEX annotation enum value index out of range");
                    if (visibility != 0) {
                        String reference = fieldReferences[index];
                        String owner = memberOwnerDescriptor(reference);
                        String enumType = fieldTypeDescriptor(reference);
                        if (owner == null || !owner.equals(enumType)) {
                            throw new IOException("invalid DEX annotation enum field");
                        }
                        runtimeTypes.add(owner);
                        runtimeTypes.add(enumType);
                        addFieldUse(reference, new FieldUse(classDescriptor, 0x60, true),
                                decodeBudget);
                    }
                    return offset + valueArg + 1;
                }
                case 0x1c: {
                    if (valueArg != 0) throw new IOException("invalid DEX annotation array value");
                    UlebResult sizeResult = reader.uleb128(offset);
                    int size = reader.checkedCount(sizeResult.value, "annotation array values");
                    decodeBudget.consumeTableItems(size);
                    offset = sizeResult.nextOffset;
                    for (int index = 0; index < size; index++) {
                        offset = readEncodedValue(reader, offset, visibility, classDescriptor,
                                fieldReferences, methodReferences, prototypes, stringCount,
                                depth + 1, decodeBudget);
                    }
                    return offset;
                }
                case 0x1d: {
                    if (valueArg != 0) throw new IOException("invalid DEX nested annotation value");
                    return readEncodedAnnotation(reader, offset, visibility, classDescriptor,
                            fieldReferences, methodReferences, prototypes, stringCount,
                            depth + 1, decodeBudget);
                }
                case 0x1e:
                    if (valueArg != 0) throw new IOException("invalid DEX annotation null value");
                    return offset;
                case 0x1f:
                    if (valueArg > 1) throw new IOException("invalid DEX annotation boolean value");
                    return offset;
                default:
                    throw new IOException("unsupported DEX annotation value kind");
            }
        }

        private int skipEncodedScalar(DexReader reader, int offset, int valueArg,
                                      int maxValueArg) throws IOException {
            if (valueArg > maxValueArg) throw new IOException("invalid DEX annotation scalar width");
            int byteCount = valueArg + 1;
            reader.check(offset, byteCount);
            return offset + byteCount;
        }

        private int encodedValueIndex(DexReader reader, int offset, int valueArg)
                throws IOException {
            if (valueArg > 3) throw new IOException("invalid DEX annotation index width");
            int byteCount = valueArg + 1;
            reader.check(offset, byteCount);
            long value = 0;
            for (int index = 0; index < byteCount; index++) {
                value |= (long) reader.u8(offset + index) << (index * 8);
            }
            if (value > Integer.MAX_VALUE) throw new IOException("DEX annotation index exceeds the preflight limit");
            return (int) value;
        }

        private int methodParameterCount(String reference) throws IOException {
            int arrow = reference.indexOf("->");
            int open = reference.indexOf('(', arrow + 2);
            int close = reference.indexOf(')', open + 1);
            if (arrow <= 0 || open <= arrow || close < 0) {
                throw new IOException("invalid DEX annotated method descriptor");
            }
            int count = 0;
            for (int index = open + 1; index < close;) {
                int end = descriptorEnd(reference, index);
                if (end > close) throw new IOException("invalid DEX annotated method parameter");
                count++;
                index = end;
            }
            return count;
        }

        private int skipEncodedFields(DexReader reader, int offset, int count,
                                     String classDescriptor, String[] fieldReferences) throws IOException {
            long fieldIndex = 0;
            for (int index = 0; index < count; index++) {
                UlebResult difference = reader.uleb128(offset); offset = difference.nextOffset;
                UlebResult accessFlags = reader.uleb128(offset); offset = accessFlags.nextOffset;
                fieldIndex += difference.value;
                if (fieldIndex < 0 || fieldIndex >= fieldReferences.length) {
                    throw new IOException("class field index out of range");
                }
                String reference = fieldReferences[(int) fieldIndex];
                if (reference == null || !classDescriptor.equals(memberOwnerDescriptor(reference))) {
                    throw new IOException("class field owner mismatch");
                }
            }
            return offset;
        }

        private int readEncodedMethods(DexReader reader, int offset, int count,
                                       String classDescriptor, String[] methodReferences,
                                       String[] fieldReferences, DecodeBudget decodeBudget) throws IOException {
            long methodIndex = 0;
            for (int index = 0; index < count; index++) {
                UlebResult difference = reader.uleb128(offset); offset = difference.nextOffset;
                UlebResult accessFlags = reader.uleb128(offset); offset = accessFlags.nextOffset;
                UlebResult codeOffset = reader.uleb128(offset); offset = codeOffset.nextOffset;
                methodIndex += difference.value;
                if (methodIndex < 0 || methodIndex >= methodReferences.length) {
                    throw new IOException("class method index out of range");
                }
                String reference = methodReferences[(int) methodIndex];
                if (reference == null || !classDescriptor.equals(memberOwnerDescriptor(reference))) {
                    throw new IOException("class method owner mismatch");
                }
                if (codeOffset.value != 0) {
                    int arrow = reference.indexOf("->");
                    int open = reference.indexOf('(', arrow + 2);
                    if (arrow <= 0 || open <= arrow) {
                        throw new IOException("invalid DEX caller method reference");
                    }
                    String callerMethodName = reference.substring(arrow + 2, open);
                    scanCodeItem(reader, codeOffset.value, classDescriptor, callerMethodName,
                            methodReferences, fieldReferences, (accessFlags.value & 0x8) != 0,
                            decodeBudget);
                }
            }
            return offset;
        }

        private void scanCodeItem(DexReader reader, int codeOffset, String callerClass,
                                  String callerMethodName,
                                  String[] methodReferences, String[] fieldReferences,
                                  boolean callerStatic, DecodeBudget decodeBudget) throws IOException {
            reader.check(codeOffset, 16);
            int registersSize = reader.u16(codeOffset);
            int incomingSize = reader.u16(codeOffset + 2);
            int triesSize = reader.u16(codeOffset + 6);
            if (incomingSize > registersSize) {
                throw new IOException("DEX code input registers exceed register bounds");
            }
            int instructionCount = reader.i32(codeOffset + 12);
            if (instructionCount < 0) throw new IOException("invalid DEX instruction count");
            decodeBudget.consumeInstructionUnits(instructionCount);
            int instructionOffset = codeOffset + 16;
            reader.checkTable(instructionOffset, instructionCount, 2, "code instructions");
            int programCounter = 0;
            int previousArraySgetRegister = -1;
            BitSet instructionBoundaries = new BitSet(instructionCount + 1);
            while (programCounter < instructionCount) {
                instructionBoundaries.set(programCounter);
                int instructionAddress = instructionOffset + programCounter * 2;
                int first = reader.u16(instructionAddress);
                int opcode = first & 0xff;
                int width = instructionWidth(reader, instructionOffset, programCounter,
                        instructionCount, first);
                if (width <= 0 || programCounter > instructionCount - width) {
                    throw new IOException("DEX instruction is truncated or invalid");
                }
                if (opcode == 0xfa || opcode == 0xfb || opcode == 0xfc
                        || opcode == 0xfd || opcode == 0xfe) {
                    throw new IOException("DEX polymorphic, call-site, or method-handle uses are not classified");
                }
                if (opcode == 0xff) {
                    if (dexVersion < 39) {
                        throw new IOException("DEX const-method-type requires version 039 or later");
                    }
                    int prototypeIndex = reader.u16(instructionAddress + 2);
                    if (prototypeIndex >= prototypeDescriptors.length) {
                        throw new IOException("DEX const-method-type prototype index out of range");
                    }
                    addMethodDescriptorTypes(prototypeDescriptors[prototypeIndex], runtimeTypes);
                }
                if (isDexTypeReferenceOpcode(opcode)) {
                    int typeIndex = reader.u16(instructionAddress + 2);
                    if (typeIndex >= typeDescriptors.length) {
                        throw new IOException("DEX code type index out of range");
                    }
                    runtimeTypes.add(typeDescriptors[typeIndex]);
                }
                int nextArraySgetRegister = -1;
                if (opcode >= 0x52 && opcode <= 0x6d) {
                    if (width < 2) throw new IOException("DEX field instruction is truncated");
                    int fieldIndex = reader.u16(instructionAddress + 2);
                    if (fieldIndex >= fieldReferences.length) throw new IOException("field instruction index out of range");
                    String reference = fieldReferences[fieldIndex];
                    addFieldUse(reference, new FieldUse(callerClass, opcode), decodeBudget);
                    if (opcode == 0x62) { // sget-object: candidate proof for a following Object.clone call.
                        int destinationRegister = (first >>> 8) & 0xff;
                        if (destinationRegister >= registersSize) {
                            throw new IOException("DEX sget-object register exceeds code register bounds");
                        }
                        String fieldType = fieldTypeDescriptor(reference);
                        if (isArrayDescriptor(fieldType)) nextArraySgetRegister = destinationRegister;
                    }
                } else if ((opcode >= 0x6e && opcode <= 0x72)
                        || (opcode >= 0x74 && opcode <= 0x78)) {
                    if (width < 3) throw new IOException("DEX method instruction is truncated");
                    int methodIndex = reader.u16(instructionAddress + 2);
                    if (methodIndex >= methodReferences.length) throw new IOException("method instruction index out of range");
                    String reference = methodReferences[methodIndex];
                    validateInvokeOperands(reader, instructionAddress, first, opcode,
                            registersSize, reference);
                    boolean arrayCloneReceiverProven = false;
                    boolean protectedConstructorReceiverIsThis = false;
                    int invocationReceiver = invokeReceiverRegister(reader, instructionAddress, opcode);
                    if (!callerStatic && incomingSize > 0) {
                        protectedConstructorReceiverIsThis = invocationReceiver
                                == registersSize - incomingSize;
                    }
                    if (OBJECT_CLONE_REFERENCE.equals(reference)
                            && (opcode == 0x6e || opcode == 0x74)) {
                        arrayCloneReceiverProven = previousArraySgetRegister
                                == invocationReceiver;
                    }
                    addMethodUse(reference, new MethodUse(callerClass, callerMethodName, opcode,
                            callerStatic, arrayCloneReceiverProven,
                            protectedConstructorReceiverIsThis), decodeBudget);
                }
                previousArraySgetRegister = nextArraySgetRegister;
                programCounter += width;
            }
            instructionBoundaries.set(instructionCount);
            if (triesSize > 0) {
                long triesOffset = (long) instructionOffset + (long) instructionCount * 2
                        + ((instructionCount & 1) == 0 ? 0 : 2);
                if (triesOffset > Integer.MAX_VALUE) throw new IOException("DEX try table offset overflows");
                scanCatchHandlers(reader, (int) triesOffset, triesSize, instructionCount,
                        instructionBoundaries, decodeBudget);
            }
        }

        private void scanCatchHandlers(DexReader reader, int triesOffset, int triesSize,
                                       int instructionCount, BitSet instructionBoundaries,
                                       DecodeBudget decodeBudget) throws IOException {
            decodeBudget.consumeTableItems(triesSize);
            reader.checkTable(triesOffset, triesSize, 8, "code try items");
            BitSet referencedHandlerOffsets = new BitSet(1 << 16);
            for (int index = 0; index < triesSize; index++) {
                int item = reader.tableItemOffset(triesOffset, index, 8, "code try item");
                int startAddress = reader.i32(item);
                int instructionLength = reader.u16(item + 4);
                int handlerOffset = reader.u16(item + 6);
                long endAddress = (long) startAddress + instructionLength;
                if (startAddress < 0 || instructionLength == 0 || endAddress > instructionCount
                        || !instructionBoundaries.get(startAddress)
                        || !instructionBoundaries.get((int) endAddress)) {
                    throw new IOException("invalid DEX try instruction range");
                }
                referencedHandlerOffsets.set(handlerOffset);
            }

            long handlerListLongOffset = (long) triesOffset + (long) triesSize * 8L;
            if (handlerListLongOffset > Integer.MAX_VALUE) {
                throw new IOException("DEX catch-handler list offset overflows");
            }
            int handlerListOffset = (int) handlerListLongOffset;
            UlebResult handlerCountResult = reader.uleb128(handlerListOffset);
            int handlerCount = reader.checkedCount(handlerCountResult.value, "catch handler list");
            decodeBudget.consumeTableItems(handlerCount);
            int cursor = handlerCountResult.nextOffset;
            BitSet actualHandlerOffsets = new BitSet(1 << 16);
            for (int handlerIndex = 0; handlerIndex < handlerCount; handlerIndex++) {
                int handlerRelativeOffset = cursor - handlerListOffset;
                if (handlerRelativeOffset <= 0xffff) actualHandlerOffsets.set(handlerRelativeOffset);
                SlebResult sizeResult = reader.sleb128(cursor);
                cursor = sizeResult.nextOffset;
                int signedSize = sizeResult.value;
                if (signedSize == Integer.MIN_VALUE) {
                    throw new IOException("DEX catch handler size overflows");
                }
                int typedCount = Math.abs(signedSize);
                int hasCatchAll = signedSize <= 0 ? 1 : 0;
                decodeBudget.consumeTableItems((long) typedCount + hasCatchAll);
                for (int typedIndex = 0; typedIndex < typedCount; typedIndex++) {
                    UlebResult typeIndexResult = reader.uleb128(cursor);
                    cursor = typeIndexResult.nextOffset;
                    if (typeIndexResult.value >= typeDescriptors.length
                            || !isClassDescriptor(typeDescriptors[typeIndexResult.value])) {
                        throw new IOException("invalid DEX catch type index");
                    }
                    UlebResult addressResult = reader.uleb128(cursor);
                    cursor = addressResult.nextOffset;
                    if (addressResult.value >= instructionCount
                            || !instructionBoundaries.get(addressResult.value)) {
                        throw new IOException("invalid DEX catch handler address");
                    }
                    runtimeTypes.add(typeDescriptors[typeIndexResult.value]);
                }
                if (hasCatchAll != 0) {
                    UlebResult addressResult = reader.uleb128(cursor);
                    cursor = addressResult.nextOffset;
                    if (addressResult.value >= instructionCount
                            || !instructionBoundaries.get(addressResult.value)) {
                        throw new IOException("invalid DEX catch-all handler address");
                    }
                }
            }
            BitSet unknownHandlerOffsets = (BitSet) referencedHandlerOffsets.clone();
            unknownHandlerOffsets.andNot(actualHandlerOffsets);
            if (!unknownHandlerOffsets.isEmpty()) {
                throw new IOException("DEX try item references an unknown catch handler");
            }
        }

        private static void rejectUnclassifiedHandleTables(DexReader reader, int mapOffset,
                                                            DecodeBudget decodeBudget) throws IOException {
            int itemCount = reader.checkedCount(reader.i32(mapOffset), "map list");
            reader.checkTable(mapOffset + 4, itemCount, 12, "map list");
            decodeBudget.consumeTableItems(itemCount);
            for (int index = 0; index < itemCount; index++) {
                int item = reader.tableItemOffset(mapOffset + 4, index, 12, "map list");
                int type = reader.u16(item);
                int size = reader.i32(item + 4);
                if (size < 0) throw new IOException("invalid DEX map item size");
                if ((type == 0x0007 || type == 0x0008) && size != 0) {
                    throw new IOException("DEX call-site or method-handle tables are not classified");
                }
            }
        }

        private void addMethodUse(String reference, MethodUse use,
                                  DecodeBudget decodeBudget) throws IOException {
            LinkedHashSet<MethodUse> uses = methodUses.get(reference);
            if (uses == null) {
                uses = new LinkedHashSet<>();
                methodUses.put(reference, uses);
            }
            if (uses.add(use)) decodeBudget.consumeMemberUseSite();
        }

        private void addFieldUse(String reference, FieldUse use,
                                 DecodeBudget decodeBudget) throws IOException {
            LinkedHashSet<FieldUse> uses = fieldUses.get(reference);
            if (uses == null) {
                uses = new LinkedHashSet<>();
                fieldUses.put(reference, uses);
            }
            if (uses.add(use)) decodeBudget.consumeMemberUseSite();
        }
    }

    /** Validates DEX invoke word counts and every encoded argument register. */
    private static void validateInvokeOperands(DexReader reader, int instructionAddress,
                                               int firstCodeUnit, int opcode, int registersSize,
                                               String methodReference) throws IOException {
        boolean range = opcode >= 0x74 && opcode <= 0x78;
        boolean isStatic = methodStaticOpcode(opcode);
        int expectedWordCount = invokeArgumentWordCount(methodReference, isStatic);
        int encodedWordCount = range ? (firstCodeUnit >>> 8) & 0xff
                : (firstCodeUnit >>> 12) & 0x0f;
        if (!range && encodedWordCount > 5) {
            throw new IOException("DEX invoke 35c argument count exceeds five words");
        }
        if (encodedWordCount != expectedWordCount) {
            throw new IOException("DEX invoke argument count does not match method prototype");
        }

        int[] argumentRegisters = new int[encodedWordCount];
        if (range) {
            int firstRegister = reader.u16(instructionAddress + 4);
            long registerEnd = (long) firstRegister + encodedWordCount;
            if (registerEnd > registersSize) {
                throw new IOException("DEX invoke range exceeds code register bounds");
            }
            for (int index = 0; index < encodedWordCount; index++) {
                argumentRegisters[index] = firstRegister + index;
            }
        } else {
            int packedRegisters = reader.u16(instructionAddress + 4);
            int fifthRegister = (firstCodeUnit >>> 8) & 0x0f;
            for (int index = 0; index < encodedWordCount; index++) {
                int register = index == 4 ? fifthRegister
                        : (packedRegisters >>> (index * 4)) & 0x0f;
                if (register >= registersSize) {
                    throw new IOException("DEX invoke register exceeds code register bounds");
                }
                argumentRegisters[index] = register;
            }
        }
        validateWideInvokeRegisters(methodReference, isStatic, argumentRegisters);
    }

    private static int invokeArgumentWordCount(String methodReference, boolean isStatic)
            throws IOException {
        int arrow = methodReference.indexOf("->");
        int open = methodReference.indexOf('(', arrow + 2);
        int close = methodReference.indexOf(')', open + 1);
        if (arrow <= 0 || open <= arrow || close < 0) {
            throw new IOException("invalid DEX invoke method descriptor");
        }
        int words = isStatic ? 0 : 1;
        for (int index = open + 1; index < close;) {
            int end = descriptorEnd(methodReference, index);
            if (end > close) throw new IOException("invalid DEX invoke parameter descriptor");
            char first = methodReference.charAt(index);
            words += first == 'J' || first == 'D' ? 2 : 1;
            index = end;
        }
        return words;
    }

    private static void validateWideInvokeRegisters(String methodReference, boolean isStatic,
                                                   int[] argumentRegisters) throws IOException {
        int arrow = methodReference.indexOf("->");
        int open = methodReference.indexOf('(', arrow + 2);
        int close = methodReference.indexOf(')', open + 1);
        int argumentWord = isStatic ? 0 : 1;
        for (int index = open + 1; index < close;) {
            int end = descriptorEnd(methodReference, index);
            char first = methodReference.charAt(index);
            if (first == 'J' || first == 'D') {
                if (argumentWord + 1 >= argumentRegisters.length) {
                    throw new IOException("DEX invoke wide argument is truncated");
                }
                if (argumentRegisters[argumentWord + 1] != argumentRegisters[argumentWord] + 1) {
                    throw new IOException("DEX invoke wide argument registers are not consecutive");
                }
                argumentWord += 2;
            } else {
                argumentWord++;
            }
            index = end;
        }
        if (argumentWord != argumentRegisters.length) {
            throw new IOException("DEX invoke register word count does not match method prototype");
        }
    }

    private static int instructionWidth(DexReader reader, int instructionOffset,
                                        int programCounter, int instructionCount,
                                        int firstCodeUnit) throws IOException {
        int opcode = firstCodeUnit & 0xff;
        if (opcode == 0x00) {
            int payloadKind = firstCodeUnit >>> 8;
            if (payloadKind == 0) return 1;
            if (payloadKind == 1 || payloadKind == 2) {
                if (programCounter > instructionCount - 2) throw new IOException("truncated DEX switch payload");
                int size = reader.u16(instructionOffset + (programCounter + 1) * 2);
                long width = payloadKind == 1 ? 4L + (long) size * 2L : 2L + (long) size * 4L;
                if (width > Integer.MAX_VALUE) throw new IOException("DEX switch payload exceeds the preflight limit");
                return (int) width;
            }
            if (payloadKind == 3) {
                if (programCounter > instructionCount - 4) throw new IOException("truncated DEX array payload");
                int elementWidth = reader.u16(instructionOffset + (programCounter + 1) * 2);
                long elementCount = reader.u16(instructionOffset + (programCounter + 2) * 2)
                        | ((long) reader.u16(instructionOffset + (programCounter + 3) * 2) << 16);
                if (elementWidth != 1 && elementWidth != 2 && elementWidth != 4 && elementWidth != 8) {
                    throw new IOException("invalid DEX array payload width");
                }
                long width = 4L + ((long) elementWidth * elementCount + 1L) / 2L;
                if (width > Integer.MAX_VALUE) throw new IOException("DEX array payload exceeds the preflight limit");
                return (int) width;
            }
            throw new IOException("invalid DEX payload identifier");
        }
        if (opcode >= 0x01 && opcode <= 0x09) return (opcode % 3 == 0) ? 3 : ((opcode % 3 == 2) ? 2 : 1);
        if (opcode >= 0x0a && opcode <= 0x12) return 1;
        switch (opcode) {
            case 0x13: case 0x15: case 0x16: case 0x19: case 0x1a:
            case 0x1c: case 0x1f: case 0x20: case 0x22: case 0x23:
            case 0x29:
                return 2;
            case 0x14: case 0x17: case 0x1b: case 0x24: case 0x25:
            case 0x26: case 0x2a: case 0x2b: case 0x2c:
                return 3;
            case 0x18: return 5;
            case 0x1d: case 0x1e: case 0x21: case 0x27: case 0x28:
                return 1;
            default: break;
        }
        if ((opcode >= 0x2d && opcode <= 0x3d) || (opcode >= 0x44 && opcode <= 0x6d)
                || (opcode >= 0x90 && opcode <= 0xaf)
                || (opcode >= 0xd0 && opcode <= 0xe2)) return 2;
        if ((opcode >= 0x3e && opcode <= 0x43) || opcode == 0x73
                || opcode == 0x79 || opcode == 0x7a
                || (opcode >= 0xe3 && opcode <= 0xf9)) {
            throw new IOException("unsupported DEX opcode");
        }
        if ((opcode >= 0x6e && opcode <= 0x72)
                || (opcode >= 0x74 && opcode <= 0x78)
                || opcode == 0xfc || opcode == 0xfd) return 3;
        if (opcode >= 0x7b && opcode <= 0x8f) return 1;
        if (opcode >= 0xb0 && opcode <= 0xcf) return 1;
        if (opcode == 0xfa || opcode == 0xfb) return 4;
        if (opcode == 0xfe || opcode == 0xff) return 2;
        throw new IOException("unsupported DEX opcode");
    }

    private static final class DexReader {
        final byte[] data;
        final DecodeBudget decodeBudget;
        final Map<Integer, String> decodedStrings = new HashMap<>();
        int limit;
        DexReader(byte[] data, DecodeBudget decodeBudget) {
            this.data = data;
            this.decodeBudget = decodeBudget;
            limit = data.length;
        }
        void setLogicalLimit(int value) throws IOException { check(0, value); limit = value; }
        void clearDecodedStringCache() { decodedStrings.clear(); }
        void check(int offset, int bytes) throws IOException {
            if (offset < 0 || bytes < 0 || offset > limit - bytes) throw new IOException("DEX table offset is out of bounds");
        }
        int u8(int offset) throws IOException { check(offset, 1); return data[offset] & 255; }
        void checkTable(int offset, int count, int itemSize, String label) throws IOException {
            if (count < 0 || itemSize <= 0) throw new IOException("invalid " + label + " table extent");
            long byteCount = (long) count * itemSize;
            if (offset < 0 || offset > limit || byteCount > (long) limit - offset) {
                throw new IOException(label + " table extent is out of bounds");
            }
        }
        int tableItemOffset(int offset, int index, int itemSize, String label) throws IOException {
            if (index < 0 || itemSize <= 0) throw new IOException("invalid " + label + " table index");
            long itemOffset = (long) offset + (long) index * itemSize;
            if (itemOffset > Integer.MAX_VALUE) throw new IOException(label + " table offset overflows");
            int result = (int) itemOffset;
            check(result, itemSize);
            return result;
        }
        int i32(int offset) throws IOException { check(offset, 4); return (data[offset] & 255) | ((data[offset + 1] & 255) << 8) | ((data[offset + 2] & 255) << 16) | ((data[offset + 3] & 255) << 24); }
        int u16(int offset) throws IOException { check(offset, 2); return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }
        UlebResult uleb128(int offset) throws IOException {
            long value = 0;
            int shift = 0;
            int index = offset;
            for (int item = 0; item < 5; item++) {
                int next = u8(index++);
                if (shift == 28 && (next & 0xf0) != 0) throw new IOException("invalid DEX ULEB128 value");
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    if (value > Integer.MAX_VALUE) throw new IOException("DEX ULEB128 value exceeds the preflight limit");
                    return new UlebResult((int) value, index);
                }
                shift += 7;
            }
            throw new IOException("invalid DEX ULEB128 encoding");
        }
        SlebResult sleb128(int offset) throws IOException {
            long value = 0;
            int shift = 0;
            int index = offset;
            for (int item = 0; item < 5; item++) {
                int next = u8(index++);
                value |= (long) (next & 0x7f) << shift;
                shift += 7;
                if ((next & 0x80) == 0) {
                    if ((next & 0x40) != 0 && shift < 64) value |= -1L << shift;
                    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                        throw new IOException("DEX SLEB128 value exceeds the preflight limit");
                    }
                    return new SlebResult((int) value, index);
                }
            }
            throw new IOException("invalid DEX SLEB128 encoding");
        }
        int checkedCount(int count, String label) throws IOException {
            if (count < 0 || count > MAX_DEX_TABLE_ITEMS) throw new IOException("invalid or excessive " + label + " count");
            return count;
        }
        int u32Index(int offset, int count, String label) throws IOException {
            int value = i32(offset);
            if (value < 0 || value >= count) throw new IOException(label + " index out of range");
            return value;
        }
        String stringData(int offset) throws IOException {
            String cached = decodedStrings.get(offset);
            if (cached != null) return cached;
            check(offset, 1);
            int index = offset;
            long length = 0;
            int shift = 0;
            while (true) {
                check(index, 1);
                int value = data[index++] & 255;
                if (shift == 28 && (value & 0xf0) != 0) throw new IOException("invalid string length encoding");
                length |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IOException("invalid string length encoding");
            }
            if (length > MAX_DECODED_STRING_CHARS) throw new IOException("DEX identifier string exceeds character limit");
            decodeBudget.ensureStringCharsCapacity((int) length);
            decodeBudget.beginString();
            char[] characters = new char[(int) length];
            int characterCount = 0;
            int byteCount = 0;
            while (true) {
                int first = u8(index++);
                if (first == 0) break;
                int character;
                int consumedBytes = 1;
                if (first <= 0x7f) {
                    character = first;
                } else if ((first & 0xe0) == 0xc0) {
                    int second = u8(index++);
                    if ((second & 0xc0) != 0x80) throw new IOException("invalid modified UTF-8 continuation");
                    character = ((first & 0x1f) << 6) | (second & 0x3f);
                    if (character == 0) {
                        if (first != 0xc0 || second != 0x80) throw new IOException("invalid modified UTF-8 null");
                    } else if (character < 0x80) {
                        throw new IOException("overlong modified UTF-8 sequence");
                    }
                    consumedBytes = 2;
                } else if ((first & 0xf0) == 0xe0) {
                    int second = u8(index++);
                    int third = u8(index++);
                    if ((second & 0xc0) != 0x80 || (third & 0xc0) != 0x80) {
                        throw new IOException("invalid modified UTF-8 continuation");
                    }
                    character = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
                    if (character < 0x800) throw new IOException("overlong modified UTF-8 sequence");
                    consumedBytes = 3;
                } else {
                    throw new IOException("invalid modified UTF-8 leading byte");
                }
                if (byteCount > MAX_DECODED_STRING_BYTES - consumedBytes) {
                    throw new IOException("DEX identifier string exceeds byte limit");
                }
                for (int byteIndex = 0; byteIndex < consumedBytes; byteIndex++) {
                    decodeBudget.consumeStringByte();
                }
                byteCount += consumedBytes;
                if (characterCount >= characters.length) throw new IOException("DEX string length mismatch");
                characters[characterCount++] = (char) character;
            }
            if (characterCount != characters.length) throw new IOException("DEX string length mismatch");
            String decoded = new String(characters);
            decodeBudget.recordDecodedStringChars(decoded.length());
            decodedStrings.put(offset, decoded);
            return decoded;
        }
    }

    /** Package-wide parser work budget shared across every DEX entry in one archive. */
    private static final class DecodeBudget {
        private long tableItems;
        private long decodedBytes;
        private long decodedChars;
        private long descriptorChars;
        private long referenceChars;
        private long instructionUnits;
        private int memberUseSites;
        private int decodedStrings;

        void consumeTableItems(long count) throws IOException {
            if (count < 0 || tableItems > MAX_TOTAL_DEX_TABLE_ITEMS - count) {
                throw new IOException("DEX table traversal exceeds the aggregate preflight work limit");
            }
            tableItems += count;
        }

        void beginString() throws IOException {
            if (decodedStrings >= MAX_TOTAL_DECODED_STRINGS) {
                throw new IOException("DEX string decoding exceeds the aggregate preflight work limit");
            }
            decodedStrings++;
        }

        void consumeStringByte() throws IOException {
            if (decodedBytes >= MAX_TOTAL_DECODED_STRING_BYTES) {
                throw new IOException("DEX string decoding exceeds the aggregate byte budget");
            }
            decodedBytes++;
        }

        void ensureStringCharsCapacity(int maximumChars) throws IOException {
            if (maximumChars < 0 || decodedChars > MAX_TOTAL_DECODED_STRING_CHARS - maximumChars) {
                throw new IOException("DEX string decoding exceeds the aggregate character budget");
            }
        }

        void recordDecodedStringChars(int chars) throws IOException {
            ensureStringCharsCapacity(chars);
            decodedChars += chars;
        }

        void consumeDescriptorChars(long count) throws IOException {
            if (count < 0 || descriptorChars > MAX_TOTAL_DESCRIPTOR_CHARS - count) {
                throw new IOException("DEX method descriptor traversal exceeds the aggregate preflight work limit");
            }
            descriptorChars += count;
        }

        void consumeReferenceChars(long count) throws IOException {
            if (count < 0 || referenceChars > MAX_TOTAL_REFERENCE_CHARS - count) {
                throw new IOException("DEX symbol traversal exceeds the aggregate preflight work limit");
            }
            referenceChars += count;
        }

        void consumeInstructionUnits(long count) throws IOException {
            if (count < 0 || instructionUnits > MAX_TOTAL_CODE_UNITS - count) {
                throw new IOException("DEX code traversal exceeds the aggregate preflight work limit");
            }
            instructionUnits += count;
        }

        void consumeMemberUseSite() throws IOException {
            if (memberUseSites >= MAX_TOTAL_MEMBER_USE_SITES) {
                throw new IOException("DEX member-use traversal exceeds the aggregate preflight work limit");
            }
            memberUseSites++;
        }
    }

    private static final class UlebResult {
        final int value;
        final int nextOffset;

        UlebResult(int value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    private static final class SlebResult {
        final int value;
        final int nextOffset;

        SlebResult(int value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    /** Package-wide cap for non-initializing resolution of external dependency symbols. */
    private static final class ExternalResolutionBudget {
        private int symbolChecks;

        void consumeSymbolCheck() throws IOException {
            if (symbolChecks >= MAX_EXTERNAL_SYMBOL_CHECKS) {
                throw new IOException("external symbol checks exceed the preflight work limit");
            }
            symbolChecks++;
        }
    }

    private static final class MethodUse {
        final String callerClass;
        final String callerMethodName;
        final boolean callerConstructor;
        final int opcode;
        final boolean callerStatic;
        final boolean arrayCloneReceiverProven;
        final boolean protectedConstructorReceiverIsThis;
        final boolean annotationMemberValue;

        MethodUse(String callerClass, String callerMethodName, int opcode, boolean callerStatic,
                  boolean arrayCloneReceiverProven,
                  boolean protectedConstructorReceiverIsThis) {
            this(callerClass, callerMethodName, opcode, callerStatic, arrayCloneReceiverProven,
                    protectedConstructorReceiverIsThis, false);
        }

        private MethodUse(String callerClass, String callerMethodName, int opcode,
                          boolean callerStatic, boolean arrayCloneReceiverProven,
                          boolean protectedConstructorReceiverIsThis,
                          boolean annotationMemberValue) {
            this.callerClass = callerClass;
            this.callerMethodName = callerMethodName;
            this.callerConstructor = "<init>".equals(callerMethodName);
            this.opcode = opcode;
            this.callerStatic = callerStatic;
            this.arrayCloneReceiverProven = arrayCloneReceiverProven;
            this.protectedConstructorReceiverIsThis = protectedConstructorReceiverIsThis;
            this.annotationMemberValue = annotationMemberValue;
        }

        static MethodUse annotationMemberValue(String callerClass) {
            return new MethodUse(callerClass, "<annotation>", -1, false, false, false, true);
        }

        @Override public int hashCode() {
            int result = (callerClass.hashCode() * 31 + callerMethodName.hashCode()) * 31 + opcode;
            result = result * 31 + (callerStatic ? 1 : 0);
            result = result * 31 + (arrayCloneReceiverProven ? 1 : 0);
            result = result * 31 + (protectedConstructorReceiverIsThis ? 1 : 0);
            return result * 31 + (annotationMemberValue ? 1 : 0);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof MethodUse)) return false;
            MethodUse use = (MethodUse) other;
            return opcode == use.opcode && callerStatic == use.callerStatic
                    && arrayCloneReceiverProven == use.arrayCloneReceiverProven
                    && protectedConstructorReceiverIsThis
                            == use.protectedConstructorReceiverIsThis
                    && annotationMemberValue == use.annotationMemberValue
                    && callerClass.equals(use.callerClass)
                    && callerMethodName.equals(use.callerMethodName);
        }
    }

    private static final class FieldUse {
        final String callerClass;
        final int opcode;
        final boolean annotationEnumValue;
        final boolean annotationMemberValue;

        FieldUse(String callerClass, int opcode) {
            this(callerClass, opcode, false, false);
        }

        FieldUse(String callerClass, int opcode, boolean annotationEnumValue) {
            this(callerClass, opcode, annotationEnumValue, false);
        }

        private FieldUse(String callerClass, int opcode, boolean annotationEnumValue,
                         boolean annotationMemberValue) {
            this.callerClass = callerClass;
            this.opcode = opcode;
            this.annotationEnumValue = annotationEnumValue;
            this.annotationMemberValue = annotationMemberValue;
        }

        static FieldUse annotationMemberValue(String callerClass) {
            return new FieldUse(callerClass, -1, false, true);
        }

        @Override public int hashCode() {
            int result = (callerClass.hashCode() * 31 + opcode) * 31
                    + (annotationEnumValue ? 1 : 0);
            return result * 31 + (annotationMemberValue ? 1 : 0);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof FieldUse)) return false;
            FieldUse use = (FieldUse) other;
            return opcode == use.opcode && annotationEnumValue == use.annotationEnumValue
                    && annotationMemberValue == use.annotationMemberValue
                    && callerClass.equals(use.callerClass);
        }
    }

    private static final class ResolvedMethod {
        final int modifiers;
        final String declaringType;
        final boolean constructor;
        final boolean ownerIsInterface;
        final boolean declaringTypeIsInterface;

        ResolvedMethod(int modifiers, String declaringType, boolean constructor,
                       boolean ownerIsInterface, boolean declaringTypeIsInterface) {
            this.modifiers = modifiers;
            this.declaringType = declaringType;
            this.constructor = constructor;
            this.ownerIsInterface = ownerIsInterface;
            this.declaringTypeIsInterface = declaringTypeIsInterface;
        }
    }

    private static final class ResolvedField {
        final int modifiers;
        final String declaringType;

        ResolvedField(int modifiers, String declaringType) {
            this.modifiers = modifiers;
            this.declaringType = declaringType;
        }
    }

    public static final class Result {
        public final int dexCount;
        public final int dexBytes;
        public final List<String> referencedHostTypes;
        public final List<String> referencedHostMethods;
        public final List<String> referencedHostFields;
        public final List<String> missingHostTypes;
        public final List<String> missingHostMethods;
        public final List<String> missingHostFields;
        /** Includes every external object type_id, even when used only by DEX metadata. */
        public final List<String> referencedExternalTypes;
        public final List<String> referencedExternalMethods;
        public final List<String> referencedExternalFields;
        public final List<String> missingExternalTypes;
        public final List<String> missingExternalMethods;
        public final List<String> missingExternalFields;
        public final int pluginDefinedTypeCount;
        public final boolean externalResolutionComplete;
        public final List<String> errors;

        private Result(int count, int bytes, Set<String> types, Set<String> methods, Set<String> fields,
                       List<String> missingTypes, List<String> missingMethods, List<String> missingFields,
                       Set<String> externalTypes, Set<String> externalMethods, Set<String> externalFields,
                       List<String> missingExternalTypes, List<String> missingExternalMethods,
                       List<String> missingExternalFields, int pluginDefinedTypeCount,
                       boolean externalResolutionComplete, List<String> errors) {
            dexCount = count;
            dexBytes = bytes;
            referencedHostTypes = immutable(types);
            referencedHostMethods = immutable(methods);
            referencedHostFields = immutable(fields);
            missingHostTypes = immutable(missingTypes);
            missingHostMethods = immutable(missingMethods);
            missingHostFields = immutable(missingFields);
            referencedExternalTypes = immutable(externalTypes);
            referencedExternalMethods = immutable(externalMethods);
            referencedExternalFields = immutable(externalFields);
            this.missingExternalTypes = immutable(missingExternalTypes);
            this.missingExternalMethods = immutable(missingExternalMethods);
            this.missingExternalFields = immutable(missingExternalFields);
            this.pluginDefinedTypeCount = pluginDefinedTypeCount;
            this.externalResolutionComplete = externalResolutionComplete;
            this.errors = immutable(errors);
        }
        private static List<String> immutable(java.util.Collection<String> values) {
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
        static Result failure(String error) {
            return new Result(0, 0, Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    0, false, Collections.singletonList(error));
        }
        /** Legacy name; this gate now covers both the CloudStream API and external DEX closure. */
        public boolean isExactHostSurfacePresent() {
            return externalResolutionComplete && errors.isEmpty() && missingHostTypes.isEmpty()
                    && missingHostMethods.isEmpty() && missingHostFields.isEmpty()
                    && missingExternalTypes.isEmpty() && missingExternalMethods.isEmpty()
                    && missingExternalFields.isEmpty();
        }
        public int getTotalMissingHostMembers() { return missingHostMethods.size() + missingHostFields.size(); }
        public int getTotalMissingExternalMembers() {
            return missingExternalMethods.size() + missingExternalFields.size();
        }
    }
}
