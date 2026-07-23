package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/** Strict parser and deterministic serializer for canonical manifest JSON. */
public final class ManifestCodec {
    private static final String DEPENDENCIES = "dependencies";
    private static final String ENABLED = "enabled";
    private static final String ENTRYPOINT = "entrypoint";
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public PluginDescriptor parse(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = MAPPER.readTree(json);
            verifyRequiredFields(root);
            verifySemanticValues(root);
            return MAPPER.treeToValue(root, PluginDescriptor.class);
        } catch (JsonProcessingException exception) {
            ManifestValidationException validation = findValidation(exception);
            if (validation != null) {
                throw validation;
            }
            String path = exception instanceof JsonMappingException mapping ? mapping.getPathReference() : "";
            ProtocolDiagnostic diagnostic = new ProtocolDiagnostic(
                    "malformed_manifest", path == null ? "" : path, exception.getOriginalMessage());
            throw new ManifestParseException(diagnostic, exception);
        }
    }

    public String serialize(PluginDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        try {
            return MAPPER.writeValueAsString(descriptor);
        } catch (JsonProcessingException exception) {
            ProtocolDiagnostic diagnostic = new ProtocolDiagnostic(
                    "manifest_serialization_failed", "", "could not serialize plugin descriptor");
            throw new ManifestSerializationException(diagnostic, exception);
        }
    }

    private static ManifestValidationException findValidation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ManifestValidationException validation) {
                return validation;
            }
            current = current.getCause();
        }
        return null;
    }

    private static void verifyRequiredFields(JsonNode root) {
        require(root, "", "name", "displayName", "version", "shamoo", "platforms", DEPENDENCIES, "node", "reload");
        require(root.path("shamoo"), "/shamoo", "api", "runtime", "manifest");
        JsonNode platforms = root.path("platforms");
        require(platforms, "/platforms", "paper", "velocity");
        JsonNode paper = platforms.path("paper");
        JsonNode velocity = platforms.path("velocity");
        require(paper, "/platforms/paper", ENABLED);
        require(velocity, "/platforms/velocity", ENABLED);
        rejectNullWhenPresent(paper, "/platforms/paper", ENTRYPOINT, "minecraft", "paperApi");
        rejectNullWhenPresent(velocity, "/platforms/velocity", ENTRYPOINT, "velocityApi");
        if (paper.path(ENABLED).isBoolean() && paper.path(ENABLED).booleanValue()) {
            require(paper, "/platforms/paper", ENTRYPOINT, "minecraft", "paperApi");
        }
        if (velocity.path(ENABLED).isBoolean() && velocity.path(ENABLED).booleanValue()) {
            require(velocity, "/platforms/velocity", ENTRYPOINT, "velocityApi");
        }
        require(root.path(DEPENDENCIES), "/dependencies", "required", "optional", "loadBefore", "loadAfter");
        require(root.path("node"), "/node", "builtins", "filesystem", "network", "workers",
                "childProcess", "nativeAddons");
        require(root.path("node").path("filesystem"), "/node/filesystem", "read", "write");
        require(root.path("reload"), "/reload", "watch", "debounceMs", "preserveState");
    }

    private static void require(JsonNode node, String path, String... names) {
        for (String name : names) {
            if (!node.isObject() || !node.has(name) || node.path(name).isNull()) {
                ProtocolDiagnostic diagnostic = new ProtocolDiagnostic(
                        "missing_field", path + "/" + name, "required manifest field is missing");
                throw new ManifestParseException(diagnostic, null);
            }
        }
    }

    private static void rejectNullWhenPresent(JsonNode node, String path, String... names) {
        for (String name : names) {
            if (node.has(name) && node.path(name).isNull()) {
                ProtocolDiagnostic diagnostic = new ProtocolDiagnostic(
                        "invalid_value", path + "/" + name, "manifest field must not be null");
                throw new ManifestParseException(diagnostic, null);
            }
        }
    }

    private static void verifySemanticValues(JsonNode root) {
        validateVersion(root.path("version"), "/version");
        JsonNode shamoo = root.path("shamoo");
        validateRange(shamoo.path("api"), "/shamoo/api");
        validateRange(shamoo.path("runtime"), "/shamoo/runtime");

        JsonNode platforms = root.path("platforms");
        JsonNode paper = platforms.path("paper");
        validateRange(paper.path("minecraft"), "/platforms/paper/minecraft");
        validateRange(paper.path("paperApi"), "/platforms/paper/paperApi");
        validateRange(platforms.path("velocity").path("velocityApi"), "/platforms/velocity/velocityApi");

        validateDependencyRanges(root.path(DEPENDENCIES).path("required"), "/dependencies/required");
        validateDependencyRanges(root.path(DEPENDENCIES).path("optional"), "/dependencies/optional");
    }

    private static void validateVersion(JsonNode node, String path) {
        if (node.isTextual()) {
            SemanticVersion.validate(node.textValue(), path);
        }
    }

    private static void validateRange(JsonNode node, String path) {
        if (node.isTextual()) {
            SemverRange.validate(node.textValue(), path);
        }
    }

    private static void validateDependencyRanges(JsonNode node, String path) {
        if (node.isObject()) {
            node.properties().forEach(entry -> validateRange(
                    entry.getValue(), path + "/" + escapePointerToken(entry.getKey())));
        }
    }

    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
