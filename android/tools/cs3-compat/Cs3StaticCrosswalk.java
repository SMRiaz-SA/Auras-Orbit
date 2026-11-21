import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Static DEX-to-JVM compile-surface crosswalk. Never loads or executes analyzed code. */
public final class Cs3StaticCrosswalk {
    private static final String VERSION = "1.0.0";

    private record Input(String kind, String label, Path path) {}
    private record Proto(String returnType, List<String> parameters) {
        String descriptor() { return "(" + String.join("", parameters) + ")" + returnType; }
    }
    private record FieldRef(String owner, String name, String type) {
        String descriptor() { return owner + "->" + name + ":" + type; }
    }
    private record MethodRef(String owner, String name, String proto) {
        String descriptor() { return owner + "->" + name + proto; }
    }
    private record MemberKey(String name, String descriptor) {}
    private static final class ClassInfo {
        String descriptor;
        String superDescriptor;
        List<String> interfaces = new ArrayList<>();
        Set<MemberKey> methods = new HashSet<>();
        Set<MemberKey> fields = new HashSet<>();
        String sourceKind;
        String sourceLabel;
    }
    private record Provider(String kind, String label) {}
    private record Resolution(String status, String declaringClass, String providers) {}
    private record ManifestInfo(String pluginClass, String name, String version,
                                boolean requiresResources, int resourceEntries,
                                boolean resourcesTablePresent, int archiveEntries,
                                long archiveBytes, String archiveSha256,
                                String manifestJson) {}

    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Map<String, List<ClassInfo>> classVariants = new HashMap<>();
    private final Map<String, List<Provider>> duplicateProviders = new HashMap<>();
    private final Map<String, Set<String>> counts = new TreeMap<>();
    private final Map<String, Integer> statusCounts = new TreeMap<>();
    private final String plugin;
    private final String baseline;
    private final Set<String> pluginClasses;
    private final List<String[]> dexTypes;
    private final List<FieldRef> dexFields;
    private final List<MethodRef> dexMethods;
    private final ManifestInfo manifest;

