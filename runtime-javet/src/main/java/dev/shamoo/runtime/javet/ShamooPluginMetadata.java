package dev.shamoo.runtime.javet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.shamoo.runtime.protocol.NodePolicy;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.PluginDescriptor;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.protocol.SemverRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict, immutable view of the shamooc format-2 deployment metadata. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public record ShamooPluginMetadata(
        String packageName,
        PlatformKind platform,
        String entrypoint,
        Set<String> lifecycle,
        Set<String> invocations,
        Map<String, String> services,
        Map<String, String> events,
        Map<String, String> consumers,
        Map<String, String> consumerPolicies,
        Permissions permissions,
        Map<String, Object> data) {
    public static final String FILE_NAME = "shamoo.metadata.json";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_CANONICAL_DEPTH = 64;
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public ShamooPluginMetadata {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(entrypoint, "entrypoint");
        lifecycle = Set.copyOf(lifecycle);
        invocations = Set.copyOf(invocations);
        services = Map.copyOf(services);
        events = Map.copyOf(events);
        consumers = Map.copyOf(consumers);
        consumerPolicies = Map.copyOf(consumerPolicies);
        Objects.requireNonNull(permissions, "permissions");
        data = Map.copyOf(data);
    }

    public static ShamooPluginMetadata load(Path root, PluginDescriptor descriptor, PlatformKind platform) {
        Path path = root.resolve(FILE_NAME).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("compiled plugin metadata is missing: " + FILE_NAME);
        }
        try {
            JsonNode value = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            return parse(value, descriptor, platform);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("compiled plugin metadata is malformed", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read compiled plugin metadata", exception);
        }
    }

    public boolean permitsPlatformOperation(String operation) {
        String lower = operation.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("packet")) {
            return permissions.packets() && invocations.contains("packet");
        }
        if (lower.contains("nms")) {
            return permissions.nms();
        }
        if (lower.contains("event")) {
            return invocations.contains("event");
        }
        if (lower.contains("command")) {
            return invocations.contains("command");
        }
        if (lower.contains("schedule")) {
            return invocations.contains("task");
        }
        return lower.contains("messag") || lower.contains("proxy")
                ? !services.isEmpty() || !events.isEmpty() || !consumers.isEmpty() : false;
    }

    private static ShamooPluginMetadata parse(JsonNode root, PluginDescriptor descriptor, PlatformKind platform) {
        object(root, "", Set.of("formatVersion", "compilerVersion", "packageName", "components", "modules",
                "communication", "permissions", "entrypoints"),
                Set.of("formatVersion", "compilerVersion", "packageName", "components", "modules", "entrypoints"));
        if (integer(root, "formatVersion", "") != FORMAT_VERSION) {
            fail("/formatVersion", "must be 2");
        }
        text(root, "compilerVersion", "");
        String packageName = text(root, "packageName", "");
        String packageId = packageName.substring(packageName.lastIndexOf('/') + 1);
        if (!packageId.equals(descriptor.name())) {
            fail("/packageName", "does not match descriptor name " + descriptor.name());
        }
        array(root.path("components"), "/components");
        array(root.path("modules"), "/modules");
        Set<String> lifecycle = new LinkedHashSet<>();
        Set<String> invocations = new LinkedHashSet<>();
        validateComponents(root.path("components"), lifecycle, invocations);
        validateModules(root.path("modules"));

        JsonNode entrypoints = root.path("entrypoints");
        object(entrypoints, "/entrypoints", Set.of("paper", "velocity"), Set.of());
        String key = platform == PlatformKind.PAPER ? "paper" : "velocity";
        JsonNode selected = entrypoints.path(key);
        object(selected, "/entrypoints/" + key, Set.of("source", "output"), Set.of("source", "output"));
        text(selected, "source", "/entrypoints/" + key);
        String output = text(selected, "output", "/entrypoints/" + key);
        String descriptorEntrypoint = platform == PlatformKind.PAPER
                ? descriptor.platforms().paper().entrypoint() : descriptor.platforms().velocity().entrypoint();
        if (!output.equals(descriptorEntrypoint)) {
            fail("/entrypoints/" + key + "/output", "does not match descriptor entrypoint");
        }

        Permissions permissions = parsePermissions(root.path("permissions"));
        validatePermissions(permissions, descriptor.node(), platform);
        Map<String, String> services = new LinkedHashMap<>();
        Map<String, String> events = new LinkedHashMap<>();
        Map<String, String> consumers = new LinkedHashMap<>();
        Map<String, String> consumerPolicies = new LinkedHashMap<>();
        parseCommunication(root.path("communication"), services, events, consumers, consumerPolicies);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = MAPPER.convertValue(root, Map.class);
        return new ShamooPluginMetadata(packageName, platform, output, lifecycle, invocations,
                services, events, consumers, consumerPolicies, permissions, data);
    }

    private static void validateComponents(JsonNode components, Set<String> lifecycle, Set<String> invocations) {
        for (int index = 0; index < components.size(); index++) {
            String path = "/components/" + index;
            JsonNode component = components.get(index);
            object(component, path, Set.of("id", "kind", "name", "file", "platform", "decorators",
                    "constructor", "properties", "methods", "location"),
                    Set.of("id", "kind", "name", "file", "platform", "decorators", "constructor",
                            "properties", "methods", "location"));
            text(component, "id", path);
            enumText(component, "kind", path,
                    Set.of("plugin", "module", "component", "service", "event-listener", "command", "task"));
            text(component, "name", path);
            text(component, "file", path);
            enumText(component, "platform", path, Set.of("common", "paper", "velocity"));
            decorators(component.path("decorators"), path + "/decorators");
            dependencies(component.path("constructor"), path + "/constructor");
            dependencies(component.path("properties"), path + "/properties");
            array(component.path("methods"), path + "/methods");
            location(component.path("location"), path + "/location");
            for (int methodIndex = 0; methodIndex < component.path("methods").size(); methodIndex++) {
                JsonNode method = component.path("methods").get(methodIndex);
                String methodPath = path + "/methods/" + methodIndex;
                object(method, methodPath, Set.of("name", "lifecycle", "invocation", "decorators",
                        "parameters", "location"), Set.of("name", "decorators", "parameters", "location"));
                text(method, "name", methodPath);
                optionalEnum(method, "lifecycle", methodPath,
                        Set.of("load", "enable", "ready", "drain", "disable", "unload"), lifecycle);
                optionalEnum(method, "invocation", methodPath,
                        Set.of("event", "command", "task", "packet"), invocations);
                decorators(method.path("decorators"), methodPath + "/decorators");
                dependencies(method.path("parameters"), methodPath + "/parameters");
                location(method.path("location"), methodPath + "/location");
            }
        }
    }

    private static void validateModules(JsonNode modules) {
        for (int index = 0; index < modules.size(); index++) {
            String path = "/modules/" + index;
            JsonNode module = modules.get(index);
            object(module, path, Set.of("id", "name", "imports", "declarations", "exports", "global", "location"),
                    Set.of("id", "name", "imports", "declarations", "exports", "global", "location"));
            text(module, "id", path);
            text(module, "name", path);
            imports(module.path("imports"), path + "/imports");
            strings(module.path("declarations"), path + "/declarations");
            strings(module.path("exports"), path + "/exports");
            if (!module.path("global").isBoolean()) {
                fail(path + "/global", "must be a boolean");
            }
            location(module.path("location"), path + "/location");
        }
    }

    private static void imports(JsonNode values, String path) {
        array(values, path);
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            String itemPath = path + "/" + index;
            object(value, itemPath, Set.of("id", "forwardRef"), Set.of("id", "forwardRef"));
            text(value, "id", itemPath);
            booleanField(value, "forwardRef", itemPath);
        }
    }

    private static void decorators(JsonNode values, String path) {
        array(values, path);
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            String itemPath = path + "/" + index;
            object(value, itemPath, Set.of("name", "arguments", "location"),
                    Set.of("name", "arguments", "location"));
            text(value, "name", itemPath);
            array(value.path("arguments"), itemPath + "/arguments");
            canonicalValues(value.path("arguments"), itemPath + "/arguments", 0);
            location(value.path("location"), itemPath + "/location");
        }
    }

    private static void dependencies(JsonNode values, String path) {
        array(values, path);
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            String itemPath = path + "/" + index;
            object(value, itemPath, Set.of("index", "property", "token", "optional", "all", "lazy", "name",
                    "qualifier", "location"), Set.of("token", "location"));
            optionalInteger(value, "index", itemPath);
            optionalText(value, "property", itemPath);
            optionalBoolean(value, "optional", itemPath);
            optionalBoolean(value, "all", itemPath);
            optionalBoolean(value, "lazy", itemPath);
            optionalText(value, "name", itemPath);
            optionalText(value, "qualifier", itemPath);
            token(value.path("token"), itemPath + "/token");
            location(value.path("location"), itemPath + "/location");
        }
    }

    private static void token(JsonNode value, String path) {
        object(value, path, Set.of("kind", "name", "module", "value"), Set.of("kind"));
        String kind = enumText(value, "kind", path, Set.of("class", "token"));
        if (value.has("value")) {
            if (!"token".equals(kind) || value.has("name") || value.has("module")) {
                fail(path, "value tokens cannot contain name or module");
            }
            canonicalValue(value.path("value"), path + "/value", 0);
        } else {
            text(value, "name", path);
            text(value, "module", path);
        }
    }

    private static void canonicalValues(JsonNode values, String path, int depth) {
        for (int index = 0; index < values.size(); index++) {
            canonicalValue(values.get(index), path + "/" + index, depth);
        }
    }

    @SuppressWarnings("deprecation")
    private static void canonicalValue(JsonNode value, String path, int depth) {
        if (depth > MAX_CANONICAL_DEPTH) {
            fail(path, "canonical value nesting exceeds " + MAX_CANONICAL_DEPTH);
        }
        if (value.isNull() || value.isBoolean() || value.isNumber() || value.isTextual()) {
            return;
        }
        if (value.isArray()) {
            canonicalValues(value, path, depth + 1);
            return;
        }
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> canonicalValue(entry.getValue(),
                    path + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"), depth + 1));
            return;
        }
        fail(path, "must be a canonical JSON value");
    }

    private static Permissions parsePermissions(JsonNode value) {
        if (value.isMissingNode()) {
            return Permissions.NONE;
        }
        object(value, "/permissions", Set.of("builtins", "filesystem", "network", "workers", "childProcess",
                "nativeAddons", "nms", "packets"), Set.of());
        Set<String> builtins = strings(value.path("builtins"), "/permissions/builtins");
        Set<String> read = Set.of();
        Set<String> write = Set.of();
        JsonNode filesystem = value.path("filesystem");
        if (!filesystem.isMissingNode()) {
            object(filesystem, "/permissions/filesystem", Set.of("read", "write"), Set.of("read", "write"));
            read = strings(filesystem.path("read"), "/permissions/filesystem/read");
            write = strings(filesystem.path("write"), "/permissions/filesystem/write");
        }
        return new Permissions(builtins, read, write, bool(value, "network"), bool(value, "workers"),
                bool(value, "childProcess"), bool(value, "nativeAddons"), bool(value, "nms"),
                bool(value, "packets"));
    }

    private static void validatePermissions(Permissions value, NodePolicy node, PlatformKind platform) {
        if (!value.builtins().equals(Set.copyOf(node.builtins()))
                || !value.read().equals(Set.copyOf(node.filesystem().read()))
                || !value.write().equals(Set.copyOf(node.filesystem().write()))
                || value.network() != node.network() || value.workers() != node.workers()
                || value.childProcess() != node.childProcess() || value.nativeAddons() != node.nativeAddons()) {
            fail("/permissions", "does not exactly match descriptor node capabilities");
        }
        if (platform != PlatformKind.PAPER && (value.nms() || value.packets())) {
            fail("/permissions", "NMS and packet capabilities require Paper");
        }
    }

    private static void parseCommunication(JsonNode value, Map<String, String> services,
            Map<String, String> events, Map<String, String> consumers, Map<String, String> consumerPolicies) {
        if (value.isMissingNode()) {
            return;
        }
        object(value, "/communication", Set.of("services", "events", "consumers"),
                Set.of("services", "events", "consumers"));
        contracts(value.path("services"), "/communication/services", services, true);
        contracts(value.path("events"), "/communication/events", events, false);
        JsonNode values = value.path("consumers");
        array(values, "/communication/consumers");
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String path = "/communication/consumers/" + index;
            object(item, path, Set.of("id", "versionRange", "dependentReload"),
                    Set.of("id", "versionRange", "dependentReload"));
            String id = text(item, "id", path);
            String range = text(item, "versionRange", path);
            new SemverRange(range);
            String policy = text(item, "dependentReload", path);
            if (!Set.of("keep-running", "reload").contains(policy)) {
                fail(path + "/dependentReload", "is invalid");
            }
            duplicate(consumers.put(id, range), path + "/id");
            consumerPolicies.put(id, policy);
        }
    }

    private static void contracts(JsonNode values, String base, Map<String, String> target, boolean service) {
        array(values, base);
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String path = base + "/" + index;
            Set<String> allowed = service ? Set.of("id", "version", "componentId", "methods")
                    : Set.of("id", "version");
            object(item, path, allowed, allowed);
            String id = text(item, "id", path);
            String version = text(item, "version", path);
            new SemanticVersion(version);
            if (service) {
                text(item, "componentId", path);
                strings(item.path("methods"), path + "/methods");
            }
            duplicate(target.put(id, version), path + "/id");
        }
    }

    private static void duplicate(String previous, String path) {
        if (previous != null) {
            fail(path, "is duplicated");
        }
    }

    private static void location(JsonNode value, String path) {
        object(value, path, Set.of("file", "line", "column"), Set.of("file", "line", "column"));
        text(value, "file", path);
        integer(value, "line", path);
        integer(value, "column", path);
    }

    private static void optionalEnum(JsonNode node, String field, String path, Set<String> allowed, Set<String> sink) {
        if (node.has(field)) {
            String value = text(node, field, path);
            if (!allowed.contains(value)) {
                fail(path + "/" + field, "is invalid");
            }
            sink.add(value);
        }
    }

    private static Set<String> strings(JsonNode value, String path) {
        if (value.isMissingNode()) {
            return Set.of();
        }
        array(value, path);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < value.size(); index++) {
            if (!value.get(index).isTextual() || value.get(index).textValue().isBlank()) {
                fail(path + "/" + index, "must be text");
            }
            if (!result.add(value.get(index).textValue())) {
                fail(path + "/" + index, "is duplicated");
            }
        }
        return Set.copyOf(result);
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) {
            return false;
        }
        if (!value.isBoolean()) {
            fail("/permissions/" + field, "must be a boolean");
        }
        return value.booleanValue();
    }

    private static String text(JsonNode node, String field, String path) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            fail(path + "/" + field, "must be non-blank text");
        }
        return value.textValue();
    }

    private static String enumText(JsonNode node, String field, String path, Set<String> allowed) {
        String value = text(node, field, path);
        if (!allowed.contains(value)) {
            fail(path + "/" + field, "is invalid");
        }
        return value;
    }

    private static void optionalText(JsonNode node, String field, String path) {
        if (node.has(field)) {
            text(node, field, path);
        }
    }

    private static void optionalInteger(JsonNode node, String field, String path) {
        if (node.has(field)) {
            integer(node, field, path);
        }
    }

    private static void optionalBoolean(JsonNode node, String field, String path) {
        if (node.has(field)) {
            booleanField(node, field, path);
        }
    }

    private static void booleanField(JsonNode node, String field, String path) {
        if (!node.path(field).isBoolean()) {
            fail(path + "/" + field, "must be a boolean");
        }
    }

    private static int integer(JsonNode node, String field, String path) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            fail(path + "/" + field, "must be an integer");
        }
        return value.intValue();
    }

    private static void array(JsonNode value, String path) {
        if (!value.isArray()) {
            fail(path, "must be an array");
        }
    }

    private static void object(JsonNode value, String path, Set<String> allowed, Set<String> required) {
        if (!value.isObject()) {
            fail(path, "must be an object");
        }
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                fail(path + "/" + field, "is unknown");
            }
        });
        for (String field : required) {
            if (!value.has(field)) {
                fail(path + "/" + field, "is required");
            }
        }
    }

    private static void fail(String path, String message) {
        String pointer = path.isEmpty() ? "/" : path;
        throw new IllegalArgumentException("invalid " + FILE_NAME + " at " + pointer + ": " + message);
    }

    /** Compiler capability claims, normalized to exact immutable sets and booleans. */
    public record Permissions(Set<String> builtins, Set<String> read, Set<String> write,
            boolean network, boolean workers, boolean childProcess, boolean nativeAddons,
            boolean nms, boolean packets) {
        private static final Permissions NONE = new Permissions(Set.of(), Set.of(), Set.of(),
                false, false, false, false, false, false);
        public Permissions {
            builtins = Set.copyOf(builtins);
            read = Set.copyOf(read);
            write = Set.copyOf(write);
        }
    }
}
