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

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class ManifestCodecTest {
    private static final String INVALID_RANGE = "invalid_semver_range";
    private final ManifestCodec codec = new ManifestCodec();

    @Test
    void roundTripsCanonicalGoldenManifest() throws IOException {
        String golden = resource("/manifests/full-v1.json").strip();
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
                () -> codec.parse(golden.replace("\"manifest\":1", "\"removed\":1")));

        assertEquals("malformed_manifest", unknown.diagnostics().getFirst().code());
        assertEquals("missing_field", missing.diagnostics().getFirst().code());
    }

    @Test
    void rejectsInvalidIdentifiersPathsOrderingDebounceAndVersions() {
        assertValidation("invalid_plugin_id", validJson().replace("identity", "Identity Plugin"));
        assertValidation("unsafe_path", validJson().replace("dist/paper.mjs", "../paper.mjs"));
        assertValidation("unsafe_path", validJson().replace("dist/paper.mjs", "C:/paper.mjs"));
        assertValidation("invalid_entrypoint", validJson().replace("dist/paper.mjs", "dist/paper.txt"));
        assertValidation("invalid_debounce", validJson().replace("\"debounceMs\":500", "\"debounceMs\":60001"));
        assertValidation("unsupported_manifest_version",
                validJson().replace("\"manifest\":1", "\"manifest\":2"));
        assertValidation("invalid_semver", validJson().replace("\"version\":\"1.0.0\"", "\"version\":\"one\""));
        assertValidation(INVALID_RANGE, validJson().replace("\"api\":\"^1.0.0\"", "\"api\":\"[not a range\""));
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        ManifestParseException exception = assertThrows(ManifestParseException.class,
                () -> codec.parse(validJson().replace("\"manifest\":1",
                        "\"manifest\":1,\"manifest\":1")));

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
                "/dev/shamoo/runtime/protocol/plugin-manifest-v1.schema.json"));
        JsonNode definitions = schema.path("$defs");
        Pattern semver = Pattern.compile(definitions.path("semver").path("pattern").textValue());
        Pattern nonWhitespace = Pattern.compile(definitions.path("nonWhitespace").path("pattern").textValue());

        assertEquals("semver", definitions.path("semver").path("format").textValue());
        assertEquals("semver-range", definitions.path("semverRange").path("format").textValue());
        assertTrue(semver.matcher("1.2.3-alpha.1+build.5").matches());
        assertFalse(semver.matcher("v1.2.3").matches());
        assertFalse(semver.matcher("01.2.3").matches());
        assertFalse(nonWhitespace.matcher(" \t").matches());
        assertEquals("#/$defs/nonWhitespace", schema.path("properties").path("displayName").path("$ref").textValue());
        assertEquals("#/$defs/semverRange", definitions.path("dependencyMap")
                .path("additionalProperties").path("$ref").textValue());
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
                .replace("\"paper\":{\"enabled\":true", "\"paper\":{\"enabled\":false")
                .replace("\"velocity\":{\"enabled\":true", "\"velocity\":{\"enabled\":false"));
        assertValidation("unsafe_path", validJson().replace("\"./data\"", "\"./../data\""));

        String disabledPaper = validJson().replace(
                "{\"enabled\":true,\"entrypoint\":\"dist/paper.mjs\",\"minecraft\":\"1.21.x\",\"paperApi\":\"1.21.x\"}",
                "{\"enabled\":false}");
        assertTrue(codec.parse(disabledPaper).platforms().velocity().enabled());
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
            return resource("/manifests/full-v1.json");
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
}
