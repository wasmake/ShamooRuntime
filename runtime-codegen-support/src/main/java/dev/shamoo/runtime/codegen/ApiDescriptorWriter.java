package dev.shamoo.runtime.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.shamoo.runtime.codegen.ApiModel.ApiMethod;
import dev.shamoo.runtime.codegen.ApiModel.ApiType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Writes the canonical JVM model consumed by the TypeScript platform generator. */
@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.AvoidLiteralsInIfCondition",
    "PMD.NullAssignment",
    "PMD.OverrideBothEqualsAndHashCodeOnComparable"
})
public final class ApiDescriptorWriter {
    public static final int SCHEMA_VERSION = 2;
    private static final String BUKKIT_EVENT = "org.bukkit.event.Event";
    private static final String CANCELLABLE = "org.bukkit.event.Cancellable";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Coverage write(ApiModel source, String apiVersion, String mapping, Path output,
            List<String> exclusions) throws IOException {
        return write(source, apiVersion, mapping, output, exclusions, List.of());
    }

    public Coverage write(ApiModel source, String apiVersion, String mapping, Path output,
            List<String> exclusions, List<PacketInfo> packets) throws IOException {
        long registrations = packets.stream().mapToLong(packet -> packet.registrations().size()).sum();
        return write(source, apiVersion, mapping, output, exclusions, packets,
                new PacketInventory(packets.size(), registrations, registrations));
    }

    public Coverage write(ApiModel source, String apiVersion, String mapping, Path output,
            List<String> exclusions, List<PacketInfo> packets, PacketInventory expectedPackets) throws IOException {
        Files.createDirectories(output);
        List<Map<String, Object>> declarations = declarations(source);
        List<Map<String, Object>> events = events(source, declarations);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("schemaVersion", SCHEMA_VERSION);
        model.put("platform", source.platform());
        model.put("apiVersion", apiVersion);
        model.put("mapping", mapping);
        model.put("generatedBy", "ShamooRuntime ASM scanner");
        model.put("declarations", declarations);
        model.put("events", events);
        model.put("packets", packets.stream().map(PacketInfo::asJson).toList());
        JSON.writeValue(output.resolve("model.json").toFile(), model);
        Files.writeString(output.resolve("model.json"), Files.readString(output.resolve("model.json")) + '\n');

        long members = declarations.stream().mapToLong(ApiDescriptorWriter::memberCount).sum();
        if (declarations.size() != source.inventory().eligibleTypes()
                || members != source.inventory().eligibleMembers()) {
            throw new IOException("generated model omitted eligible scanner items: scanned "
                    + source.inventory().eligibleTypes() + " types/" + source.inventory().eligibleMembers()
                    + " members, emitted " + declarations.size() + " types/" + members + " members");
        }
        long exceptions = declarations.stream().mapToLong(ApiDescriptorWriter::exceptionCount).sum();
        long registrations = packets.stream().mapToLong(packet -> packet.registrations().size()).sum();
        long registrationIds = packets.stream().flatMap(packet -> packet.registrations().stream())
                .filter(registration -> registration.id() != null).count();
        if (events.size() != source.inventory().eligibleEvents()
                || exceptions != source.inventory().eligibleExceptions()
                || packets.size() != expectedPackets.packetClasses()
                || registrations != expectedPackets.registrations()
                || registrationIds != expectedPackets.registrationIds()) {
            throw new IOException("linked output differs from the independent inventory: events "
                    + source.inventory().eligibleEvents() + '/' + events.size() + ", exceptions "
                    + source.inventory().eligibleExceptions() + '/' + exceptions + ", packets "
                    + expectedPackets.packetClasses() + '/' + packets.size() + ", registrations "
                    + expectedPackets.registrations() + '/' + registrations + ", registration IDs "
                    + expectedPackets.registrationIds() + '/' + registrationIds);
        }
        Coverage coverage = new Coverage(source.inventory().eligibleTypes(), declarations.size(),
                source.inventory().eligibleMembers(), members, source.inventory().eligibleEvents(), events.size(),
                source.inventory().eligibleExceptions(), exceptions, expectedPackets.packetClasses(), packets.size(),
                expectedPackets.registrations(), registrations, expectedPackets.registrationIds(), registrationIds,
                exclusions);
        JSON.writeValue(output.resolve("coverage.json").toFile(), coverage.asJson(source.platform(), apiVersion));
        Files.writeString(output.resolve("coverage.json"), Files.readString(output.resolve("coverage.json")) + '\n');
        return coverage;
    }

