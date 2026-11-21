import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Deterministic class-file ABI inventory for the Orbit CS3 descriptor facade.
 * It reads class-file bytes and source package declarations only; it never loads
 * or initializes a facade or plugin class.
 */
public final class Cs3FacadeAbiInventory {
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?");
    private static final Pattern TOP_LEVEL_JAVA = Pattern.compile("(?m)^\\s*(?:(?:public|protected|private|abstract|final|static|sealed|strictfp)\\s+)*(?:class|enum|interface|record)\\s+([\\w$]+)");
    private static final Pattern TOP_LEVEL_KOTLIN = Pattern.compile("(?m)^\\s*(?:(?:public|internal|data|open|final|sealed|abstract)\\s+)*(?:class|enum\\s+class|interface|object)\\s+([\\w$]+)");
    private static final Pattern KOTLIN_JVM_NAME = Pattern.compile("(?m)^\\s*@file:JvmName\\(\"([\\w$]+)\"\\)");

    private Cs3FacadeAbiInventory() { }

    private record Symbol(String kind, String reference, String evidenceSet) { }
    private record Member(int access, String name, String descriptor) { }
    private record ClassFile(String internalName, int access, Map<String, Member> fields,
                             Map<String, Member> methods, Path file) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: Cs3FacadeAbiInventory <matrix-contract.tsv> <supplemental-contract.tsv> <source-root> <compiler-id> <class-dir> [class-dir ...]");
            System.exit(2);
        }

        List<Symbol> symbols = new ArrayList<>();
        readContract(Path.of(args[0]), "matrix", symbols);
        readContract(Path.of(args[1]), "supplemental", symbols);
        validateSourcePaths(Path.of(args[2]));

        Map<String, ClassFile> classes = new TreeMap<>();
        for (int i = 4; i < args.length; i++) {
            scanClassPath(Path.of(args[i]), classes);
        }

        symbols.sort(Comparator.comparing(Symbol::evidenceSet)
                .thenComparing(Symbol::kind).thenComparing(Symbol::reference));
        int found = 0;
        List<String> missing = new ArrayList<>();
        System.out.println("BUILD\tcompiler\t" + args[3].replace('\t', ' '));
        System.out.println("set\tkind\treference\tstatus\tclassfile");
        for (Symbol symbol : symbols) {
            String owner = ownerOf(symbol.reference());
            ClassFile file = classes.get(owner);
            boolean present = false;
            String source = file == null ? "" : file.file().toString();
            if (symbol.kind().equals("type")) {
                present = file != null && (file.access() & ACC_PUBLIC) != 0;
            } else if (file != null && (file.access() & ACC_PUBLIC) != 0) {
                String memberKey = memberKey(symbol.kind(), symbol.reference());
                Map<String, Member> members = symbol.kind().equals("field") ? file.fields() : file.methods();
                Member member = members.get(memberKey);
                boolean visibilityMatches = member != null && (member.access() & ACC_PUBLIC) != 0;
                boolean shapeMatches = member != null && (symbol.kind().equals("field")
                        ? (member.access() & (ACC_STATIC | 0x0010)) == (ACC_STATIC | 0x0010)
                        : staticShapeMatches(owner, member));
                present = visibilityMatches && shapeMatches;
            }
            String status = present ? "present" : "MISSING";
            System.out.println(symbol.evidenceSet() + "\t" + symbol.kind() + "\t"
                    + symbol.reference() + "\t" + status + "\t" + source);
            if (present) found++;
            else missing.add(symbol.evidenceSet() + " " + symbol.kind() + " " + symbol.reference());
        }
        long matrixTypes = symbols.stream().filter(s -> s.evidenceSet().equals("matrix") && s.kind().equals("type")).count();
        long matrixFields = symbols.stream().filter(s -> s.evidenceSet().equals("matrix") && s.kind().equals("field")).count();
        long matrixMethods = symbols.stream().filter(s -> s.evidenceSet().equals("matrix") && s.kind().equals("method")).count();
        System.out.println("SUMMARY matrix_types=" + matrixTypes + " matrix_fields=" + matrixFields
                + " matrix_methods=" + matrixMethods + " supplemental="
                + symbols.stream().filter(s -> s.evidenceSet().equals("supplemental")).count()
                + " present=" + found + " missing=" + missing.size());
        for (String item : missing) System.err.println("MISSING " + item);
        if (!missing.isEmpty()) System.exit(1);
    }

    private static void readContract(Path file, String set, List<Symbol> output) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).replace("\uFEFF", "").startsWith("kind\treference")) {
            throw new IOException("Unexpected contract header in " + file);
        }
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).isBlank()) continue;
            String[] columns = lines.get(line).split("\t", -1);
            if (columns.length < 2 || !Set.of("type", "field", "method").contains(columns[0])
                    || columns[1].isBlank()) {
                throw new IOException("Malformed contract row " + (line + 1) + " in " + file);
            }
            output.add(new Symbol(columns[0], columns[1], set));
        }
    }

    private static void validateSourcePaths(Path sourceRoot) throws IOException {
        Path packageRoot = sourceRoot.resolve("com/lagradost/cloudstream3").normalize();
        if (!packageRoot.startsWith(sourceRoot.normalize())) {
            throw new IOException("Source package root escapes source root");
        }
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher packageMatcher = PACKAGE.matcher(text);
                if (!packageMatcher.find()) throw new IOException("Missing package declaration: " + file);
                String declaredPackage = packageMatcher.group(1);
                Path expectedParent = sourceRoot.resolve(declaredPackage.replace(".", "/")).normalize();
                if (!file.getParent().toAbsolutePath().normalize().equals(expectedParent.toAbsolutePath().normalize())) {
                    throw new IOException("Source package/path mismatch: " + file + " declares " + declaredPackage);
                }
                Pattern classPattern = file.toString().endsWith(".kt") ? TOP_LEVEL_KOTLIN : TOP_LEVEL_JAVA;
                Matcher classMatcher = classPattern.matcher(text);
                String declaredClass;
                if (classMatcher.find()) {
                    declaredClass = classMatcher.group(1);
                } else if (file.toString().endsWith(".kt")) {
                    Matcher jvmNameMatcher = KOTLIN_JVM_NAME.matcher(text);
                    if (!jvmNameMatcher.find()) throw new IOException("No top-level class or file JVM name: " + file);
                    declaredClass = jvmNameMatcher.group(1);
                } else {
                    throw new IOException("No top-level class declaration: " + file);
                }
                String expectedFile = declaredClass + (file.toString().endsWith(".kt") ? ".kt" : ".java");
                if (!file.getFileName().toString().equals(expectedFile)) {
                    throw new IOException("Source filename/class mismatch: " + file + " declares " + declaredClass);
                }
            }
        }
    }

    private static void scanClassPath(Path root, Map<String, ClassFile> classes) throws IOException {
        if (Files.isDirectory(root)) {
            scanClassDirectory(root, classes);
            return;
        }
        if (Files.isRegularFile(root) && root.toString().endsWith(".jar")) {
            scanJar(root, classes);
            return;
        }
        throw new IOException("Missing class directory or JAR: " + root);
    }

    private static void scanClassDirectory(Path root, Map<String, ClassFile> classes) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Missing class output directory: " + root);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(normalizedRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                ClassFile parsed = parseClass(file);
                Path expected = normalizedRoot.resolve(parsed.internalName() + ".class").normalize();
                if (!file.toAbsolutePath().normalize().equals(expected)) {
                    throw new IOException("Classfile path/package mismatch: " + file + " declares " + parsed.internalName());
                }
                ClassFile previous = classes.putIfAbsent(parsed.internalName(), parsed);
                if (previous != null) throw new IOException("Duplicate compiled facade class " + parsed.internalName()
                        + " in " + previous.file() + " and " + parsed.file());
            }
        }
    }

    private static void scanJar(Path path, Map<String, ClassFile> classes) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            List<JarEntry> entries = new ArrayList<>();
            Enumeration<JarEntry> all = jar.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) entries.add(entry);
            }
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry entry : entries) {
                Path evidencePath = Path.of(path.toString() + "!" + entry.getName());
                ClassFile parsed;
                try (var stream = jar.getInputStream(entry)) {
                    parsed = parseClass(stream, evidencePath);
                }
                ClassFile previous = classes.putIfAbsent(parsed.internalName(), parsed);
                if (previous != null) throw new IOException("Duplicate compiled ABI class " + parsed.internalName()
                        + " in " + previous.file() + " and " + parsed.file());
            }
        }
    }

    private static ClassFile parseClass(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            return parseClass(stream, file);
        }
    }

    private static ClassFile parseClass(java.io.InputStream stream, Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("Bad classfile magic: " + file);
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            Object[] cp = new Object[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> cp[i] = in.readUTF();
                    case 3, 4 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; }
                    case 7 -> cp[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("Unsupported constant-pool tag " + tag + " in " + file);
                }
            }
            int access = in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            in.readUnsignedShort(); // super class
            String internalName = (String) cp[(Integer) cp[thisClass]];
            int interfaces = in.readUnsignedShort();
            in.skipNBytes(2L * interfaces);
            Map<String, Member> fields = readMembers(in, cp);
            Map<String, Member> methods = readMembers(in, cp);
            return new ClassFile(internalName, access, fields, methods, file);
        }
    }

    private static Map<String, Member> readMembers(DataInputStream in, Object[] cp) throws IOException {
        int count = in.readUnsignedShort();
        Map<String, Member> members = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = (String) cp[in.readUnsignedShort()];
            String descriptor = (String) cp[in.readUnsignedShort()];
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.readUnsignedShort(); // attribute name
                in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
            }
            members.put(name + descriptor, new Member(access, name, descriptor));
        }
        return members;
    }

    private static boolean staticShapeMatches(String owner, Member member) {
        if (member.name().equals("<init>")) return (member.access() & ACC_STATIC) == 0;
        boolean expectedStatic = owner.endsWith("Kt") || member.name().endsWith("$default")
                || member.name().equals("copy$default");
        if (owner.equals("com/lagradost/cloudstream3/SearchQuality")
                && Set.of("values", "valueOf", "getEntries").contains(member.name())) expectedStatic = true;
        if (owner.equals("com/lagradost/cloudstream3/extractors/helper/AesHelper")
                && member.name().equals("cryptoAESHandler$default")) expectedStatic = true;
        return ((member.access() & ACC_STATIC) != 0) == expectedStatic;
    }

    private static String ownerOf(String reference) throws IOException {
        if (!reference.startsWith("L")) throw new IOException("Invalid owner descriptor " + reference);
        int end = reference.indexOf(';');
        if (end < 0) throw new IOException("Invalid owner descriptor " + reference);
        return reference.substring(1, end);
    }

    private static String memberKey(String kind, String reference) throws IOException {
        int marker = reference.indexOf("->");
        if (marker < 0) throw new IOException("Invalid member reference " + reference);
        String item = reference.substring(marker + 2);
        if (kind.equals("field")) {
            int descriptor = item.indexOf(':');
            if (descriptor < 0) throw new IOException("Invalid field reference " + reference);
            return item.substring(0, descriptor) + item.substring(descriptor + 1);
        }
        int descriptor = item.indexOf('(');
        if (descriptor < 0) throw new IOException("Invalid method reference " + reference);
        return item;
    }
}
