package dev.shamoo.runtime.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Independent class-file inventory check for checked-in generated platform models. */
@SuppressWarnings("PMD.NullAssignment")
public final class ApiCoverageVerifierCli {
    private static final int MINIMUM_ARGUMENTS = 4;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PAPER_PACKAGES = Set.of(
            "org.bukkit.", "io.papermc.paper.", "com.destroystokyo.paper.", "net.kyori.adventure.");
    private static final Set<String> VELOCITY_PACKAGES = Set.of(
            "com.velocitypowered.api.", "net.kyori.adventure.");

    private ApiCoverageVerifierCli() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < MINIMUM_ARGUMENTS) {
            throw new IllegalArgumentException(
                    "usage: <paper|velocity> <model.json> <coverage.json> <artifact>...");
        }
        String platform = arguments[0];
        Set<String> packages = switch (platform) {
            case "paper" -> PAPER_PACKAGES;
            case "velocity" -> VELOCITY_PACKAGES;
            default -> throw new IllegalArgumentException("unsupported platform: " + platform);
        };
        Map<String, ClassInventory> classes = inventory(
                Arrays.stream(arguments, 3, arguments.length).map(Path::of).toList(), packages);
        verify(platform, classes, JSON.readTree(Path.of(arguments[1]).toFile()),
                JSON.readTree(Path.of(arguments[2]).toFile()));
    }

    private static Map<String, ClassInventory> inventory(List<Path> artifacts, Set<String> packages)
            throws IOException {
        Map<String, ClassInventory> classes = new HashMap<>();
        for (Path artifact : artifacts.stream().sorted().toList()) {
            try (JarFile jar = new JarFile(artifact.toFile(), false, JarFile.OPEN_READ, Runtime.version())) {
                for (var entry : jar.versionedStream().filter(item -> item.getName().endsWith(".class")).toList()) {
                    try (var input = jar.getInputStream(entry)) {
                        InventoryVisitor visitor = new InventoryVisitor();
                        new ClassReader(input.readAllBytes()).accept(visitor,
                                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        ClassInventory value = visitor.result();
                        if (value != null && packages.stream().anyMatch(value.name()::startsWith)) {
                            classes.put(value.name(), value);
                        }
                    }
                }
            }
        }
        return classes;
    }

    private static void verify(String platform, Map<String, ClassInventory> classes, JsonNode model,
            JsonNode coverage) {
        require(platform.equals(model.path("platform").asText()), "model platform does not match task");
        require(platform.equals(coverage.path("platform").asText()), "coverage platform does not match task");
        JsonNode declarations = model.path("declarations");
        Set<String> emittedTypes = new HashSet<>();
        long emittedMembers = 0;
        long emittedExceptions = 0;
        for (JsonNode declaration : declarations) {
            emittedTypes.add(declaration.path("javaName").asText());
            for (String memberGroup : List.of("constructors", "methods", "fields", "enumConstants")) {
                emittedMembers += declaration.path(memberGroup).size();
            }
            for (String callableGroup : List.of("constructors", "methods")) {
                for (JsonNode callable : declaration.path(callableGroup)) {
                    emittedExceptions += callable.path("throws").size();
                }
            }
        }
        require(emittedTypes.equals(classes.keySet()), difference(classes.keySet(), emittedTypes));

        long expectedMembers = classes.values().stream().mapToLong(ClassInventory::members).sum();
        long expectedExceptions = classes.values().stream().mapToLong(ClassInventory::exceptions).sum();
        Set<String> expectedEvents = events(platform, classes);
        Set<String> emittedEvents = new HashSet<>();
        model.path("events").forEach(event -> emittedEvents.add(event.path("javaName").asText()));
        require(emittedEvents.equals(expectedEvents), "event inventory differs: " + difference(expectedEvents,
                emittedEvents));

        verifyCoverage(coverage, "declarations", classes.size(), declarations.size());
        verifyCoverage(coverage, "members", expectedMembers, emittedMembers);
        verifyCoverage(coverage, "events", expectedEvents.size(), emittedEvents.size());
        verifyCoverage(coverage, "exceptions", expectedExceptions, emittedExceptions);
    }

    private static Set<String> events(String platform, Map<String, ClassInventory> classes) {
        Set<String> result = new HashSet<>();
        classes.values().stream()
                .filter(value -> (value.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0)
                .filter(value -> "paper".equals(platform)
                        ? extendsType(value, "org.bukkit.event.Event", classes)
                        : !value.name().contains("$") && value.name().startsWith("com.velocitypowered.api.event.")
                                && value.name().endsWith("Event") && !value.name().contains(".annotation."))
                .forEach(value -> result.add(value.name()));
        return result;
    }

    private static boolean extendsType(ClassInventory value, String expected,
            Map<String, ClassInventory> classes) {
        Set<String> visited = new HashSet<>();
        String parent = value.superName();
        while (parent != null && visited.add(parent)) {
            if (expected.equals(parent)) {
                return true;
            }
            ClassInventory parentValue = classes.get(parent);
            parent = parentValue == null ? null : parentValue.superName();
        }
        return false;
    }

    private static void verifyCoverage(JsonNode coverage, String name, long expected, long emitted) {
        JsonNode value = coverage.path(name);
        require(value.path("expected").asLong(-1) == expected, name + " expected count differs from artifacts");
        require(value.path("emitted").asLong(-1) == emitted, name + " emitted count differs from model");
        require(value.path("percent").asInt(-1) == 100, name + " coverage is not 100 percent");
    }

    private static String difference(Set<String> expected, Set<String> emitted) {
        Set<String> missing = new java.util.TreeSet<>(expected);
        missing.removeAll(emitted);
        Set<String> stale = new java.util.TreeSet<>(emitted);
        stale.removeAll(expected);
        return "generated API types differ: missing=" + missing + ", stale=" + stale;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record ClassInventory(String name, String superName, int access, long members, long exceptions) {
    }

    private static final class InventoryVisitor extends ClassVisitor {
        private String name;
        private String superName;
        private int access;
        private long members;
        private long exceptions;

        private InventoryVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int classAccess, String internalName, String signature, String parent,
                String[] interfaces) {
            name = internalName.replace('/', '.');
            superName = parent == null ? null : parent.replace('/', '.');
            access = classAccess;
        }

        @Override
        public FieldVisitor visitField(int fieldAccess, String fieldName, String descriptor, String signature,
                Object value) {
            if ((fieldAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                    && (fieldAccess & Opcodes.ACC_SYNTHETIC) == 0) {
                members++;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int methodAccess, String methodName, String descriptor, String signature,
                String[] thrown) {
            if ((methodAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                    && (methodAccess & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0
                    && !"<clinit>".equals(methodName)) {
                members++;
                exceptions += thrown == null ? 0 : thrown.length;
            }
            return null;
        }

        private ClassInventory result() {
            if (name == null || (access & Opcodes.ACC_PUBLIC) == 0 || (access & Opcodes.ACC_SYNTHETIC) != 0) {
                return null;
            }
            return new ClassInventory(name, superName, access, members, exceptions);
        }
    }
}