    private Cs3StaticCrosswalk(String plugin, String baseline, Set<String> pluginClasses,
                               List<String[]> dexTypes, List<FieldRef> dexFields,
                               List<MethodRef> dexMethods, ManifestInfo manifest) {
        this.plugin = plugin;
        this.baseline = baseline;
        this.pluginClasses = pluginClasses;
        this.dexTypes = dexTypes;
        this.dexFields = dexFields;
        this.dexMethods = dexMethods;
        this.manifest = manifest;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        String plugin = require(options, "plugin");
        String baseline = require(options, "baseline");
        Path archive = Path.of(require(options, "archive"));
        Path dex = Path.of(require(options, "dex"));
        Path classpath = Path.of(require(options, "classpath"));
        Path out = Path.of(require(options, "out"));
        Path summary = Path.of(require(options, "summary"));

        ManifestInfo manifest = readManifest(archive);
        DexData dexData = DexData.read(dex);
        Cs3StaticCrosswalk crosswalk = new Cs3StaticCrosswalk(plugin, baseline,
                dexData.pluginClasses, dexData.types, dexData.fields, dexData.methods, manifest);
        List<Input> inputs = readInputs(classpath);
        crosswalk.loadClassSurface(inputs);
        crosswalk.writeTsv(out);
        crosswalk.writeSummary(summary, inputs, dexData);
        System.out.printf("%s %s: %d type IDs, %d field IDs, %d method IDs; %d JVM classes; %d unresolved refs%n",
                plugin, baseline, dexData.types.size(), dexData.fields.size(), dexData.methods.size(),
                crosswalk.classes.size(), crosswalk.unresolvedCount());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 == args.length)
                throw new IllegalArgumentException("Expected --name value arguments");
            result.put(args[i].substring(2), args[++i]);
        }
        return result;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
        return value;
    }

    private static List<Input> readInputs(Path manifest) throws IOException {
        List<Input> inputs = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length != 3) throw new IOException("Bad classpath row: " + line);
            Path path = Path.of(parts[2]);
            if (!Files.exists(path)) throw new IOException("Missing classpath input: " + path);
            inputs.add(new Input(parts[0], parts[1], path));
        }
        return inputs;
    }

    private void loadClassSurface(List<Input> inputs) throws IOException {
        for (Input input : inputs) {
            if (Files.isDirectory(input.path())) {
                try (var files = Files.walk(input.path())) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                        byte[] bytes = Files.readAllBytes(file);
                        addClass(parseClass(bytes), input);
                    }
                }
            } else if (input.path().toString().toLowerCase().endsWith(".aar")) {
                readAar(input, Files.readAllBytes(input.path()));
            } else {
                try (ZipFile zip = new ZipFile(input.path().toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                            try (InputStream in = zip.getInputStream(entry)) {
                                addClass(parseClass(in.readAllBytes()), input);
                            }
                        }
                    }
                }
            }
        }
    }

    private void readAar(Input input, byte[] aarBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(aarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && (name.equals("classes.jar") ||
                        (name.startsWith("libs/") && name.endsWith(".jar")))) {
                    String label = input.label() + "!/" + name;
                    readJarBytes(zip.readAllBytes(), new Input(input.kind(), label, input.path()));
                }
            }
        }
    }

    private void readJarBytes(byte[] bytes, Input input) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class"))
                    addClass(parseClass(zip.readAllBytes()), input);
            }
        }
    }

    private void addClass(ClassInfo info, Input input) {
        info.sourceKind = input.kind();
        info.sourceLabel = input.label();
        List<Provider> providers = duplicateProviders.computeIfAbsent(info.descriptor, k -> new ArrayList<>());
        providers.add(new Provider(input.kind(), input.label()));
        classVariants.computeIfAbsent(info.descriptor, k -> new ArrayList<>()).add(info);
        // Preserve first input order as the primary compile classpath definition.
        if (providers.size() == 1) classes.put(info.descriptor, info);
    }

    private static ClassInfo parseClass(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("Invalid class file magic");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int cpCount = in.readUnsignedShort();
        Object[] cp = new Object[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> {
                    int length = in.readUnsignedShort();
                    cp[i] = new String(in.readNBytes(length), StandardCharsets.UTF_8);
                }
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 7 -> cp[i] = new CpClass(in.readUnsignedShort());
                case 8, 16, 19, 20 -> in.skipNBytes(2);
                case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("Unsupported class constant-pool tag " + tag);
            }
        }
        in.readUnsignedShort(); // access flags
        int thisClass = in.readUnsignedShort();
        int superClass = in.readUnsignedShort();
        ClassInfo result = new ClassInfo();
        result.descriptor = classDescriptor(cp, thisClass);
        result.superDescriptor = superClass == 0 ? null : classDescriptor(cp, superClass);
        int interfaceCount = in.readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++) result.interfaces.add(classDescriptor(cp, in.readUnsignedShort()));
        readMembers(in, cp, result.fields);
        readMembers(in, cp, result.methods);
        return result;
    }

    private record CpClass(int nameIndex) {}

    private static String classDescriptor(Object[] cp, int index) throws IOException {
        if (!(cp[index] instanceof CpClass cls) || !(cp[cls.nameIndex()] instanceof String name))
            throw new IOException("Invalid class constant");
        return name.startsWith("[") ? name : "L" + name + ";";
    }

    private static void readMembers(DataInputStream in, Object[] cp, Set<MemberKey> members) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); // access flags
            int name = in.readUnsignedShort();
            int descriptor = in.readUnsignedShort();
            if (!(cp[name] instanceof String n) || !(cp[descriptor] instanceof String d))
                throw new IOException("Invalid member constant");
            members.add(new MemberKey(n, d));
            int attributes = in.readUnsignedShort();
            for (int j = 0; j < attributes; j++) {
                in.readUnsignedShort();
                long length = Integer.toUnsignedLong(in.readInt());
                in.skipNBytes(length);
            }
        }
    }

    private void writeTsv(Path out) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        StringBuilder text = new StringBuilder("plugin\tbaseline\tkind\treference\towner\tclassification\tstatus\tdeclaring_class\tdeclaring_classification\tprovider\tduplicate_providers\n");
        for (String[] type : dexTypes) {
            String descriptor = type[0];
            String classification = classifyType(descriptor);
            String component = componentType(descriptor);
            String status = isPrimitive(component) ? "primitive" : pluginClasses.contains(component) ? "plugin-internal" :
                    findClass(component) == null ? "unresolved-class" : "class-present";
            ClassInfo info = isPrimitive(component) ? null : findClass(component);
            append(text, "type", descriptor, descriptor, classification, status,
                    info == null ? "" : info.descriptor, info == null ? "" : info.sourceKind,
                    info == null ? "" : info.sourceLabel, providers(component));
        }
        for (FieldRef field : dexFields) {
            String classification = classifyType(field.owner());
            Resolution resolution = resolve(field.owner(), new MemberKey(field.name(), field.type()), false);
            append(text, "field", field.descriptor(), field.owner(), classification, resolution.status(),
                    resolution.declaringClass(), classKind(resolution.declaringClass()),
                    resolution.providers(), providers(field.owner()));
        }
        for (MethodRef method : dexMethods) {
            String classification = classifyType(method.owner());
            Resolution resolution = resolve(method.owner(), new MemberKey(method.name(), method.proto()), true);
            append(text, "method", method.descriptor(), method.owner(), classification, resolution.status(),
                    resolution.declaringClass(), classKind(resolution.declaringClass()),
                    resolution.providers(), providers(method.owner()));
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    private void append(StringBuilder out, String kind, String reference, String owner, String classification,
                        String status, String declaration, String declarationKind, String provider, String duplicates) {
        out.append(clean(plugin)).append('\t').append(clean(baseline)).append('\t').append(kind).append('\t')
                .append(clean(reference)).append('\t').append(clean(owner)).append('\t').append(classification)
                .append('\t').append(status).append('\t').append(clean(declaration)).append('\t')
                .append(clean(declarationKind)).append('\t').append(clean(provider)).append('\t')
                .append(clean(duplicates)).append('\n');
        statusCounts.merge(kind + ":" + status, 1, Integer::sum);
        counts.computeIfAbsent(kind + ":" + classification, k -> new TreeSet<>()).add(reference);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private ClassInfo findClass(String descriptor) {
        if (descriptor.startsWith("[")) return classes.get("Ljava/lang/Object;");
        return classes.get(descriptor);
    }

    private static String componentType(String descriptor) {
        while (descriptor.startsWith("[")) descriptor = descriptor.substring(1);
        return descriptor;
    }

    private String classifyType(String descriptor) {
        descriptor = componentType(descriptor);
        if (isPrimitive(descriptor)) return "platform";
        if (pluginClasses.contains(descriptor)) return "plugin";
        ClassInfo info = findClass(descriptor);
        if (info != null) return info.sourceKind;
        if (descriptor.startsWith("Ljava/") || descriptor.startsWith("Ljavax/") ||
                descriptor.startsWith("Landroid/") || descriptor.startsWith("Ldalvik/") ||
                descriptor.startsWith("Lorg/xmlpull/")) return "unresolved";
        return "unresolved";
    }

    private String classKind(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return "";
        if (pluginClasses.contains(descriptor)) return "plugin";
        ClassInfo info = findClass(descriptor);
        return info == null ? "" : info.sourceKind;
    }

    private String providers(String descriptor) {
        List<Provider> providers = duplicateProviders.get(descriptor);
        if (providers == null) return "";
        return providers.stream().map(p -> p.kind() + ":" + p.label()).distinct().reduce((a, b) -> a + "|" + b).orElse("");
    }

    private Resolution resolve(String owner, MemberKey member, boolean method) {
        if (pluginClasses.contains(owner)) return new Resolution("plugin-internal", owner, "plugin-dex");
        String lookupOwner = owner.startsWith("[") ? "Ljava/lang/Object;" : owner;
        if (!classes.containsKey(lookupOwner)) return new Resolution("unresolved-class", "", "");
        List<ClassInfo> matches = new ArrayList<>();
        lookupAll(lookupOwner, member, method, new HashSet<>(), matches);
        if (!matches.isEmpty()) {
            String declaration = matches.get(0).descriptor;
            String matchingProviders = matches.stream()
                    .map(info -> info.sourceKind + ":" + info.sourceLabel)
                    .distinct().reduce((a, b) -> a + "|" + b).orElse("");
            return new Resolution("exact-descriptor-present", declaration, matchingProviders);
        }
        return new Resolution("unresolved-descriptor", "", providers(owner));
    }

    private void lookupAll(String descriptor, MemberKey member, boolean method, Set<String> seen,
                           List<ClassInfo> matches) {
        for (ClassInfo current : classVariants.getOrDefault(descriptor, List.of())) {
            String visitKey = current.descriptor + "\u0000" + current.sourceLabel;
            if (!seen.add(visitKey)) continue;
            Set<MemberKey> members = method ? current.methods : current.fields;
            if (members.contains(member)) {
                matches.add(current);
                continue;
            }
            if (method && member.name().startsWith("<")) continue; // constructors are never inherited
            if (current.superDescriptor != null)
                lookupAll(current.superDescriptor, member, method, seen, matches);
            for (String iface : current.interfaces)
                lookupAll(iface, member, method, seen, matches);
        }
    }

    private static boolean isPrimitive(String descriptor) {
        return descriptor.length() == 1 && "VZBSCIJFD".contains(descriptor);
    }

    private int unresolvedCount() {
        return statusCounts.entrySet().stream().filter(e -> e.getKey().endsWith("unresolved-class") ||
                e.getKey().endsWith("unresolved-descriptor")).mapToInt(Map.Entry::getValue).sum();
    }

    private void writeSummary(Path out, List<Input> inputs, DexData dex) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"analyzer\": \"Cs3StaticCrosswalk\",\n  \"analyzerVersion\": \"").append(VERSION)
                .append("\",\n  \"executionBoundary\": \"Static archive/DEX/JVM class parsing only; analyzed code is never loaded or executed.\",\n")
                .append("  \"plugin\": \"").append(j(plugin)).append("\",\n  \"baseline\": \"").append(j(baseline)).append("\",\n")
                .append("  \"manifest\": {\n    \"name\": \"").append(j(manifest.name())).append("\",\n")
                .append("    \"pluginClassName\": \"").append(j(manifest.pluginClass())).append("\",\n")
                .append("    \"version\": \"").append(j(manifest.version())).append("\",\n")
                .append("    \"requiresResources\": ").append(manifest.requiresResources()).append(",\n")
                .append("    \"resourceEntries\": ").append(manifest.resourceEntries()).append(",\n")
                .append("    \"resourcesTablePresent\": ").append(manifest.resourcesTablePresent()).append(",\n")
                .append("    \"resourceRequirementSatisfiedByArchiveShape\": ").append(!manifest.requiresResources() || manifest.resourcesTablePresent()).append(",\n")
                .append("    \"archiveEntries\": ").append(manifest.archiveEntries()).append(",\n")
                .append("    \"archiveBytes\": ").append(manifest.archiveBytes()).append(",\n")
                .append("    \"archiveSha256\": \"").append(manifest.archiveSha256()).append("\"\n  },\n")
                .append("  \"dex\": {\n    \"sha256\": \"").append(dex.sha256).append("\",\n")
                .append("    \"bytes\": ").append(dex.bytes.length).append(",\n    \"version\": \"").append(j(dex.version)).append("\",\n")
                .append("    \"classDefinitions\": ").append(dex.pluginClasses.size()).append(",\n")
                .append("    \"typeIds\": ").append(dex.types.size()).append(",\n    \"fieldIds\": ").append(dex.fields.size()).append(",\n")
                .append("    \"methodIds\": ").append(dex.methods.size()).append("\n  },\n")
                .append("  \"surface\": {\n    \"classDefinitions\": ").append(classes.size()).append(",\n")
                .append("    \"duplicateClassDescriptors\": ").append(duplicateProviders.values().stream().filter(v -> v.size() > 1).count()).append(",\n")
                .append("    \"inputs\": [\n");
        for (int i = 0; i < inputs.size(); i++) {
            Input input = inputs.get(i);
            json.append("      {\"kind\": \"").append(j(input.kind())).append("\", \"label\": \"").append(j(input.label()))
                    .append("\", \"path\": \"").append(j(input.path().toString())).append("\", \"sha256\": \"")
                    .append(sha256(input.path())).append("\"}").append(i + 1 == inputs.size() ? "\n" : ",\n");
        }
        json.append("    ]\n  },\n  \"countsByKindAndClassification\": {");
        int i = 0;
        for (Map.Entry<String, Set<String>> entry : counts.entrySet()) {
            if (i++ > 0) json.append(',');
            json.append("\n    \"").append(j(entry.getKey())).append("\": ").append(entry.getValue().size());
        }
        json.append("\n  },\n  \"countsByKindAndStatus\": {");
        i = 0;
        for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
            if (i++ > 0) json.append(',');
            json.append("\n    \"").append(j(entry.getKey())).append("\": ").append(entry.getValue());
        }
        json.append("\n  },\n  \"unresolvedReferences\": ").append(unresolvedCount()).append("\n}\n");
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    private static String j(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (Exception e) { throw new IOException(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static ManifestInfo readManifest(Path archive) throws Exception {
        byte[] archiveBytes = Files.readAllBytes(archive);
        String manifest = null;
        int entries = 0;
        int resources = 0;
        boolean resourcesTable = false;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> list = zip.entries();
            while (list.hasMoreElements()) {
                ZipEntry entry = list.nextElement();
                entries++;
                if (entry.getName().equals("manifest.json")) {
                    try (InputStream in = zip.getInputStream(entry)) { manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                }
                if (entry.getName().equals("resources.arsc")) resourcesTable = true;
                if (entry.getName().startsWith("res/") && !entry.isDirectory()) resources++;
            }
        }
        if (manifest == null) throw new IOException("Archive has no root manifest.json: " + archive);
        String pluginClass = capture(manifest, "pluginClassName");
        String name = capture(manifest, "name");
        String version = capture(manifest, "version");
        boolean requires = Pattern.compile("\\\"requiresResources\\\"\\s*:\\s*true").matcher(manifest).find();
        return new ManifestInfo(pluginClass, name, version, requires, resources, resourcesTable,
                entries, archiveBytes.length, sha256(archive), manifest);
    }

    private static String capture(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"?([^,}\\\"]+)\\\"?").matcher(json);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static final class DexData {
        byte[] bytes;
        String sha256;
        String version;
        List<String[]> types = new ArrayList<>();
        List<FieldRef> fields = new ArrayList<>();
        List<MethodRef> methods = new ArrayList<>();
        Set<String> pluginClasses = new HashSet<>();

        static DexData read(Path path) throws Exception {
            DexData result = new DexData();
            result.bytes = Files.readAllBytes(path);
            result.sha256 = sha256(path);
            ByteBuffer b = ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (result.bytes.length < 0x70 || result.bytes[0] != 'd' || result.bytes[1] != 'e' || result.bytes[2] != 'x' || result.bytes[3] != '\n')
                throw new IOException("Not a DEX file: " + path);
            result.version = new String(result.bytes, 4, 3, StandardCharsets.US_ASCII);
            int stringSize = b.getInt(0x38), stringOff = b.getInt(0x3c);
            int typeSize = b.getInt(0x40), typeOff = b.getInt(0x44);
            int protoSize = b.getInt(0x48), protoOff = b.getInt(0x4c);
            int fieldSize = b.getInt(0x50), fieldOff = b.getInt(0x54);
            int methodSize = b.getInt(0x58), methodOff = b.getInt(0x5c);
            int classSize = b.getInt(0x60), classOff = b.getInt(0x64);
            String[] strings = new String[stringSize];
            for (int i = 0; i < stringSize; i++) strings[i] = dexString(result.bytes, b.getInt(stringOff + i * 4));
            String[] typeList = new String[typeSize];
            TreeSet<String> sortedTypes = new TreeSet<>();
            for (int i = 0; i < typeSize; i++) {
                typeList[i] = strings[b.getInt(typeOff + i * 4)];
                sortedTypes.add(typeList[i]);
            }
            for (String type : sortedTypes) result.types.add(new String[]{type});
            Proto[] protos = new Proto[protoSize];
            for (int i = 0; i < protoSize; i++) {
                int off = protoOff + i * 12;
                String ret = typeList[b.getInt(off + 4)];
                int parametersOff = b.getInt(off + 8);
                List<String> parameters = new ArrayList<>();
                if (parametersOff != 0) {
                    int n = Short.toUnsignedInt(b.getShort(parametersOff));
                    for (int j = 0; j < n; j++) parameters.add(typeList[Short.toUnsignedInt(b.getShort(parametersOff + 4 + j * 2))]);
                }
                protos[i] = new Proto(ret, parameters);
            }
            for (int i = 0; i < fieldSize; i++) {
                int off = fieldOff + i * 8;
                String owner = typeList[Short.toUnsignedInt(b.getShort(off))];
                String type = typeList[Short.toUnsignedInt(b.getShort(off + 2))];
                String name = strings[b.getInt(off + 4)];
                result.fields.add(new FieldRef(owner, name, type));
            }
            for (int i = 0; i < methodSize; i++) {
                int off = methodOff + i * 8;
                String owner = typeList[Short.toUnsignedInt(b.getShort(off))];
                Proto proto = protos[Short.toUnsignedInt(b.getShort(off + 2))];
                String name = strings[b.getInt(off + 4)];
                result.methods.add(new MethodRef(owner, name, proto.descriptor()));
            }
            for (int i = 0; i < classSize; i++) {
                int off = classOff + i * 32;
                int classIdx = b.getInt(off);
                result.pluginClasses.add(typeList[classIdx]);
            }
            result.fields.sort(Comparator.comparing(FieldRef::descriptor));
            result.methods.sort(Comparator.comparing(MethodRef::descriptor));
            return result;
        }

        private static String dexString(byte[] bytes, int offset) throws IOException {
            int[] pos = {offset};
            readUleb(bytes, pos); // UTF-16 length (names used here are ASCII-compatible)
            int start = pos[0];
            while (pos[0] < bytes.length && bytes[pos[0]] != 0) pos[0]++;
            if (pos[0] == bytes.length) throw new IOException("Unterminated DEX string");
            return new String(bytes, start, pos[0] - start, StandardCharsets.UTF_8);
        }

        private static int readUleb(byte[] bytes, int[] pos) throws IOException {
            int result = 0, shift = 0;
            while (shift < 35) {
                if (pos[0] >= bytes.length) throw new IOException("Invalid ULEB128");
                int value = bytes[pos[0]++] & 0xff;
                result |= (value & 0x7f) << shift;
                if ((value & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("Invalid ULEB128");
        }
    }
}
