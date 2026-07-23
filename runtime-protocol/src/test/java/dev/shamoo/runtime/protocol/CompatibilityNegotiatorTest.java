package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class CompatibilityNegotiatorTest {
    private static final String VERSION_1 = "1.0.0";
    private final CompatibilityNegotiator negotiator = new CompatibilityNegotiator();

    @Test
    void acceptsMatchingRuntimeAndPrereleaseAwareRanges() throws IOException {
        CompatibilityResult result = negotiator.negotiate(descriptor(), input(
                PlatformKind.PAPER, "1.21.8", "1.21.5", null, VERSION_1, VERSION_1, EnumSet.of(
                        RuntimeCapability.NODE_BUILTINS,
                        RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE)));

        assertTrue(result.compatible());
        assertTrue(result.reasons().isEmpty());
        assertFalse(new SemverRange("^1.0.0").includes(new SemanticVersion("2.0.0-beta.1")));
    }

    @Test
    void reportsEveryActionableMismatchInStableOrder() throws IOException {
        CompatibilityResult result = negotiator.negotiate(descriptor(), input(
                PlatformKind.VELOCITY, null, null, "4.0.0", "2.0.0", "2.0.0",
                EnumSet.noneOf(RuntimeCapability.class)));

        assertFalse(result.compatible());
        assertEquals(java.util.List.of(
                "runtime_version", "api_version", "velocity_api_version", "missing_capability",
                "missing_capability", "missing_capability"),
                result.reasons().stream().map(IncompatibilityReason::code).toList());
    }

    @Test
    void checksMinecraftAndPaperApiIndependently() throws IOException {
        CompatibilityResult result = negotiator.negotiate(descriptor(), input(
                PlatformKind.PAPER, "1.22.0", "1.20.6", null, VERSION_1, VERSION_1, EnumSet.of(
                        RuntimeCapability.NODE_BUILTINS,
                        RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE)));

        assertEquals(java.util.List.of("minecraft_version", "paper_api_version"),
                result.reasons().stream().map(IncompatibilityReason::code).toList());
    }

    @Test
    void acceptsRuntimeProtocolWithCurrentMajorAndNewerMinor() throws IOException {
        CompatibilityInput runtime = input(
                PlatformKind.PAPER, "1.21.8", "1.21.5", null, VERSION_1, VERSION_1, EnumSet.of(
                        RuntimeCapability.NODE_BUILTINS,
                        RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE));
        runtime = new CompatibilityInput(runtime.platform(), runtime.minecraftVersion(), runtime.paperApiVersion(),
                runtime.velocityApiVersion(), runtime.capabilities(), runtime.runtimeVersion(), runtime.apiVersion(),
                new ProtocolVersion(ProtocolVersion.CURRENT.major(), ProtocolVersion.CURRENT.minor() + 1));

        assertTrue(negotiator.negotiate(descriptor(), runtime).compatible());
    }

    @Test
    void rejectsRuntimeProtocolWithDifferentMajor() throws IOException {
        CompatibilityInput runtime = input(
                PlatformKind.PAPER, "1.21.8", "1.21.5", null, VERSION_1, VERSION_1, EnumSet.of(
                        RuntimeCapability.NODE_BUILTINS,
                        RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE));
        runtime = new CompatibilityInput(runtime.platform(), runtime.minecraftVersion(), runtime.paperApiVersion(),
                runtime.velocityApiVersion(), runtime.capabilities(), runtime.runtimeVersion(), runtime.apiVersion(),
                new ProtocolVersion(ProtocolVersion.CURRENT.major() + 1, ProtocolVersion.CURRENT.minor()));

        CompatibilityResult result = negotiator.negotiate(descriptor(), runtime);

        assertFalse(result.compatible());
        assertEquals(java.util.List.of("protocol_version"),
                result.reasons().stream().map(IncompatibilityReason::code).toList());
    }

    private static CompatibilityInput input(
            PlatformKind platform,
            String minecraftVersion,
            String paperApiVersion,
            String velocityApiVersion,
            String apiVersion,
            String runtimeVersion,
            Set<RuntimeCapability> capabilities) {
        return new CompatibilityInput(platform, versionOrNull(minecraftVersion), versionOrNull(paperApiVersion),
                versionOrNull(velocityApiVersion), capabilities,
                new SemanticVersion(runtimeVersion), new SemanticVersion(apiVersion), ProtocolVersion.CURRENT);
    }

    private static SemanticVersion versionOrNull(String value) {
        return value == null ? null : new SemanticVersion(value);
    }

    private static PluginDescriptor descriptor() throws IOException {
        try (InputStream stream = CompatibilityNegotiatorTest.class.getResourceAsStream("/manifests/full-v1.json")) {
            if (stream == null) {
                throw new IOException("Missing golden manifest");
            }
            return new ManifestCodec().parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
