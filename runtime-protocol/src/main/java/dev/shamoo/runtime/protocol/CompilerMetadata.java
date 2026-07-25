package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict compiler-owned metadata embedded in a manifest v2 descriptor. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidFieldNameMatchingMethodName"})
public final class CompilerMetadata {
    private static final int MAX_CANONICAL_DEPTH = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JsonNode value;
    private final String version;
    private final Set<String> lifecycle;
    private final Set<String> invocations;
    private final Map<String, String> componentPlatforms;
    private final Map<MethodId, MethodAuthorization> methods;
    private final Map<String, String> services;
    private final Map<String, String> events;
    private final Map<String, String> consumers;
    private final Map<String, String> consumerPolicies;
    private final Map<String, ServiceProvider> serviceProviders;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CompilerMetadata(JsonNode input) {
        value = Objects.requireNonNull(input, "compiler metadata").deepCopy();
        object(value, "", Set.of("version", "components", "modules", "communication"),
                Set.of("version", "components", "modules", "communication"));
        version = text(value, "version", "");
        array(value.path("components"), "/components");
        array(value.path("modules"), "/modules");
        lifecycle = new LinkedHashSet<>();
        invocations = new LinkedHashSet<>();
        Map<String, String> componentValues = new LinkedHashMap<>();
        Map<MethodId, MethodAuthorization> methodValues = new LinkedHashMap<>();
        validateComponents(value.path("components"), lifecycle, invocations, componentValues, methodValues);
        componentPlatforms = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(componentValues));
        methods = Map.copyOf(methodValues);
        validateModules(value.path("modules"));
        Map<String, String> serviceValues = new LinkedHashMap<>();
        Map<String, String> eventValues = new LinkedHashMap<>();
        Map<String, String> consumerValues = new LinkedHashMap<>();
        Map<String, String> policyValues = new LinkedHashMap<>();
        Map<String, ServiceProvider> providerValues = new LinkedHashMap<>();
        parseCommunication(value.path("communication"), serviceValues, eventValues,
                consumerValues, policyValues, componentPlatforms, providerValues);
        services = Map.copyOf(serviceValues);
        events = Map.copyOf(eventValues);
        consumers = Map.copyOf(consumerValues);
        consumerPolicies = Map.copyOf(policyValues);
        serviceProviders = Map.copyOf(providerValues);
    }

    public String version() {
        return version;
    }

    public Set<String> lifecycle() {
        return Set.copyOf(lifecycle);
    }

    public Set<String> invocations() {
        return Set.copyOf(invocations);
    }

    public Map<String, String> componentPlatforms() {
        return componentPlatforms;
    }

    public Map<MethodId, MethodAuthorization> methods() {
        return methods;
    }

    public Map<String, String> services() {
        return services;
    }

    public Map<String, String> events() {
        return events;
    }

    public Map<String, String> consumers() {
        return consumers;
    }

    public Map<String, String> consumerPolicies() {
        return consumerPolicies;
    }

    /** Returns the exact compiler object as ordinary JSON-compatible values for host exposure. */
    public Map<String, Object> data() {
        return MAPPER.convertValue(value, MAP_TYPE);
    }

    @JsonValue
    public JsonNode json() {
        return value.deepCopy();
    }

    void validatePlatforms(PlatformTargets targets) {
        int index = 0;
        for (String platform : componentPlatforms.values()) {
            if ("paper".equals(platform) && !targets.paper().enabled()) {
                fail("/components/" + index + "/platform", "requires the Paper target to be enabled");
            }
            if ("velocity".equals(platform) && !targets.velocity().enabled()) {
                fail("/components/" + index + "/platform", "requires the Velocity target to be enabled");
            }
            index++;
        }
        if (targets.paper().enabled() && targets.velocity().enabled()) {
            for (ServiceProvider provider : serviceProviders.values()) {
                if (!"common".equals(componentPlatforms.get(provider.componentId()))) {
                    fail(provider.path(), "must reference a common component when both targets are enabled");
                }
            }
        }
    }

    /** Exact compiler component and decorated method identity. */
    public record MethodId(String componentId, String method) {
        public MethodId {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(method, "method");
        }
    }

    /** Platform and optional lifecycle/invocation classification for one exact method. */
    public record MethodAuthorization(String platform, String lifecycle, String invocation) {
        public MethodAuthorization {
            Objects.requireNonNull(platform, "platform");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompilerMetadata metadata && value.equals(metadata.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }

    private static void validateComponents(JsonNode components, Set<String> lifecycle, Set<String> invocations,
            Map<String, String> componentPlatforms, Map<MethodId, MethodAuthorization> methods) {
        for (int index = 0; index < components.size(); index++) {
            String path = "/components/" + index;
            JsonNode component = components.get(index);
            object(component, path, Set.of("id", "kind", "name", "file", "platform", "decorators",
                    "constructor", "properties", "methods", "location"),
                    Set.of("id", "kind", "name", "file", "platform", "decorators", "constructor",
                            "properties", "methods", "location"));
            String componentId = text(component, "id", path);
            if (componentPlatforms.containsKey(componentId)) {
                fail(path + "/id", "is duplicated");
            }
            enumText(component, "kind", path,
                    Set.of("plugin", "module", "component", "service", "event-listener", "command", "task"));
            text(component, "name", path);
            text(component, "file", path);
            String platform = enumText(component, "platform", path, Set.of("common", "paper", "velocity"));
            componentPlatforms.put(componentId, platform);
            decorators(component.path("decorators"), path + "/decorators");
            dependencies(component.path("constructor"), path + "/constructor");
            dependencies(component.path("properties"), path + "/properties");
            array(component.path("methods"), path + "/methods");
            location(component.path("location"), path + "/location");
            Set<String> methodNames = new LinkedHashSet<>();
            for (int methodIndex = 0; methodIndex < component.path("methods").size(); methodIndex++) {
                JsonNode method = component.path("methods").get(methodIndex);
                String methodPath = path + "/methods/" + methodIndex;
                object(method, methodPath, Set.of("name", "lifecycle", "invocation", "decorators",
                        "parameters", "location"), Set.of("name", "decorators", "parameters", "location"));
                String methodName = text(method, "name", methodPath);
                if (!methodNames.add(methodName)) {
                    fail(methodPath + "/name", "is duplicated in component " + componentId);
                }
                if (method.has("lifecycle") && method.has("invocation")) {
                    fail(methodPath, "must not contain both lifecycle and invocation");
                }
                String lifecycleValue = optionalEnum(method, "lifecycle", methodPath,
                        Set.of("load", "enable", "ready", "drain", "disable", "unload"), lifecycle);
                String invocationValue = optionalEnum(method, "invocation", methodPath,
                        Set.of("event", "command", "task", "packet"), invocations);
                methods.put(new MethodId(componentId, methodName),
                        new MethodAuthorization(platform, lifecycleValue, invocationValue));
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
            booleanField(module, "global", path);
            location(module.path("location"), path + "/location");
        }
    }

    private static void imports(JsonNode values, String path) {
        array(values, path);
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String itemPath = path + "/" + index;
            object(item, itemPath, Set.of("id", "forwardRef"), Set.of("id", "forwardRef"));
            text(item, "id", itemPath);
            booleanField(item, "forwardRef", itemPath);
        }
    }

    private static void decorators(JsonNode values, String path) {
        array(values, path);
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String itemPath = path + "/" + index;
            object(item, itemPath, Set.of("name", "arguments", "location"),
                    Set.of("name", "arguments", "location"));
            text(item, "name", itemPath);
            array(item.path("arguments"), itemPath + "/arguments");
            canonicalValues(item.path("arguments"), itemPath + "/arguments", 0);
            location(item.path("location"), itemPath + "/location");
        }
    }

    private static void dependencies(JsonNode values, String path) {
        array(values, path);
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String itemPath = path + "/" + index;
            object(item, itemPath, Set.of("index", "property", "token", "optional", "all", "lazy", "name",
                    "qualifier", "location"), Set.of("token", "location"));
            optionalNonnegativeInteger(item, "index", itemPath);
            optionalText(item, "property", itemPath);
            optionalBoolean(item, "optional", itemPath);
            optionalBoolean(item, "all", itemPath);
            optionalBoolean(item, "lazy", itemPath);
            optionalText(item, "name", itemPath);
            optionalText(item, "qualifier", itemPath);
            token(item.path("token"), itemPath + "/token");
            location(item.path("location"), itemPath + "/location");
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

    private static void parseCommunication(JsonNode value, Map<String, String> services,
            Map<String, String> events, Map<String, String> consumers, Map<String, String> consumerPolicies,
            Map<String, String> components, Map<String, ServiceProvider> serviceProviders) {
        object(value, "/communication", Set.of("services", "events", "consumers"),
                Set.of("services", "events", "consumers"));
        contracts(value.path("services"), "/communication/services", services, components, serviceProviders);
        contracts(value.path("events"), "/communication/events", events, null, null);
        JsonNode values = value.path("consumers");
        array(values, "/communication/consumers");
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String path = "/communication/consumers/" + index;
            object(item, path, Set.of("id", "versionRange", "dependentReload"),
                    Set.of("id", "versionRange", "dependentReload"));
            String id = text(item, "id", path);
            contractName(id, path + "/id");
            String range = text(item, "versionRange", path);
            SemverRange.parse(range, compilerPath(path + "/versionRange"));
            String policy = text(item, "dependentReload", path);
            if (!Set.of("keep-running", "reload").contains(policy)) {
                fail(path + "/dependentReload", "is invalid");
            }
            duplicate(consumers.put(id, range), path + "/id");
            consumerPolicies.put(id, policy);
        }
    }

    private static void contracts(JsonNode values, String base, Map<String, String> target,
            Map<String, String> components, Map<String, ServiceProvider> serviceProviders) {
        boolean service = components != null;
        array(values, base);
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String path = base + "/" + index;
            Set<String> allowed = service ? Set.of("id", "version", "componentId", "methods")
                    : Set.of("id", "version");
            object(item, path, allowed, allowed);
            String id = text(item, "id", path);
            contractName(id, path + "/id");
            String contractVersion = text(item, "version", path);
            VersionParser.parseSemantic(contractVersion, compilerPath(path + "/version"));
            String componentId = null;
            if (service) {
                componentId = text(item, "componentId", path);
                if (!components.containsKey(componentId)) {
                    fail(path + "/componentId", "does not reference a declared component");
                }
                strings(item.path("methods"), path + "/methods");
            }
            duplicate(target.put(id, contractVersion), path + "/id");
            if (service) {
                serviceProviders.put(id, new ServiceProvider(componentId, path + "/componentId"));
            }
        }
    }

    private static void duplicate(String previous, String path) {
        if (previous != null) {
            fail(path, "is duplicated");
        }
    }

    private static void contractName(String value, String path) {
        if (!value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
            fail(path, "is not a valid communication contract identifier");
        }
    }

    private static void location(JsonNode value, String path) {
        object(value, path, Set.of("file", "line", "column"), Set.of("file", "line", "column"));
        text(value, "file", path);
        positiveInteger(value, "line", path);
        positiveInteger(value, "column", path);
    }

    private static String optionalEnum(
            JsonNode node, String field, String path, Set<String> allowed, Set<String> sink) {
        if (node.has(field)) {
            String item = text(node, field, path);
            if (!allowed.contains(item)) {
                fail(path + "/" + field, "is invalid");
            }
            sink.add(item);
            return item;
        }
        return null;
    }

    private static Set<String> strings(JsonNode value, String path) {
        array(value, path);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (!item.isTextual() || item.textValue().isBlank()) {
                fail(path + "/" + index, "must be non-blank text");
            }
            String text = item.textValue();
            textLength(text, path + "/" + index);
            if (!result.add(text)) {
                fail(path + "/" + index, "is duplicated");
            }
        }
        return Set.copyOf(result);
    }

    private static String text(JsonNode node, String field, String path) {
        JsonNode item = node.path(field);
        if (!item.isTextual() || item.textValue().isBlank()) {
            fail(path + "/" + field, "must be non-blank text");
        }
        String text = item.textValue();
        textLength(text, path + "/" + field);
        return text;
    }

    private static String enumText(JsonNode node, String field, String path, Set<String> allowed) {
        String item = text(node, field, path);
        if (!allowed.contains(item)) {
            fail(path + "/" + field, "is invalid");
        }
        return item;
    }

    private static void optionalText(JsonNode node, String field, String path) {
        if (node.has(field)) {
            text(node, field, path);
        }
    }

    private static void optionalNonnegativeInteger(JsonNode node, String field, String path) {
        if (node.has(field) && integer(node, field, path) < 0) {
            fail(path + "/" + field, "must be nonnegative");
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
        JsonNode item = node.path(field);
        if (!item.isIntegralNumber()) {
            fail(path + "/" + field, "must be an integer");
        }
        return item.intValue();
    }

    private static int positiveInteger(JsonNode node, String field, String path) {
        int result = integer(node, field, path);
        if (result <= 0) {
            fail(path + "/" + field, "must be positive");
        }
        return result;
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
            if (!value.has(field) || value.path(field).isNull()) {
                fail(path + "/" + field, "is required");
            }
        }
    }

    private static void fail(String path, String message) {
        ManifestValidation.fail("invalid_compiler_metadata", compilerPath(path), message);
    }

    private static void textLength(String value, String path) {
        if (value.codePointCount(0, value.length()) > ManifestValidation.MAX_TEXT_LENGTH) {
            fail(path, "must be at most " + ManifestValidation.MAX_TEXT_LENGTH + " characters");
        }
    }

    private static String compilerPath(String path) {
        return "/compiler" + path;
    }

    private record ServiceProvider(String componentId, String path) {
    }
}