    public record PacketInfo(String type, String javaName, List<PacketRegistration> registrations)
            implements Comparable<PacketInfo> {
        public PacketInfo {
            java.util.Objects.requireNonNull(type, "type");
            java.util.Objects.requireNonNull(javaName, "javaName");
            registrations = registrations.stream().distinct().sorted().toList();
        }

        @Override
        public List<PacketRegistration> registrations() {
            return List.copyOf(registrations);
        }

        private Map<String, Object> asJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", type);
            value.put("javaName", javaName);
            value.put("registrations", registrations.stream().map(PacketRegistration::asJson).toList());
            return value;
        }

        @Override
        public int compareTo(PacketInfo other) {
            return javaName.compareTo(other.javaName);
        }
    }

    public record PacketRegistration(String phase, String direction, Integer id)
            implements Comparable<PacketRegistration> {
        public PacketRegistration {
            java.util.Objects.requireNonNull(phase, "phase");
            java.util.Objects.requireNonNull(direction, "direction");
            if (id != null && id < 0) {
                throw new IllegalArgumentException("packet protocol id must be nonnegative");
            }
        }

        private Map<String, Object> asJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("phase", phase);
            value.put("direction", direction);
            if (id != null) {
                value.put("id", id);
            }
            return value;
        }

        @Override
        public int compareTo(PacketRegistration other) {
            int result = phase.compareTo(other.phase);
            result = result == 0 ? direction.compareTo(other.direction) : result;
            return result == 0 ? java.util.Comparator.nullsLast(Integer::compareTo).compare(id, other.id) : result;
        }
    }

    public record PacketInventory(long packetClasses, long registrations, long registrationIds) {
    }

    private static List<Map<String, Object>> declarations(ApiModel source) {
        Map<String, String> names = generatedNames(source.types());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiType type : source.types()) {
            JvmSignatures.ClassTypes signature = JvmSignatures.declaration(
                    type.signature(), type.superName(), type.interfaces());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", type.name());
            value.put("javaName", type.name());
            value.put("name", names.get(type.name()));
            value.put("kind", kind(type));
            int nested = type.name().lastIndexOf('$');
            if (nested >= 0) {
                value.put("nestedIn", type.name().substring(0, nested));
            }
            if (!signature.typeParameters().isEmpty()) {
                value.put("typeParameters", typeParameters(signature.typeParameters()));
            }
            List<String> inherited = new ArrayList<>(signature.inherited());
            inherited.remove("java.lang.Object");
            if (!inherited.isEmpty()) {
                value.put("extends", inherited);
            }
            if (!signature.implemented().isEmpty()) {
                value.put("implements", signature.implemented());
            }
            List<Map<String, Object>> constructors = new ArrayList<>();
            List<Map<String, Object>> methods = new ArrayList<>();
            for (ApiMethod method : type.methods()) {
                if ("<init>".equals(method.name())) {
                    constructors.add(constructor(method));
                } else {
                    methods.add(method(method));
                }
            }
            if (!constructors.isEmpty()) {
                value.put("constructors", constructors);
            }
            if (!methods.isEmpty()) {
                value.put("methods", methods);
            }
            List<Map<String, Object>> fields = type.fields().stream()
                    .filter(field -> (field.access() & Opcodes.ACC_ENUM) == 0)
                    .map(field -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", field.name());
                        item.put("type", JvmSignatures.field(field.signature(), field.descriptor()));
                        optionalTrue(item, "static", (field.access() & Opcodes.ACC_STATIC) != 0);
                        optionalTrue(item, "readonly", (field.access() & Opcodes.ACC_FINAL) != 0);
                        if (field.constant() instanceof String || field.constant() instanceof Number
                                || field.constant() instanceof Boolean) {
                            item.put("constant", field.constant());
                        }
                        optionalTrue(item, "nullable", nullable(field.annotations()));
                        return item;
                    }).toList();
            if (!fields.isEmpty()) {
                value.put("fields", fields);
            }
            if (type.enumType()) {
                value.put("enumConstants", type.fields().stream()
                        .filter(field -> (field.access() & Opcodes.ACC_ENUM) != 0)
                        .map(ApiModel.ApiField::name).toList());
            }
            if (type.functionalInterface()) {
                type.methods().stream().filter(method -> (method.access() & Opcodes.ACC_ABSTRACT) != 0)
                        .filter(method -> !Set.of("equals", "hashCode", "toString").contains(method.name()))
                        .findFirst().ifPresent(method -> value.put("functionalMethod", method.name()));
            }
            result.add(value);
        }
        return result;
    }

    private static Map<String, Object> constructor(ApiMethod method) {
        JvmSignatures.MethodTypes signature = JvmSignatures.method(method.signature(), method.descriptor());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parameters", parameters(method, signature.parameters()));
        if (!signature.typeParameters().isEmpty()) {
            result.put("typeParameters", typeParameters(signature.typeParameters()));
        }
        if (!method.exceptions().isEmpty()) {
            result.put("throws", method.exceptions().stream().map(value -> value.replace('/', '.')).toList());
        }
        return result;
    }

    private static Map<String, Object> method(ApiMethod method) {
        JvmSignatures.MethodTypes signature = JvmSignatures.method(method.signature(), method.descriptor());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", method.name());
        result.put("parameters", parameters(method, signature.parameters()));
        result.put("returns", signature.returns());
        optionalTrue(result, "nullable", method.annotations().stream().anyMatch(annotation ->
                annotation.descriptor().startsWith("return:") && nullable(annotation.descriptor()))
                || method.annotations().stream().anyMatch(annotation -> !annotation.typeUse()
                        && nullable(annotation.descriptor())));
        optionalTrue(result, "static", (method.access() & Opcodes.ACC_STATIC) != 0);
        optionalTrue(result, "default", method.defaultMethod());
        if (!signature.typeParameters().isEmpty()) {
            result.put("typeParameters", typeParameters(signature.typeParameters()));
        }
        if (!method.exceptions().isEmpty()) {
            result.put("throws", method.exceptions().stream().map(value -> value.replace('/', '.')).toList());
        }
        return result;
    }

    private static List<Map<String, Object>> parameters(ApiMethod method, List<String> signatureTypes) {
        Type[] arguments = Type.getArgumentTypes(method.descriptor());
        int signatureOffset = arguments.length - signatureTypes.size();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            Map<String, Object> parameter = new LinkedHashMap<>();
            String scannedName = index < method.parameterNames().size() ? method.parameterNames().get(index) : null;
            parameter.put("name", scannedName == null ? "arg" + index : scannedName);
            boolean varargs = method.varargs() && index == arguments.length - 1;
            Type type = arguments[index];
            String parameterType = index < signatureOffset ? JvmSignatures.descriptor(type)
                    : signatureTypes.get(index - signatureOffset);
            parameter.put("type", varargs && parameterType.endsWith("[]")
                    ? parameterType.substring(0, parameterType.length() - 2) : parameterType);
            int parameterIndex = index;
            optionalTrue(parameter, "nullable", method.annotations().stream().anyMatch(annotation ->
                    annotation.descriptor().startsWith("parameter[" + parameterIndex + "]:")
                            && nullable(annotation.descriptor())));
            optionalTrue(parameter, "varargs", varargs);
            result.add(parameter);
        }
        return result;
    }

    private static List<Map<String, Object>> typeParameters(List<JvmSignatures.TypeParameter> values) {
        return values.stream().map(parameter -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", parameter.name());
            if (parameter.bound() != null && !"java.lang.Object".equals(parameter.bound())) {
                value.put("bound", parameter.bound());
            }
            return value;
        }).toList();
    }

    private static List<Map<String, Object>> events(ApiModel source, List<Map<String, Object>> declarations) {
        Map<String, ApiType> types = new HashMap<>();
        source.types().forEach(type -> types.put(type.name(), type));
        Map<String, String> generatedNames = new HashMap<>();
        declarations.forEach(value -> generatedNames.put((String) value.get("javaName"), (String) value.get("name")));
        return source.types().stream().filter(type -> !type.annotationType() && !type.enumType())
                .filter(type -> (type.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0)
                .filter(type -> isEvent(source.platform(), type, types)).map(type -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("type", generatedNames.get(type.name()));
                    event.put("javaName", type.name());
                    event.put("cancellable", implementsType(type, CANCELLABLE, types));
                    return event;
                }).toList();
    }

    private static boolean isEvent(String platform, ApiType type, Map<String, ApiType> types) {
        if ("paper".equals(platform)) {
            return extendsType(type, BUKKIT_EVENT, types);
        }
        return !type.name().contains("$") && type.name().startsWith("com.velocitypowered.api.event.")
                && type.name().endsWith("Event") && !type.name().contains(".annotation.");
    }

    private static boolean extendsType(ApiType type, String expected, Map<String, ApiType> types) {
        Set<String> visited = new java.util.HashSet<>();
        String current = type.superName();
        while (current != null && visited.add(current)) {
            if (expected.equals(current)) {
                return true;
            }
            ApiType parent = types.get(current);
            current = parent == null ? null : parent.superName();
        }
        return false;
    }

    private static boolean implementsType(ApiType type, String expected, Map<String, ApiType> types) {
        if (type.interfaces().contains(expected)) {
            return true;
        }
        for (String interfaceName : type.interfaces()) {
            ApiType interfaceType = types.get(interfaceName);
            if (interfaceType != null && implementsType(interfaceType, expected, types)) {
                return true;
            }
        }
        ApiType parent = types.get(type.superName());
        return parent != null && implementsType(parent, expected, types);
    }

    private static Map<String, String> generatedNames(List<ApiType> types) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> result = new HashMap<>();
        for (ApiType type : types) {
            String base = sanitize(type.name().substring(type.name().lastIndexOf('.') + 1).replace('$', '_'));
            int count = counts.merge(base, 1, Integer::sum);
            result.put(type.name(), count == 1 ? base : base + '_' + count);
        }
        return result;
    }

    private static String sanitize(String value) {
        String result = value.replaceAll("[^A-Za-z0-9_$]", "_");
        return Character.isJavaIdentifierStart(result.charAt(0)) ? result : '_' + result;
    }

    private static String kind(ApiType type) {
        if (type.enumType()) {
            return "enum";
        }
        if (type.record()) {
            return "record";
        }
        if ((type.access() & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        return (type.access() & Opcodes.ACC_ABSTRACT) != 0 ? "abstract" : "class";
    }

    private static boolean nullable(List<ApiModel.ApiAnnotation> annotations) {
        return annotations.stream().anyMatch(annotation -> nullable(annotation.descriptor()));
    }

    private static boolean nullable(String descriptor) {
        return descriptor.toLowerCase(java.util.Locale.ROOT).contains("nullable");
    }

    private static void optionalTrue(Map<String, Object> value, String key, boolean enabled) {
        if (enabled) {
            value.put(key, true);
        }
    }

    private static long memberCount(Map<String, Object> declaration) {
        long count = 0;
        for (String key : List.of("constructors", "methods", "fields", "enumConstants")) {
            Object value = declaration.get(key);
            if (value instanceof List<?> list) {
                count += list.size();
            }
        }
        return count;
    }

    private static long exceptionCount(Map<String, Object> declaration) {
        long count = 0;
        for (String key : List.of("constructors", "methods")) {
            Object members = declaration.get(key);
            if (members instanceof List<?> list) {
                for (Object member : list) {
                    if (member instanceof Map<?, ?> callable && callable.get("throws") instanceof List<?> thrown) {
                        count += thrown.size();
                    }
                }
            }
        }
        return count;
    }

    /** Counts are derived only from entries serialized in model.json. */
    public record Coverage(long expectedDeclarations, long declarations, long expectedMembers, long members,
            long expectedEvents, long events, long expectedExceptions, long exceptions, long expectedPackets,
            long packets, long expectedPacketRegistrations, long packetRegistrations,
            long expectedPacketRegistrationIds, long packetRegistrationIds, List<String> exclusions) {
        public Coverage {
            exclusions = List.copyOf(exclusions);
        }

        private Map<String, Object> asJson(String platform, String apiVersion) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("platform", platform);
            result.put("apiVersion", apiVersion);
            result.put("declarations", coverage(expectedDeclarations, declarations));
            result.put("members", coverage(expectedMembers, members));
            result.put("events", coverage(expectedEvents, events));
            result.put("exceptions", coverage(expectedExceptions, exceptions));
            result.put("packets", coverage(expectedPackets, packets));
            result.put("packetRegistrations", coverage(expectedPacketRegistrations, packetRegistrations));
            result.put("packetRegistrationIds", coverage(expectedPacketRegistrationIds, packetRegistrationIds));
            result.put("exclusions", exclusions);
            return result;
        }

        private static Map<String, Object> coverage(long expected, long emitted) {
            long percent = expected == 0 ? 100 : emitted * 100 / expected;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expected", expected);
            result.put("emitted", emitted);
            result.put("percent", percent);
            return result;
        }
    }
}
