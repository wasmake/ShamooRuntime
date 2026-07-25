package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.AvoidDuplicateLiterals"})
class ManifestCodecTest {
    private static final String INVALID_RANGE = "invalid_semver_range";
    private final ManifestCodec codec = new ManifestCodec();

    @Test
    void roundTripsCanonicalGoldenManifest() throws IOException {
        String golden = resource("/manifests/full-v2.json").strip();
        PluginDescriptor descriptor = codec.parse(golden);

        assertEquals("identity", descriptor.name());
        assertEquals(golden, codec.serialize(descriptor));
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.dependencies().required().clear());
    }

    @Test
    void rejectsUnknownAndMissingCriticalFields() {
        String golden = validJson();
        ManifestParseException unknown = assertThrows(ManifestParseException.class,
                () -> codec.parse(golden.replace("\"displayName\"", "\"unexpected\":true,\"displayName\"")));
        ManifestParseException missing = assertThrows(ManifestParseException.class,
                () -> codec.parse(golden.replace("\"manifest\":2", "\"removed\":2")));

        assertEquals("malformed_manifest", unknown.diagnostics().getFirst().code());
        assertEquals("missing_field", missing.diagnostics().getFirst().code());
    }

    @Test
    void rejectsInvalidIdentifiersPathsOrderingDebounceAndVersions() {
        assertValidation("invalid_plugin_id", validJson().replace("identity", "Identity Plugin"));
        assertValidation("unsafe_path", validJson().replace("\"./data\"", "\"../data\""));
        assertValidation("unsafe_path", validJson().replace("\"./data\"", "\"C:/data\""));
        assertValidation("invalid_debounce", validJson().replace("\"debounceMs\":500", "\"debounceMs\":60001"));
        assertValidation("unsupported_manifest_version",
                validJson().replace("\"manifest\":2", "\"manifest\":1"));
        assertValidation("invalid_semver", validJson().replace("\"version\":\"1.0.0\"", "\"version\":\"one\""));
        assertValidation(INVALID_RANGE, validJson().replace("\"api\":\"^1.0.0\"", "\"api\":\"[not a range\""));
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        ManifestParseException exception = assertThrows(ManifestParseException.class,
                () -> codec.parse(validJson().replace("\"manifest\":2",
                        "\"manifest\":2,\"manifest\":2")));

        assertTrue(exception.getMessage().contains("Duplicate field"));
    }

    @Test
    void rejectsTrailingJsonDocuments() {
        ManifestParseException exception = assertThrows(ManifestParseException.class,
                () -> codec.parse(validJson() + " {}"));

        assertEquals("malformed_manifest", exception.diagnostics().getFirst().code());
    }

    @Test
    void acceptsStrictSemverPrereleaseAndBuildButRejectsVersionPrefix() {
        assertEquals("1.2.3-alpha.1+build.5", new SemanticVersion("1.2.3-alpha.1+build.5").value());

        ManifestValidationException exception = assertThrows(ManifestValidationException.class,
                () -> new SemanticVersion("v1.2.3"));
        assertEquals("invalid_semver", exception.diagnostics().getFirst().code());
        assertEquals("/version", exception.diagnostics().getFirst().path());
    }

    @Test
    void reportsExactSemanticValuePointers() {
        assertValidationAt("invalid_semver", "/version",
                validJson().replace("\"version\":\"1.0.0\"", "\"version\":\"v1.0.0\""));
        assertValidationAt(INVALID_RANGE, "/shamoo/api",
                validJson().replace("\"api\":\"^1.0.0\"", "\"api\":\"[bad\""));
        assertValidationAt(INVALID_RANGE, "/shamoo/runtime",
                validJson().replace("\"runtime\":\"^1.0.0\"", "\"runtime\":\"[bad\""));
        assertValidationAt(INVALID_RANGE, "/platforms/paper/minecraft",
                validJson().replace("\"minecraft\":\"1.21.x\"", "\"minecraft\":\"[bad\""));
        assertValidationAt(INVALID_RANGE, "/platforms/paper/paperApi",
                validJson().replace("\"paperApi\":\"1.21.x\"", "\"paperApi\":\"[bad\""));
        assertValidationAt(INVALID_RANGE, "/platforms/velocity/velocityApi",
                validJson().replace("\"velocityApi\":\"3.x\"", "\"velocityApi\":\"[bad\""));
        assertValidationAt(INVALID_RANGE, "/dependencies/required/other.plugin",
                validJson().replace("\"required\":{}", "\"required\":{\"other.plugin\":\"[bad\"}"));
    }

    @Test
    void schemaPredictsSemverAndNonWhitespaceValues() throws IOException {
        JsonNode schema = new ObjectMapper().readTree(resource(
                "/dev/shamoo/runtime/protocol/plugin-manifest-v2.schema.json"));
        JsonNode definitions = schema.path("$defs");
        Pattern semver = Pattern.compile(definitions.path("semver").path("pattern").textValue());
        Pattern nonWhitespace = Pattern.compile(definitions.path("nonWhitespace").path("pattern").textValue());

        assertEquals("semver", definitions.path("semver").path("format").textValue());
        assertEquals("semver-range", definitions.path("semverRange").path("format").textValue());
        assertEquals(512, definitions.path("relativePath").path("anyOf").get(1).path("maxLength").intValue());
        assertTrue(semver.matcher("1.2.3-alpha.1+build.5").matches());
        assertFalse(semver.matcher("v1.2.3").matches());
        assertFalse(semver.matcher("01.2.3").matches());
        assertFalse(nonWhitespace.matcher(" \t").matches());
        assertEquals("#/$defs/nonWhitespace", schema.path("properties").path("displayName").path("$ref").textValue());
        assertEquals("#/$defs/semverRange", definitions.path("dependencyMap")
                .path("additionalProperties").path("$ref").textValue());

        String longPath = "./" + "a".repeat(300);
        assertEquals(longPath, codec.parse(validJson().replace("\"./data\"", "\"" + longPath + "\""))
                .node().filesystem().write().getFirst());
        assertValidation("unsafe_path", validJson().replace("\"./data\"", "\"./" + "a".repeat(511) + "\""));
    }

    @Test
    void protocolExceptionRetainsDiagnosticsThroughJavaSerialization() throws IOException, ClassNotFoundException {
        ManifestValidationException original = assertThrows(ManifestValidationException.class,
                () -> SemanticVersion.parse("v1.0.0", "/shamoo/runtime"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }

        RuntimeProtocolException restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (RuntimeProtocolException) input.readObject();
        }

        assertEquals(original.diagnostics(), restored.diagnostics());
        assertThrows(UnsupportedOperationException.class,
                () -> restored.diagnostics().add(new ProtocolDiagnostic("code", "", "message")));
    }

    @Test
    void validatesEnabledDiscriminatorsAndSafeFilesystemPaths() {
        assertValidation("missing_platform", validJson()
                .replace("{\"enabled\":true,\"minecraft\":\"1.21.x\",\"paperApi\":\"1.21.x\","
                        + "\"nms\":false,\"packets\":false}", "{\"enabled\":false}")
                .replace("{\"enabled\":true,\"velocityApi\":\"3.x\"}", "{\"enabled\":false}"));
        assertValidation("unsafe_path", validJson().replace("\"./data\"", "\"./../data\""));

        String disabledPaper = validJson().replace(
                "{\"enabled\":true,\"minecraft\":\"1.21.x\",\"paperApi\":\"1.21.x\",\"nms\":false,\"packets\":false}",
                "{\"enabled\":false}");
        assertTrue(codec.parse(disabledPaper).platforms().velocity().enabled());
        assertEquals(disabledPaper.strip(), codec.serialize(codec.parse(disabledPaper)));
        assertThrows(ManifestParseException.class, () -> codec.parse(disabledPaper.replace(
                "{\"enabled\":false}", "{\"enabled\":false,\"nms\":false}")));
    }

    @Test
    void rejectsLegacyCompilerFieldsAndComponentsForDisabledTargets() {
        assertValidationAt("invalid_compiler_metadata", "/compiler/compilerVersion",
                validJson().replace("\"version\":\"test\"", "\"compilerVersion\":\"test\""));
        assertValidationAt("invalid_compiler_metadata", "/compiler/permissions",
                validJson().replace("\"components\":[]", "\"permissions\":{},\"components\":[]"));
        String velocityComponent = "{\"id\":\"one\",\"kind\":\"component\",\"name\":\"One\","
                + "\"file\":\"src/one.ts\",\"platform\":\"velocity\",\"decorators\":[],"
                + "\"constructor\":[],\"properties\":[],\"methods\":[],"
                + "\"location\":{\"file\":\"src/one.ts\",\"line\":1,\"column\":1}}";
        String paperOnly = validJson().replace("\"components\":[]", "\"components\":[" + velocityComponent + "]")
                .replace("{\"enabled\":true,\"velocityApi\":\"3.x\"}", "{\"enabled\":false}");
        assertValidationAt("invalid_compiler_metadata", "/compiler/components/0/platform", paperOnly);
    }

    @Test
    void buildsExactCompilerMethodIndexAndRejectsDuplicateOrAmbiguousMethods() {
        String indexed = validJson().replace("\"components\":[]", "\"components\":["
                + component("listener", "common", method("onJoin", "\"invocation\":\"event\","), "") + "]");
        CompilerMetadata compiler = codec.parse(indexed).compiler();

        assertEquals("common", compiler.componentPlatforms().get("listener"));
        assertEquals("event", compiler.methods().get(
                new CompilerMetadata.MethodId("listener", "onJoin")).invocation());

        String duplicateComponents = validJson().replace("\"components\":[]", "\"components\":["
                + component("duplicate", "common", "", "") + ","
                + component("duplicate", "common", "", "") + "]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/components/1/id", duplicateComponents);

        String duplicateMethods = validJson().replace("\"components\":[]", "\"components\":["
                + component("listener", "common", method("same", "") + "," + method("same", ""), "")
                + "]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/components/0/methods/1/name",
                duplicateMethods);

        String ambiguous = validJson().replace("\"components\":[]", "\"components\":["
                + component("listener", "common",
                        method("same", "\"lifecycle\":\"load\",\"invocation\":\"event\","), "") + "]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/components/0/methods/0", ambiguous);
    }

    @Test
    void rejectsUnknownAndPlatformSpecificUniversalServiceProviders() {
        String unknown = validJson().replace("\"services\":[]", "\"services\":[{\"id\":\"api\","
                + "\"version\":\"1.0.0\",\"componentId\":\"missing\",\"methods\":[]}]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/communication/services/0/componentId", unknown);

        String provider = validJson().replace("\"components\":[]", "\"components\":["
                + component("provider", "paper", "", "") + "]")
                .replace("\"services\":[]", "\"services\":[{\"id\":\"api\",\"version\":\"1.0.0\","
                        + "\"componentId\":\"provider\",\"methods\":[]}]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/communication/services/0/componentId",
                provider);
    }

    @Test
    void enforcesCompilerTextManifestAndCanonicalDepthLimits() {
        assertValidationAt("invalid_compiler_metadata", "/compiler/version",
                validJson().replace("\"version\":\"test\",\"components\"",
                        "\"version\":\"" + "x".repeat(257) + "\",\"components\""));

        String nested = "0";
        for (int depth = 0; depth < 65; depth++) {
            nested = "[" + nested + "]";
        }
        String injection = "{\"token\":{\"kind\":\"token\",\"value\":" + nested + "},"
                + "\"location\":{\"file\":\"src/plugin.ts\",\"line\":1,\"column\":1}}";
        String tooDeep = validJson().replace("\"components\":[]", "\"components\":["
                + component("nested", "common", "", injection) + "]");
        assertValidation("invalid_compiler_metadata", tooDeep);

        String invalidLocation = validJson().replace("\"components\":[]", "\"components\":["
                + component("located", "common", "", "").replace("\"line\":1", "\"line\":0") + "]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/components/0/location/line", invalidLocation);

        String invalidIndex = validJson().replace("\"components\":[]", "\"components\":["
                + component("indexed", "common", "",
                        "{\"index\":-1,\"token\":{\"kind\":\"token\",\"value\":\"value\"},"
                                + "\"location\":{\"file\":\"src/plugin.ts\",\"line\":1,\"column\":1}}")
                + "]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/components/0/constructor/0/index", invalidIndex);

        String invalidContract = validJson().replace("\"events\":[]",
                "\"events\":[{\"id\":\"example/event\",\"version\":\"1.0.0\"}]");
        assertValidationAt("invalid_compiler_metadata", "/compiler/communication/events/0/id", invalidContract);

        ManifestParseException tooLarge = assertThrows(ManifestParseException.class,
                () -> codec.parse(validJson() + " ".repeat(ManifestCodec.MAX_MANIFEST_BYTES)));
        assertEquals("manifest_too_large", tooLarge.diagnostics().getFirst().code());
    }

    private void assertValidation(String code, String json) {
        ManifestValidationException exception = assertThrows(
                ManifestValidationException.class, () -> codec.parse(json));
        assertEquals(code, exception.diagnostics().getFirst().code());
    }

    private void assertValidationAt(String code, String path, String json) {
        ManifestValidationException exception = assertThrows(
                ManifestValidationException.class, () -> codec.parse(json));
        assertEquals(code, exception.diagnostics().getFirst().code());
        assertEquals(path, exception.diagnostics().getFirst().path());
    }

    private String validJson() {
        try {
            return resource("/manifests/full-v2.json");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = ManifestCodecTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String component(String id, String platform, String methods, String constructor) {
        return "{\"id\":\"" + id + "\",\"kind\":\"component\",\"name\":\"Component\","
                + "\"file\":\"src/plugin.ts\",\"platform\":\"" + platform + "\",\"decorators\":[],"
                + "\"constructor\":[" + constructor + "],\"properties\":[],\"methods\":[" + methods + "],"
                + "\"location\":{\"file\":\"src/plugin.ts\",\"line\":1,\"column\":1}}";
    }

    private static String method(String name, String classification) {
        return "{\"name\":\"" + name + "\"," + classification
                + "\"decorators\":[],\"parameters\":[],"
                + "\"location\":{\"file\":\"src/plugin.ts\",\"line\":1,\"column\":1}}";
    }
}
